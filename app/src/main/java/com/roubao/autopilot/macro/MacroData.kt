package com.roubao.autopilot.macro

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 宏动作类型
 */
enum class MacroActionType {
    CLICK,          // 点击
    LONG_PRESS,     // 长按
    DOUBLE_TAP,     // 双击
    SWIPE,          // 滑动
    TYPE,           // 输入文字
    SYSTEM_BUTTON,  // 系统按键 (Back/Home/Enter)
    WAIT,           // 等待
    OPEN_APP        // 打开应用
}

/**
 * 宏动作 - 记录单个操作
 */
data class MacroAction(
    val type: MacroActionType,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,           // swipe 终点
    val y2: Int? = null,
    val index: Int? = null,        // 无障碍元素索引
    val text: String? = null,      // 输入文字或应用名
    val button: String? = null,    // 系统按键名
    val duration: Int? = null,     // 等待时长（秒）或长按时长
    val delay: Long = 0,           // 执行前的延迟（毫秒）
    val description: String = ""   // 动作描述
) {
    /**
     * 是否使用索引模式
     */
    val isIndexMode: Boolean get() = index != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        x2?.let { put("x2", it) }
        y2?.let { put("y2", it) }
        index?.let { put("index", it) }
        text?.let { put("text", it) }
        button?.let { put("button", it) }
        duration?.let { put("duration", it) }
        put("delay", delay)
        put("description", description)
    }

    companion object {
        fun fromJson(json: JSONObject): MacroAction = MacroAction(
            type = try {
                MacroActionType.valueOf(json.optString("type", "CLICK"))
            } catch (e: Exception) {
                MacroActionType.CLICK
            },
            x = if (json.has("x")) json.optInt("x") else null,
            y = if (json.has("y")) json.optInt("y") else null,
            x2 = if (json.has("x2")) json.optInt("x2") else null,
            y2 = if (json.has("y2")) json.optInt("y2") else null,
            index = if (json.has("index")) json.optInt("index") else null,
            text = json.optString("text", null),
            button = json.optString("button", null),
            duration = if (json.has("duration")) json.optInt("duration") else null,
            delay = json.optLong("delay", 0),
            description = json.optString("description", "")
        )

        /**
         * 从 Agent Action 转换
         */
        fun fromAgentAction(
            action: com.roubao.autopilot.agent.Action,
            description: String = "",
            delay: Long = 0
        ): MacroAction? {
            val type = when (action.type) {
                "click" -> MacroActionType.CLICK
                "long_press" -> MacroActionType.LONG_PRESS
                "double_tap" -> MacroActionType.DOUBLE_TAP
                "swipe" -> MacroActionType.SWIPE
                "type" -> MacroActionType.TYPE
                "system_button" -> MacroActionType.SYSTEM_BUTTON
                "wait" -> MacroActionType.WAIT
                "open_app" -> MacroActionType.OPEN_APP
                else -> return null
            }
            return MacroAction(
                type = type,
                x = action.x,
                y = action.y,
                x2 = action.x2,
                y2 = action.y2,
                index = action.index,
                text = action.text,
                button = action.button,
                duration = action.duration,
                delay = delay,
                description = description
            )
        }
    }

    /**
     * 转换为 Agent Action
     */
    fun toAgentAction(): com.roubao.autopilot.agent.Action {
        val actionType = when (type) {
            MacroActionType.CLICK -> "click"
            MacroActionType.LONG_PRESS -> "long_press"
            MacroActionType.DOUBLE_TAP -> "double_tap"
            MacroActionType.SWIPE -> "swipe"
            MacroActionType.TYPE -> "type"
            MacroActionType.SYSTEM_BUTTON -> "system_button"
            MacroActionType.WAIT -> "wait"
            MacroActionType.OPEN_APP -> "open_app"
        }
        return com.roubao.autopilot.agent.Action(
            type = actionType,
            x = x,
            y = y,
            x2 = x2,
            y2 = y2,
            index = index,
            text = text,
            button = button,
            duration = duration
        )
    }

    /**
     * 获取动作的简短描述
     */
    fun getShortDescription(): String {
        if (description.isNotEmpty()) return description
        return when (type) {
            MacroActionType.CLICK -> {
                if (index != null) "点击元素 #$index"
                else "点击 ($x, $y)"
            }
            MacroActionType.LONG_PRESS -> {
                if (index != null) "长按元素 #$index"
                else "长按 ($x, $y)"
            }
            MacroActionType.DOUBLE_TAP -> "双击 ($x, $y)"
            MacroActionType.SWIPE -> "滑动 ($x,$y) → ($x2,$y2)"
            MacroActionType.TYPE -> "输入: ${text?.take(20)}${if ((text?.length ?: 0) > 20) "..." else ""}"
            MacroActionType.SYSTEM_BUTTON -> "按键: $button"
            MacroActionType.WAIT -> "等待 ${duration}秒"
            MacroActionType.OPEN_APP -> "打开: $text"
        }
    }
}

/**
 * 宏脚本 - 包含多个动作的序列
 */
data class MacroScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val actions: List<MacroAction> = emptyList(),
    val tags: List<String> = emptyList(),
    val sourceInstruction: String = "",   // 原始指令（如果是从 Agent 执行录制的）
    val loopCount: Int = 1,               // 循环次数（0 = 无限循环）
    val loopDelay: Long = 0               // 每次循环之间的延迟（毫秒）
) {
    val actionCount: Int get() = actions.size

    val formattedCreatedAt: String get() {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(createdAt))
    }

    val estimatedDuration: Long get() {
        var total = 0L
        actions.forEach { action ->
            total += action.delay
            when (action.type) {
                MacroActionType.WAIT -> total += (action.duration ?: 0) * 1000L
                MacroActionType.SWIPE -> total += 500L
                MacroActionType.LONG_PRESS -> total += (action.duration ?: 1) * 1000L
                else -> total += 200L
            }
        }
        return total
    }

    val formattedEstimatedDuration: String get() {
        val seconds = estimatedDuration / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分${seconds % 60}秒"
            else -> "${seconds / 3600}小时${(seconds % 3600) / 60}分"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("actions", JSONArray().apply {
            actions.forEach { put(it.toJson()) }
        })
        put("tags", JSONArray().apply {
            tags.forEach { put(it) }
        })
        put("sourceInstruction", sourceInstruction)
        put("loopCount", loopCount)
        put("loopDelay", loopDelay)
    }

    companion object {
        fun fromJson(json: JSONObject): MacroScript {
            val actionsArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = mutableListOf<MacroAction>()
            for (i in 0 until actionsArray.length()) {
                actions.add(MacroAction.fromJson(actionsArray.getJSONObject(i)))
            }

            val tagsArray = json.optJSONArray("tags") ?: JSONArray()
            val tags = mutableListOf<String>()
            for (i in 0 until tagsArray.length()) {
                tags.add(tagsArray.optString(i, ""))
            }

            return MacroScript(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "未命名脚本"),
                description = json.optString("description", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                actions = actions,
                tags = tags,
                sourceInstruction = json.optString("sourceInstruction", ""),
                loopCount = json.optInt("loopCount", 1),
                loopDelay = json.optLong("loopDelay", 0)
            )
        }
    }
}

/**
 * 宏执行状态
 */
enum class MacroPlayState {
    IDLE,       // 空闲
    PLAYING,    // 播放中
    PAUSED,     // 暂停
    STOPPED     // 已停止
}

/**
 * 宏执行进度
 */
data class MacroPlayProgress(
    val state: MacroPlayState = MacroPlayState.IDLE,
    val currentLoop: Int = 0,
    val totalLoops: Int = 1,
    val currentAction: Int = 0,
    val totalActions: Int = 0,
    val currentActionDescription: String = "",
    val errorMessage: String? = null
)
