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

    // 用户自定义系统提示词（设置中可修改，为空时使用默认提示词）
    var customSystemPrompt: String = "",

    // 跨会话长期记忆（之前任务的摘要，注入提示词以保持连贯）
    var longTermMemory: String = "",

    // Skill 上下文（从 SkillManager 获取的相关技能信息）
    var skillContext: String = "",

    // 对话记忆（保存完整对话历史，用于 Executor）
    var executorMemory: ConversationMemory? = null,

    // 已安装应用列表（用于 open_app 动作）
    var installedApps: String = "",

    // 当前屏幕的元素树清单（无障碍感知，格式化后的文本，注入 Executor 提示词）
    var uiElements: String = ""
)

/**
 * 动作定义
 * 支持的动作类型:
 * - click, double_tap, long_press: 点击类操作
 * - swipe, drag: 滑动类操作
 * - type: 输入文字
 * - system_button: 系统按键 (Back, Home, Enter)
 * - open_app, open: 打开应用
 * - answer: 回答问题
 * - wait: 等待
 * - take_over, ask_user: 人机交互
 * - terminate: 任务结束
 */
data class Action(
    val type: String,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val index: Int? = null,          // 元素树清单序号（按序号直达节点，优先于坐标）
    val text: String? = null,
    val button: String? = null,      // Back, Home, Enter, menu
    val duration: Int? = null,       // wait 动作的等待时长（秒）
    val message: String? = null,     // take_over/ask_user 动作的提示消息
    val needConfirm: Boolean = false,
    val direction: String? = null,   // swipe 方向: up, down, left, right
    val status: String? = null       // terminate 状态: success, fail
) {
    companion object {
        private const val SCALE_FACTOR = 999  // MAI-UI 坐标缩放因子

        /**
         * 从 JSON 字符串解析 Action
         * 支持两种格式:
         * 1. 标准格式: {"action": "click", "coordinate": [x, y]}
         * 2. MAI-UI 格式: <tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
         */
        fun fromJson(json: String): Action? {
            val cleanJson = json.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim()

            // 检查是否是 MAI-UI 的 <tool_call> 格式
            if (cleanJson.contains("<tool_call>")) {
                return fromMAIUIFormat(cleanJson)
            }

            return fromStandardJson(cleanJson)
        }

        /**
         * 解析标准 JSON 格式
         */
        private fun fromStandardJson(json: String): Action? {
            return try {
                val obj = JSONObject(json)
                val type = obj.optString("action", "")

                Action(
                    type = type,
                    x = obj.optJSONArray("coordinate")?.optInt(0),
                    y = obj.optJSONArray("coordinate")?.optInt(1),
                    x2 = obj.optJSONArray("coordinate2")?.optInt(0),
                    y2 = obj.optJSONArray("coordinate2")?.optInt(1),
                    index = if (obj.has("index")) obj.optInt("index", -1).takeIf { it >= 0 } else null,
                    text = obj.optString("text", null),
                    button = obj.optString("button", null),
                    duration = if (obj.has("duration")) obj.optInt("duration", 3) else null,
                    message = obj.optString("message", null),
                    needConfirm = obj.optBoolean("need_confirm", false),
                    direction = obj.optString("direction", null),
                    status = obj.optString("status", null)
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 解析 MAI-UI 的 <tool_call> 格式
         * 格式: <tool_call>{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [x, y]}}</tool_call>
         */
        fun fromMAIUIFormat(response: String): Action? {
            return try {
                // 提取 <tool_call> 内容
                val toolCallRegex = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                val match = toolCallRegex.find(response) ?: return null
                val toolCallJson = match.groupValues[1].trim()

                val obj = JSONObject(toolCallJson)
                val arguments = obj.optJSONObject("arguments") ?: return null

                val type = arguments.optString("action", "")

                // MAI-UI 坐标是 0-999 归一化的，需要标记（在执行时处理）
                val coordinate = arguments.optJSONArray("coordinate")
                var x = coordinate?.optInt(0)
                var y = coordinate?.optInt(1)

                // MAI-UI drag 动作使用 start_coordinate 和 end_coordinate
                val startCoord = arguments.optJSONArray("start_coordinate")
                val endCoord = arguments.optJSONArray("end_coordinate")
                var x2: Int? = null
                var y2: Int? = null

                if (startCoord != null && endCoord != null) {
                    x = startCoord.optInt(0)
                    y = startCoord.optInt(1)
                    x2 = endCoord.optInt(0)
                    y2 = endCoord.optInt(1)
                }

                // 映射 MAI-UI 的动作类型到我们的类型
                val mappedType = when (type) {
                    "open" -> "open_app"
                    "double_click" -> "double_tap"
                    "drag" -> "swipe"  // drag 和 swipe 在执行层面相同
                    "ask_user" -> "take_over"
                    else -> type
                }

                Action(
                    type = mappedType,
                    x = x,
                    y = y,
                    x2 = x2,
                    y2 = y2,
                    text = arguments.optString("text", null),
                    button = arguments.optString("button", null),
                    direction = arguments.optString("direction", null),
                    status = arguments.optString("status", null)
                )
            } catch (e: Exception) {
                println("[Action] MAI-UI 格式解析失败: ${e.message}")
                null
            }
        }

        /**
         * 从 MAI-UI 响应中提取思考过程
         */
        fun extractThinking(response: String): String {
            val thinkingRegex = Regex("<thinking>\\s*(.+?)\\s*</thinking>", RegexOption.DOT_MATCHES_ALL)
            val match = thinkingRegex.find(response)
            return match?.groupValues?.get(1)?.trim() ?: ""
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
        if (needConfirm) obj.put("need_confirm", true)

        return obj.toString()
    }

    override fun toString(): String = toJson()
}
