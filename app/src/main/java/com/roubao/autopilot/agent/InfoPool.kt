package com.roubao.autopilot.agent

import org.json.JSONObject

/**
 * Agent 状态池 - 保存所有执行过程中的信息
 */
data class InfoPool(
    // 用户指令
    var instruction: String = "",

    // 规划相关
    var plan: String = "",
    var completedPlan: String = "",
    var progressStatus: String = "",
    var currentSubgoal: String = "",

    // 动作历史
    val actionHistory: MutableList<Action> = mutableListOf(),
    val summaryHistory: MutableList<String> = mutableListOf(),
    val actionOutcomes: MutableList<String> = mutableListOf(),  // A=成功, B=错误页面, C=无变化
    val errorDescriptions: MutableList<String> = mutableListOf(),

    // 最近一次动作
    var lastAction: Action? = null,
    var lastActionThought: String = "",
    var lastSummary: String = "",

    // 笔记
    var importantNotes: String = "",

    // 错误处理
    var errorFlagPlan: Boolean = false,
    val errToManagerThresh: Int = 2,

    // 屏幕尺寸
    var screenWidth: Int = 1080,
    var screenHeight: Int = 2400,

    // 额外知识
    var additionalKnowledge: String = "",

    // Skill 上下文（从 SkillManager 获取的相关技能信息）
    var skillContext: String = "",

    // 对话记忆（保存完整对话历史，用于 Executor）
    var executorMemory: ConversationMemory? = null,

    // 已安装应用列表（用于 open_app 动作）
    var installedApps: String = "",

    // 无障碍服务相关
    var accessibilityEnabled: Boolean = false,  // 无障碍服务是否可用
    var uiTreeContext: String = ""              // UI 树上下文（元素列表）
)

/**
 * 动作定义
 * 支持两种定位方式：
 * 1. 坐标模式：使用 x, y 坐标（传统视觉模式）
 * 2. 索引模式：使用 index 元素索引（无障碍服务模式）
 */
data class Action(
    val type: String,           // click, double_tap, swipe, type, system_button, open_app, answer, wait, take_over, scroll
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val index: Int? = null,     // 元素索引（无障碍服务模式）
    val text: String? = null,
    val button: String? = null,  // Back, Home, Enter
    val duration: Int? = null,   // wait 动作的等待时长（秒）
    val message: String? = null, // take_over 动作的提示消息，或敏感操作确认消息
    val needConfirm: Boolean = false,  // 敏感操作需要用户确认
    val direction: String? = null  // scroll 方向: up, down, left, right
) {
    /**
     * 是否使用索引模式
     */
    val isIndexMode: Boolean get() = index != null

    /**
     * 是否使用坐标模式
     */
    val isCoordinateMode: Boolean get() = x != null && y != null

    companion object {
        fun fromJson(json: String): Action? {
            return try {
                val obj = JSONObject(json.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim())

                val type = obj.optString("action", "")

                Action(
                    type = type,
                    x = obj.optJSONArray("coordinate")?.optInt(0),
                    y = obj.optJSONArray("coordinate")?.optInt(1),
                    x2 = obj.optJSONArray("coordinate2")?.optInt(0),
                    y2 = obj.optJSONArray("coordinate2")?.optInt(1),
                    index = if (obj.has("index")) obj.optInt("index") else null,
                    text = obj.optString("text", null),
                    button = obj.optString("button", null),
                    duration = if (obj.has("duration")) obj.optInt("duration", 3) else null,
                    message = obj.optString("message", null),
                    needConfirm = obj.optBoolean("need_confirm", false),
                    direction = obj.optString("direction", null)
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("action", type)

        if (x != null && y != null) {
            val coord = org.json.JSONArray()
            coord.put(x)
            coord.put(y)
            obj.put("coordinate", coord)
        }
        if (x2 != null && y2 != null) {
            val coord2 = org.json.JSONArray()
            coord2.put(x2)
            coord2.put(y2)
            obj.put("coordinate2", coord2)
        }
        index?.let { obj.put("index", it) }
        text?.let { obj.put("text", it) }
        button?.let { obj.put("button", it) }
        duration?.let { obj.put("duration", it) }
        message?.let { obj.put("message", it) }
        direction?.let { obj.put("direction", it) }
        if (needConfirm) obj.put("need_confirm", true)

        return obj.toString()
    }

    override fun toString(): String = toJson()
}
