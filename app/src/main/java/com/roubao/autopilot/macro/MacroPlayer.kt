package com.roubao.autopilot.macro

import com.roubao.autopilot.controller.DeviceController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 宏播放器 - 执行录制的宏脚本
 */
class MacroPlayer(private val deviceController: DeviceController) {

    private var playJob: Job? = null
    private var isPaused = false

    private val _progress = MutableStateFlow(MacroPlayProgress())
    val progress: StateFlow<MacroPlayProgress> = _progress.asStateFlow()

    /**
     * 播放宏脚本
     */
    suspend fun play(macro: MacroScript, scope: CoroutineScope) {
        if (_progress.value.state == MacroPlayState.PLAYING) {
            return
        }

        val totalLoops = if (macro.loopCount == 0) Int.MAX_VALUE else macro.loopCount

        _progress.value = MacroPlayProgress(
            state = MacroPlayState.PLAYING,
            currentLoop = 1,
            totalLoops = if (macro.loopCount == 0) -1 else macro.loopCount,
            currentAction = 0,
            totalActions = macro.actions.size
        )

        playJob = scope.launch(Dispatchers.IO) {
            try {
                for (loop in 1..totalLoops) {
                    if (!isActive) break

                    _progress.value = _progress.value.copy(
                        currentLoop = loop,
                        currentAction = 0
                    )

                    for ((index, action) in macro.actions.withIndex()) {
                        if (!isActive) break

                        // 检查暂停状态
                        while (isPaused && isActive) {
                            delay(100)
                        }

                        if (!isActive) break

                        _progress.value = _progress.value.copy(
                            currentAction = index + 1,
                            currentActionDescription = action.getShortDescription()
                        )

                        // 执行前延迟
                        if (action.delay > 0) {
                            delay(action.delay)
                        }

                        // 执行动作
                        val success = executeAction(action)
                        if (!success) {
                            _progress.value = _progress.value.copy(
                                state = MacroPlayState.STOPPED,
                                errorMessage = "动作执行失败: ${action.getShortDescription()}"
                            )
                            return@launch
                        }

                        // 动作间的最小间隔
                        delay(100)
                    }

                    // 循环间延迟
                    if (loop < totalLoops && macro.loopDelay > 0) {
                        delay(macro.loopDelay)
                    }
                }

                _progress.value = _progress.value.copy(
                    state = MacroPlayState.IDLE
                )
            } catch (e: CancellationException) {
                _progress.value = _progress.value.copy(
                    state = MacroPlayState.STOPPED
                )
            } catch (e: Exception) {
                _progress.value = _progress.value.copy(
                    state = MacroPlayState.STOPPED,
                    errorMessage = e.message ?: "未知错误"
                )
            }
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        if (_progress.value.state == MacroPlayState.PLAYING) {
            isPaused = true
            _progress.value = _progress.value.copy(state = MacroPlayState.PAUSED)
        }
    }

    /**
     * 恢复播放
     */
    fun resume() {
        if (_progress.value.state == MacroPlayState.PAUSED) {
            isPaused = false
            _progress.value = _progress.value.copy(state = MacroPlayState.PLAYING)
        }
    }

    /**
     * 停止播放
     */
    fun stop() {
        isPaused = false
        playJob?.cancel()
        playJob = null
        _progress.value = MacroPlayProgress(state = MacroPlayState.STOPPED)
    }

    /**
     * 重置状态
     */
    fun reset() {
        stop()
        _progress.value = MacroPlayProgress()
    }

    /**
     * 执行单个动作
     */
    private suspend fun executeAction(action: MacroAction): Boolean {
        return try {
            when (action.type) {
                MacroActionType.CLICK -> {
                    if (action.isIndexMode) {
                        deviceController.smartTap(action.index, action.x, action.y)
                    } else if (action.x != null && action.y != null) {
                        deviceController.tap(action.x, action.y)
                        true
                    } else {
                        false
                    }
                }

                MacroActionType.LONG_PRESS -> {
                    val duration = action.duration ?: 1
                    if (action.isIndexMode && action.index != null) {
                        deviceController.longPressByIndex(action.index, duration * 1000L)
                    } else if (action.x != null && action.y != null) {
                        deviceController.longPress(action.x, action.y, duration * 1000)
                        true
                    } else {
                        false
                    }
                }

                MacroActionType.DOUBLE_TAP -> {
                    if (action.x != null && action.y != null) {
                        deviceController.doubleTap(action.x, action.y)
                        true
                    } else {
                        false
                    }
                }

                MacroActionType.SWIPE -> {
                    if (action.x != null && action.y != null && action.x2 != null && action.y2 != null) {
                        deviceController.swipe(action.x, action.y, action.x2, action.y2)
                        true
                    } else {
                        false
                    }
                }

                MacroActionType.TYPE -> {
                    action.text?.let { text ->
                        if (action.isIndexMode && action.index != null) {
                            deviceController.smartType(action.index, text)
                        } else {
                            deviceController.type(text)
                            true
                        }
                    } ?: false
                }

                MacroActionType.SYSTEM_BUTTON -> {
                    when (action.button?.lowercase()) {
                        "back" -> {
                            deviceController.back()
                            true
                        }
                        "home" -> {
                            deviceController.home()
                            true
                        }
                        // Enter key not directly supported yet
                        else -> false
                    }
                }

                MacroActionType.WAIT -> {
                    val seconds = action.duration ?: 1
                    delay(seconds * 1000L)
                    true
                }

                MacroActionType.OPEN_APP -> {
                    action.text?.let { appName ->
                        deviceController.openApp(appName)
                        true
                    } ?: false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = _progress.value.state == MacroPlayState.PLAYING

    /**
     * 是否已暂停
     */
    fun isPaused(): Boolean = _progress.value.state == MacroPlayState.PAUSED
}
