package com.roubao.autopilot.vlm

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * VLM (Vision Language Model) API 客户端
 * 支持 OpenAI 兼容接口 (GPT-4V, Qwen-VL, Claude, etc.)
 */
class VLMClient(
    private val apiKey: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4-vision-preview"
) {
    // 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠
    private val baseUrl: String = normalizeUrl(baseUrl)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 1, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L

        /** 规范化 URL：自动添加 https:// 前缀，移除末尾斜杠 */
        private fun normalizeUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }

        /**
         * 从 API 获取可用模型列表
         * @param baseUrl API 基础地址
         * @param apiKey API 密钥
         * @return 模型 ID 列表
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
            // 验证 baseUrl 是否为空
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(Exception("Base URL 不能为空"))
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // 清理 URL，确保正确拼接
            val cleanBaseUrl = normalizeUrl(baseUrl.removeSuffix("/chat/completions"))

            val request = try {
                Request.Builder()
                    .url("$cleanBaseUrl/models")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .get()
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(Exception("Base URL 格式无效: ${e.message}"))
            }

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        val json = JSONObject(responseBody)
                        val data = json.optJSONArray("data") ?: JSONArray()
                        val models = mutableListOf<String>()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i)
                            if (item != null) {
                                val id = item.optString("id", "").trim()
                                if (id.isNotEmpty()) {
                                    models.add(id)
                                }
                            }
                        }
                        Result.success(models)
                    } else {
                        Result.failure(Exception("HTTP ${response.code}: $responseBody"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 调用 VLM 进行多模态推理 (带重试)
     */
    /** 接口返回了内容但不是正常回复时抛出，携带接口那边的真实错误信息 */
    private class ApiResponseException(message: String) : Exception(message)

    /**
     * 从接口返回体里提取模型回复文本。
     * 有些接口出错时 HTTP 仍返回 200，但内容里没有 choices、只有 error，
     * 以前只会报一句看不懂的 "No value for choices"，现在把真实原因带出来。
     */
    private fun extractContent(responseBody: String): String {
        val json = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw ApiResponseException("接口返回的不是有效 JSON: ${responseBody.take(200)}")
        }
        val error = json.optJSONObject("error")
        if (error != null) {
            val msg = error.optString("message").ifBlank { error.toString() }
            throw ApiResponseException("接口返回错误: $msg")
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiResponseException("接口没有返回正常回复，原始返回: ${responseBody.take(200)}")
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw ApiResponseException("接口回复缺少 message 字段")
        val content = message.optString("content")
        if (content.isNotBlank()) return content
        // 部分推理模型把正文放在 reasoning_content 里
        val reasoning = message.optString("reasoning_content")
        if (reasoning.isNotBlank()) return reasoning
        throw ApiResponseException("接口回复内容为空")
    }

    suspend fun predict(
        prompt: String,
        images: List<Bitmap> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // 预先编码图片 (避免重试时重复编码)
        val encodedImages = images.map { bitmapToBase64Url(it) }

        for (attempt in 1..MAX_RETRIES) {
            try {
                val content = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
                    encodedImages.forEach { imageUrl ->
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                            })
                        })
                    }
                }

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    })
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 8192)
                    put("temperature", 0.0)
                    put("top_p", 0.85)
                    put("frequency_penalty", 0.2)  // 减少重复输出
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return@withContext Result.success(extractContent(responseBody))
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                // DNS 解析失败，重试
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时，重试
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                // IO 错误，重试
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: ApiResponseException) {
                // 接口返回了错误内容（限流/额度/临时故障等），重试几次再放弃
                println("[VLMClient] ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                // 其他错误，不重试
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * 调用 VLM 进行多模态推理 (使用完整对话历史)
     * @param messagesJson OpenAI 兼容的 messages JSON 数组
     */
    suspend fun predictWithContext(
        messagesJson: JSONArray
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 8192)
                    put("temperature", 0.0)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return@withContext Result.success(extractContent(responseBody))
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: UnknownHostException) {
                println("[VLMClient] DNS 解析失败，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.net.SocketTimeoutException) {
                println("[VLMClient] 请求超时，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: java.io.IOException) {
                println("[VLMClient] IO 错误: ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: ApiResponseException) {
                // 接口返回了错误内容（限流/额度/临时故障等），重试几次再放弃
                println("[VLMClient] ${e.message}，重试 $attempt/$MAX_RETRIES...")
                lastException = e
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS * attempt)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    /**
     * Bitmap 转 Base64 URL (只压缩质量，不压缩分辨率)
     * 保持原始分辨率以确保坐标准确
     */
    /**
     * 多轮对话补全（角色扮演聊天用）：直接传入完整的 messages JSON，可指定温度
     */
    suspend fun chatCompletion(
        messagesJson: JSONArray,
        temperature: Double = 0.8
    ): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", messagesJson)
                    put("max_tokens", 8192)
                    put("temperature", temperature)
                }

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .apply {
                        if (apiKey.isNotBlank()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return@withContext Result.success(extractContent(responseBody))
                } else {
                    lastException = Exception("API error: ${response.code} - $responseBody")
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("Unknown error"))
    }

    private fun bitmapToBase64Url(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // 先把截图缩小到 1280 以内再编码：原图（如 1080x2400）上传和推理都很慢，
        // 模型用的是相对坐标，缩小不影响点击精度
        val scaled = resizeBitmap(bitmap, 1280, 1280)
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        println("[VLMClient] 图片压缩: ${scaled.width}x${scaled.height}, ${bytes.size / 1024}KB")
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * 调整图片大小
     */
    private fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

/**
 * 常用 VLM 配置
 */
object VLMConfigs {
    // OpenAI GPT-4V
    fun gpt4v(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4-vision-preview"
    )

    // Qwen-VL (阿里云)
    fun qwenVL(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-vl-max"
    )

    // Claude (Anthropic)
    fun claude(apiKey: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-3-5-sonnet-20241022"
    )

    // 自定义 (vLLM / Ollama / LocalAI)
    fun custom(apiKey: String, baseUrl: String, model: String) = VLMClient(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model
    )
}
