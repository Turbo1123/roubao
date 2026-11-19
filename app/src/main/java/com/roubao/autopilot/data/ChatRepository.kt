package com.roubao.autopilot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 聊天消息（角色扮演对话）
 * role: "user" / "assistant" / "system"（system 为不直接展示的上下文注记，如手机操作结果）
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 一个对话会话：独立的消息列表与压缩摘要，可重命名/置顶/删除
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "新对话",
    var pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var summary: String = "",
    var messages: List<ChatMessage> = emptyList()
)

/**
 * 聊天存储：多会话保存在独立的 SharedPreferences 中，兼容旧版单会话数据迁移
 */
class ChatRepository(context: Context) {

    private val prefs = context.getSharedPreferences("chat_data", Context.MODE_PRIVATE)

    fun loadSessions(): List<ChatSession> {
        val raw = prefs.getString("sessions_json", null)
        if (raw != null) {
            return try {
                parseSessions(raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
        // 旧版单会话数据迁移
        val legacyMessages = loadLegacyMessages()
        val legacySummary = prefs.getString("summary", "") ?: ""
        if (legacyMessages.isNotEmpty() || legacySummary.isNotBlank()) {
            val title = legacyMessages.firstOrNull { it.role == "user" }
                ?.content?.take(18) ?: "之前的对话"
            val session = ChatSession(
                title = title,
                summary = legacySummary,
                messages = legacyMessages
            )
            val sessions = listOf(session)
            saveSessions(sessions)
            setCurrentSessionId(session.id)
            prefs.edit().remove("messages_json").remove("summary").apply()
            return sessions
        }
        return emptyList()
    }

    private fun loadLegacyMessages(): List<ChatMessage> {
        val raw = prefs.getString("messages_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ChatMessage(
                        role = obj.optString("role", "user"),
                        content = obj.optString("content", ""),
                        timestamp = obj.optLong("ts", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSessions(raw: String): List<ChatSession> {
        val array = JSONArray(raw)
        val list = mutableListOf<ChatSession>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val msgsArray = obj.optJSONArray("messages") ?: JSONArray()
            val msgs = mutableListOf<ChatMessage>()
            for (j in 0 until msgsArray.length()) {
                val m = msgsArray.getJSONObject(j)
                msgs.add(
                    ChatMessage(
                        role = m.optString("role", "user"),
                        content = m.optString("content", ""),
                        timestamp = m.optLong("ts", 0L)
                    )
                )
            }
            list.add(
                ChatSession(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    title = obj.optString("title", "新对话"),
                    pinned = obj.optBoolean("pinned", false),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                    summary = obj.optString("summary", ""),
                    messages = msgs
                )
            )
        }
        return list
    }

    fun saveSessions(sessions: List<ChatSession>) {
        val array = JSONArray()
        for (session in sessions) {
            val msgsArray = JSONArray()
            for (msg in session.messages) {
                msgsArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                    put("ts", msg.timestamp)
                })
            }
            array.put(JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("pinned", session.pinned)
                put("createdAt", session.createdAt)
                put("updatedAt", session.updatedAt)
                put("summary", session.summary)
                put("messages", msgsArray)
            })
        }
        prefs.edit().putString("sessions_json", array.toString()).apply()
    }

    fun getCurrentSessionId(): String? {
        return prefs.getString("current_session_id", null)
    }

    fun setCurrentSessionId(id: String?) {
        prefs.edit().putString("current_session_id", id).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
