package com.roubao.autopilot.agent

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 对话记忆管理 - 保存完整对话历史，图片用完即删
 *
 * 参考 Open-AutoGLM 的设计：
 * - 保持完整对话历史，让模型看到之前所有操作
 * - 图片用完即删，节省 token
 */
class ConversationMemory {

    private val messages = mutableListOf<Message>()

    /**
     * 消息类型
     */
    data class Message(
        val role: String,  // "system", "user", "assistant"
        val textContent: String,
        var imageBase64: String? = null  // 图片用完后置为 null
    )

    /**
     * 添加系统消息（通常只在开始时添加一次）
     */
    fun addSystemMessage(text: String) {
        messages.add(Message(role = "system", textContent = text))
    }

    /**
     * 添加用户消息（带截图）
     */
    fun addUserMessage(text: String, image: Bitmap? = null) {
        val imageBase64 = image?.let { bitmapToBase64(it) }
        messages.add(Message(role = "user", textContent = text, imageBase64 = imageBase64))
    }

    /**
     * 添加助手消息
     */
    fun addAssistantMessage(text: String) {
        messages.add(Message(role = "assistant", textContent = text))
    }

    /**
     * 删除最后一条用户消息中的图片（节省 token）
     * 在获取模型响应后调用
     */
    fun stripLastUserImage() {
        for (i in messages.indices.reversed()) {
            if (messages[i].role == "user" && messages[i].imageBase64 != null) {
                messages[i].imageBase64 = null
                println("[ConversationMemory] 已删除第 $i 条消息的图片")
                break
            }
        }
    }

    /**
     * 获取最近 N 条消息（不包括系统消息）
     */
    fun getRecentMessages(count: Int): List<Message> {
        val nonSystemMessages = messages.filter { it.role != "system" }
        return nonSystemMessages.takeLast(count)
    }

    /**
     * 构建 OpenAI 兼容的 messages JSON
     * @param includeImages 是否包含图片（最后一条用户消息的图片）
     */
    fun toMessagesJson(includeImages: Boolean = true): JSONArray {
        val jsonArray = JSONArray()

        for ((index, msg) in messages.withIndex()) {
            val msgJson = JSONObject()
            msgJson.put("role", msg.role)

            // 判断是否需要包含图片
            val shouldIncludeImage = includeImages &&
                    msg.imageBase64 != null &&
                    index == messages.indexOfLast { it.role == "user" }

            if (shouldIncludeImage && msg.imageBase64 != null) {
                // 多模态消息格式
                val contentArray = JSONArray()
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.textContent)
                })
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                msgJson.put("content", contentArray)
            } else {
                // 纯文本消息
                msgJson.put("content", msg.textContent)
            }

            jsonArray.put(msgJson)
        }

        return jsonArray
    }

    /**
     * 获取消息数量
     */
    fun size(): Int = messages.size

    /**
     * 清空所有消息
     */
    fun clear() {
        messages.clear()
    }

    /**
     * 获取 token 估算（粗略）
     * 中文约 2 字符/token，英文约 4 字符/token
     */
    fun estimateTokens(): Int {
        var total = 0
        for (msg in messages) {
            // 文本 token
            total += msg.textContent.length / 3
            // 图片 token（约 1000 token 每张）
            if (msg.imageBase64 != null) {
                total += 1000
            }
        }
        return total
    }

    /**
     * Bitmap 转 Base64（先缩小到 1280 以内，原图上传太慢；相对坐标不受影响）
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val scaled = if (bitmap.width > 1280 || bitmap.height > 1280) {
            val ratio = minOf(1280f / bitmap.width, 1280f / bitmap.height)
            val newWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    companion object {
        /**
         * 创建带系统提示的记忆
         */
        fun withSystemPrompt(systemPrompt: String): ConversationMemory {
            return ConversationMemory().apply {
                addSystemMessage(systemPrompt)
            }
        }
    }
}
