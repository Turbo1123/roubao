package com.roubao.autopilot.macro

import com.roubao.autopilot.agent.Action

/**
 * 宏录制器 - 在 Agent 执行过程中记录动作
 */
class MacroRecorder {

    private var isRecording = false
    private var recordedActions = mutableListOf<RecordedAction>()
    private var lastActionTime = 0L
    private var startTime = 0L
    private var sourceInstruction = ""

    /**
     * 录制中的动作（带时间戳）
     */
    private data class RecordedAction(
        val action: MacroAction,
        val timestamp: Long
    )

    /**
     * 开始录制
     */
    fun startRecording(instruction: String = "") {
        isRecording = true
        recordedActions.clear()
        startTime = System.currentTimeMillis()
        lastActionTime = startTime
        sourceInstruction = instruction
    }

    /**
     * 停止录制
     */
    fun stopRecording(): MacroScript? {
        if (!isRecording || recordedActions.isEmpty()) {
            isRecording = false
            return null
        }

        isRecording = false

        // 计算每个动作的延迟
        val actionsWithDelay = recordedActions.mapIndexed { index, recorded ->
            val delay = if (index == 0) {
                0L
            } else {
                recorded.timestamp - recordedActions[index - 1].timestamp
            }
            recorded.action.copy(delay = delay)
        }

        return MacroScript(
            name = generateScriptName(),
            description = if (sourceInstruction.isNotEmpty()) "录制自: $sourceInstruction" else "手动录制",
            actions = actionsWithDelay,
            sourceInstruction = sourceInstruction
        )
    }

    /**
     * 取消录制
     */
    fun cancelRecording() {
        isRecording = false
        recordedActions.clear()
        sourceInstruction = ""
    }

    /**
     * 录制一个动作
     */
    fun recordAction(action: Action, description: String = "") {
        if (!isRecording) return

        val macroAction = MacroAction.fromAgentAction(action, description) ?: return
        recordedActions.add(RecordedAction(macroAction, System.currentTimeMillis()))
        lastActionTime = System.currentTimeMillis()
    }

    /**
     * 录制一个宏动作
     */
    fun recordMacroAction(action: MacroAction) {
        if (!isRecording) return

        recordedActions.add(RecordedAction(action, System.currentTimeMillis()))
        lastActionTime = System.currentTimeMillis()
    }

    /**
     * 是否正在录制
     */
    fun isRecording(): Boolean = isRecording

    /**
     * 获取当前录制的动作数量
     */
    fun getRecordedCount(): Int = recordedActions.size

    /**
     * 获取录制时长
     */
    fun getRecordingDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - startTime
        } else {
            0L
        }
    }

    /**
     * 生成脚本名称
     */
    private fun generateScriptName(): String {
        val dateFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val date = dateFormat.format(java.util.Date())
        return "录制_$date"
    }

    companion object {
        @Volatile
        private var instance: MacroRecorder? = null

        fun getInstance(): MacroRecorder {
            return instance ?: synchronized(this) {
                instance ?: MacroRecorder().also { instance = it }
            }
        }
    }
}
