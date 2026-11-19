package com.roubao.autopilot.agent

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.graphics.Bitmap
import com.roubao.autopilot.App
import com.roubao.autopilot.controller.AccessibilityBridge
import com.roubao.autopilot.controller.AdbBridge
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.ExecutionStep
import com.roubao.autopilot.data.SettingsManager
import com.roubao.autopilot.skills.SkillManager
import com.roubao.autopilot.ui.OverlayService
import com.roubao.autopilot.vlm.GUIOwlClient
import com.roubao.autopilot.vlm.MAIUIClient
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Mobile Agent 主循环 - 移植自 MobileAgent-v3
 *
 * 新增 Skill 层支持：
 * - 快速路径：高置信度 delegation Skill 直接执行
 * - 增强模式：GUI 自动化 Skill 提供上下文指导
 *
 * 支持三种模式：
 * - OpenAI 兼容模式：使用 VLMClient (Manager → Executor → Reflector)
 * - GUI-Owl 模式：使用 GUIOwlClient (直接返回操作指令)
 * - MAI-UI 模式：使用 MAIUIClient (专用 prompt 和对话历史)
 */
class MobileAgent(
    private val vlmClient: VLMClient?,
    private val controller: DeviceController,
    private val context: Context,
    private val guiOwlClient: GUIOwlClient? = null,  // GUI-Owl 专用客户端
    private val maiuiClient: MAIUIClient? = null     // MAI-UI 专用客户端
) {
    // 是否使用 GUI-Owl 模式
    private val useGUIOwlMode: Boolean = guiOwlClient != null
    // 是否使用 MAI-UI 模式
    private val useMAIUIMode: Boolean = maiuiClient != null
    // App 扫描器 (使用 App 单例中的实例)
    private val appScanner: AppScanner = App.getInstance().appScanner
    private val manager = Manager()
    private val executor = Executor()
    private val reflector = ActionReflector()
    private val notetaker = Notetaker()

    // Skill 管理器
    private val skillManager: SkillManager? = try {
        SkillManager.getInstance().also {
            println("[肉包] SkillManager 已加载，共 ${it.getAllSkills().size} 个 Skills")
            // 设置 VLM 客户端用于意图匹配（仅在 OpenAI 兼容模式下）
            vlmClient?.let { client -> it.setVLMClient(client) }
        }
    } catch (e: Exception) {
        println("[肉包] SkillManager 加载失败: ${e.message}")
        null
    }

    // 状态流
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    /**
     * 执行指令
     */
    suspend fun runInstruction(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        // 操控期间保持屏幕常亮：申请 WakeLock，避免自动熄屏打断任务（结束/取消都会释放）
        val wakeLock = try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "BengshaKalaka:AgentControl"
            )?.apply { acquire(30 * 60 * 1000L) }
        } catch (e: Exception) {
            println("[MobileAgent] WakeLock 申请失败: ${e.message}")
            null
        }
        try {
            return runInstructionInternal(instruction, maxSteps, useNotetaker)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                // 忽略释放异常
            }
        }
    }

    private suspend fun runInstructionInternal(
        instruction: String,
        maxSteps: Int = 25,
        useNotetaker: Boolean = false
    ): AgentResult {
        log("开始执行: $instruction")

        // 根据模式选择执行路径
        if (useGUIOwlMode && guiOwlClient != null) {
            log("使用 GUI-Owl 模式")
            return runInstructionWithGUIOwl(instruction, maxSteps)
        }

        if (useMAIUIMode && maiuiClient != null) {
            log("使用 MAI-UI 模式")
            return runInstructionWithMAIUI(instruction, maxSteps)
        }

        // OpenAI 兼容模式需要 VLMClient
        if (vlmClient == null) {
            log("错误: VLMClient 未初始化")
            return AgentResult(success = false, message = "VLMClient 未初始化")
        }

        log("使用 OpenAI 兼容模式")

        // 使用 LLM 匹配 Skill，生成上下文信息给 Agent（不执行任何操作）
        log("正在分析意图...")
        val skillContext = skillManager?.generateAgentContextWithLLM(instruction)

        val infoPool = InfoPool(instruction = instruction)

        // 读取用户自定义系统提示词（为空时使用默认提示词）
        val settingsManager = SettingsManager(context)
        val appSettings = settingsManager.settings.value
        val customSystemPrompt = appSettings.customSystemPrompt
        infoPool.customSystemPrompt = customSystemPrompt

        // 长期记忆攒到 8000 字时，用模型压缩：其中最新 10% 保留原文，其余压缩成摘要
        var effectiveMemory = appSettings.longTermMemory
        if (effectiveMemory.length >= 8000) {
            try {
                log("长期记忆已达 ${effectiveMemory.length} 字，正在用模型压缩…")
                val compressed = compressLongTermMemory(effectiveMemory)
                if (compressed != null) {
                    settingsManager.updateLongTermMemory(compressed)
                    effectiveMemory = compressed
                    log("长期记忆压缩完成，现为 ${compressed.length} 字")
                }
            } catch (e: Exception) {
                log("长期记忆压缩失败，使用原文: ${e.message}")
            }
        }
        infoPool.longTermMemory = effectiveMemory

        // 初始化 Executor 的对话记忆
        val executorSystemPrompt = buildString {
            if (customSystemPrompt.isNotBlank()) {
                append(customSystemPrompt.trim())
                append("\n\n")
            } else {
                append("You are an agent who can operate an Android phone. ")
                append("Decide the next action based on the current state.\n\n")
            }
            if (effectiveMemory.isNotBlank()) {
                append("### Long-term Memory ###\n")
                append("以下是你与用户在之前会话中的互动记忆，请保持角色、关系与记忆的连贯性：\n")
                append(effectiveMemory.trim())
                append("\n\n")
            }
            append("User Request: $instruction\n")
        }
        infoPool.executorMemory = ConversationMemory.withSystemPrompt(executorSystemPrompt)
        log("已初始化对话记忆")

        // 如果有 Skill 上下文，添加到 InfoPool，让 Manager 知道可用的工具
        if (!skillContext.isNullOrEmpty() && skillContext != "未找到相关技能或可用应用，请使用通用 GUI 自动化完成任务。") {
            infoPool.skillContext = skillContext
            log("已匹配到可用技能:\n$skillContext")
        } else {
            log("未匹配到特定技能，使用通用 GUI 自动化")
        }

        // 获取屏幕尺寸
        val (width, height) = controller.getScreenSize()
        infoPool.screenWidth = width
        infoPool.screenHeight = height
        log("屏幕尺寸: ${width}x${height}")

        // 获取已安装应用列表（只取非系统应用，限制数量避免 prompt 过长）
        val apps = appScanner.getApps()
            .filter { !it.isSystem }
            .take(50)
            .map { it.appName }
        infoPool.installedApps = apps.joinToString(", ")
        log("已加载 ${apps.size} 个应用")

        // 显示悬浮窗 (带停止按钮)
        OverlayService.show(context, "开始执行...") {
            // 停止回调 - 设置状态为停止
            // 注意：协程取消需要在 MainActivity 中处理
            updateState { copy(isRunning = false) }
            // 调用 stop() 方法确保清理
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        // 模型接口连续失败计数：连续 3 次失败就终止任务并说明原因，不再空转刷步数
        var consecutiveApiFailures = 0

        // 上一步的动作后截图：下一步直接复用，省去一次截图耗时
        var carriedScreenshot: DeviceController.ScreenshotResult? = null

        // ADB 模式：任务开始前先把无线调试连接建立好（配对过一次后自动发现端口重连）
        if (controller.isAdbMode()) {
            log("ADB 模式：正在连接本机 ADB…")
            val adbOk = withContext(Dispatchers.IO) { AdbBridge.connectNow(15000) }
            if (adbOk) {
                log("ADB 已连接")
            } else {
                log("⚠️ ADB 未连接：请先到 设置 → 控制方式 → ADB 里完成配对与连接（需打开无线调试）")
            }
        }

        try {
            for (step in 0 until maxSteps) {
                // 检查协程是否被取消
                coroutineContext.ensureActive()

                // 检查是否被用户停止
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                val stepT0 = System.currentTimeMillis()

                // 1. 截图：优先复用上一步的动作后截图（省一次截图耗时）；没有可复用的才重新截
                val carried = carriedScreenshot
                carriedScreenshot = null
                val screenshotResult: DeviceController.ScreenshotResult
                if (carried != null) {
                    log("复用上一步截图，跳过重复截图")
                    screenshotResult = carried
                } else {
                    log("截图中...")
                    OverlayService.setVisible(false)
                    delay(100) // 等待悬浮窗隐藏
                    screenshotResult = controller.screenshotWithFallback()
                    OverlayService.setVisible(true)
                }
                val screenshot = screenshotResult.bitmap

                // 坐标换算以模型实际看到的截图尺寸为准：无障碍上报的屏幕尺寸可能不含导航栏，
                // 与截图高度对不上时点击会整体偏移
                if (!screenshotResult.isFallback && screenshot.width > 0 && screenshot.height > 0 &&
                    (infoPool.screenWidth != screenshot.width || infoPool.screenHeight != screenshot.height)
                ) {
                    infoPool.screenWidth = screenshot.width
                    infoPool.screenHeight = screenshot.height
                }
                val stepT1 = System.currentTimeMillis()

                // 处理敏感页面（截图被系统阻止）
                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面（截图被阻止），请求人工接管")
                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm("检测到敏感页面，是否继续执行？")
                    }
                    if (!confirmed) {
                        log("用户取消，任务终止")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "敏感页面，用户取消")
                    }
                    log("用户确认继续（使用黑屏占位图）")
                } else if (screenshotResult.isFallback) {
                    log("⚠️ 截图失败，使用黑屏占位图继续")
                }

                // 再次检查停止状态（截图后）
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 1.5 元素树感知（借鉴 droidrun / dsh-go 的「无障碍树优先」）：
                // 无障碍已连接时抓取当前窗口的元素清单，执行器可按序号直达真实节点，
                // 点击不再依赖截图坐标换算；无元素（游戏/网页画布等）时自动回退到坐标方式。
                if (AccessibilityBridge.isConnected()) {
                    try {
                        val elements = AccessibilityBridge.snapshotElements()
                        infoPool.uiElements = if (elements.isEmpty()) {
                            ""
                        } else {
                            formatUiElements(elements, infoPool.screenWidth, infoPool.screenHeight)
                        }
                        if (elements.isNotEmpty()) {
                            log("元素树: ${elements.size} 个元素")
                        }
                    } catch (e: Exception) {
                        infoPool.uiElements = ""
                        log("元素树抓取失败: ${e.message}")
                    }
                } else {
                    infoPool.uiElements = ""
                }

                // 2. 检查错误升级
                checkErrorEscalation(infoPool)

                // 3. 跳过 Manager 的情况
                val skipManager = !infoPool.errorFlagPlan &&
                        infoPool.actionHistory.isNotEmpty() &&
                        infoPool.actionHistory.last().type == "invalid"

                // 4. Manager 规划
                if (!skipManager) {
                    log("Manager 规划中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val planPrompt = manager.getPrompt(infoPool)
                    val planResponse = vlmClient.predict(planPrompt, listOf(screenshot))

                    // VLM 调用后检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    if (planResponse.isFailure) {
                        consecutiveApiFailures++
                        val errMsg = planResponse.exceptionOrNull()?.message ?: "未知错误"
                        log("Manager 调用失败: $errMsg")
                        if (consecutiveApiFailures >= 3) {
                            log("模型接口连续 $consecutiveApiFailures 次调用失败，任务终止")
                            OverlayService.update("接口连续失败，已停止")
                            delay(1500)
                            OverlayService.hide(context)
                            updateState { copy(isRunning = false, isCompleted = false) }
                            bringAppToFront()
                            return AgentResult(success = false, message = "模型接口连续调用失败：$errMsg。请检查 API 地址、Key、额度，或稍后重试")
                        }
                        delay(1000)
                        continue
                    }
                    consecutiveApiFailures = 0

                    val planResult = manager.parseResponse(planResponse.getOrThrow())
                    infoPool.completedPlan = planResult.completedSubgoal
                    infoPool.plan = planResult.plan

                    log("计划: ${planResult.plan.take(100)}...")

                    // 检查是否遇到敏感页面
                    if (planResult.plan.contains("STOP_SENSITIVE")) {
                        log("检测到敏感页面（支付/密码等），已停止执行")
                        OverlayService.update("敏感页面，已停止")
                        delay(2000)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "检测到敏感页面（支付/密码），已安全停止")
                    }

                    // 检查是否完成
                    if (planResult.plan.contains("Finished") && planResult.plan.length < 20) {
                        log("任务完成!")
                        OverlayService.update("完成!")
                        delay(1500)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = true) }
                        bringAppToFront()
                        return AgentResult(success = true, message = "任务完成")
                    }
                }

                val stepT2 = System.currentTimeMillis()

                // 5. Executor 决定动作 (使用上下文记忆)
                log("Executor 决策中...")

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val actionPrompt = executor.getPrompt(infoPool)

                // 使用上下文记忆调用 VLM
                val memory = infoPool.executorMemory
                val actionResponse = if (memory != null) {
                    // 添加用户消息（带截图）
                    memory.addUserMessage(actionPrompt, screenshot)
                    log("记忆消息数: ${memory.size()}, 估算 token: ${memory.estimateTokens()}")

                    // 调用 VLM
                    val response = vlmClient.predictWithContext(memory.toMessagesJson())

                    // 删除图片节省 token
                    memory.stripLastUserImage()

                    response
                } else {
                    // 降级：使用普通方式
                    vlmClient.predict(actionPrompt, listOf(screenshot))
                }

                // VLM 调用后检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                if (actionResponse.isFailure) {
                    consecutiveApiFailures++
                    val errMsg = actionResponse.exceptionOrNull()?.message ?: "未知错误"
                    log("Executor 调用失败: $errMsg")
                    if (consecutiveApiFailures >= 3) {
                        log("模型接口连续 $consecutiveApiFailures 次调用失败，任务终止")
                        OverlayService.update("接口连续失败，已停止")
                        delay(1500)
                        OverlayService.hide(context)
                        updateState { copy(isRunning = false, isCompleted = false) }
                        bringAppToFront()
                        return AgentResult(success = false, message = "模型接口连续调用失败：$errMsg。请检查 API 地址、Key、额度，或稍后重试")
                    }
                    delay(1000)
                    continue
                }
                consecutiveApiFailures = 0

                val responseText = actionResponse.getOrThrow()
                val executorResult = executor.parseResponse(responseText)

                // 将助手响应添加到记忆
                memory?.addAssistantMessage(responseText)
                val action = executorResult.action
                val stepT3 = System.currentTimeMillis()

                log("思考: ${executorResult.thought.take(80)}...")
                log("动作: ${executorResult.actionStr}")
                log("描述: ${executorResult.description}")

                infoPool.lastActionThought = executorResult.thought
                infoPool.lastSummary = executorResult.description

                if (action == null) {
                    log("动作解析失败")
                    infoPool.actionHistory.add(Action(type = "invalid"))
                    infoPool.summaryHistory.add(executorResult.description)
                    infoPool.actionOutcomes.add("C")
                    infoPool.errorDescriptions.add("Invalid action format")
                    continue
                }

                // 特殊处理: answer 动作
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("${action.text?.take(20)}...")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true, answer = action.text) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 特殊处理: terminate 动作 (MAI-UI)
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log("任务${if (success) "完成" else "失败"}")
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 6. 敏感操作确认
                if (action.needConfirm || action.message != null && action.type in listOf("click", "double_tap", "long_press")) {
                    val confirmMessage = action.message ?: "确认执行此操作？"
                    log("⚠️ 敏感操作: $confirmMessage")

                    val confirmed = withContext(Dispatchers.Main) {
                        waitForUserConfirm(confirmMessage)
                    }

                    if (!confirmed) {
                        log("❌ 用户取消操作")
                        infoPool.actionHistory.add(action)
                        infoPool.summaryHistory.add("用户取消: ${executorResult.description}")
                        infoPool.actionOutcomes.add("C")
                        infoPool.errorDescriptions.add("User cancelled")
                        continue
                    }
                    log("✅ 用户确认，继续执行")
                }

                // 7. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}: ${executorResult.description.take(15)}...")
                executeAction(action, infoPool)
                infoPool.lastAction = action

                // 立即记录执行步骤（outcome 暂时为 "?" 表示进行中）
                val currentStepIndex = _state.value.executionSteps.size
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = executorResult.description,
                    thought = executorResult.thought,
                    outcome = "?" // 进行中
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 等待动作生效（界面通常几百毫秒内稳定，过长等待是单步慢的主因之一）
                delay(if (step == 0) 1500 else 550)

                // 检查停止状态
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                // 8. 截图 (动作后，隐藏悬浮窗)
                OverlayService.setVisible(false)
                delay(100)
                val afterScreenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val afterScreenshot = afterScreenshotResult.bitmap
                if (afterScreenshotResult.isFallback) {
                    log("动作后截图失败，使用黑屏占位图")
                }

                // 9. 本地校验动作效果（替代原来每步一次的 Reflector 模型调用，省一次 API 往返）：
                //    对比动作前后截图，点完画面完全没变基本就是没点中或页面未响应
                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                val (outcome, outcomeError) = verifyActionEffect(
                    action, screenshot, afterScreenshot, afterScreenshotResult.isFallback
                )
                log("结果: $outcome${if (outcomeError.isNotEmpty()) " - $outcomeError" else ""}")

                // 更新历史
                infoPool.actionHistory.add(action)
                infoPool.summaryHistory.add(executorResult.description)
                infoPool.actionOutcomes.add(outcome)
                infoPool.errorDescriptions.add(outcomeError)
                infoPool.progressStatus = infoPool.completedPlan

                // 更新执行步骤的 outcome（之前添加的步骤 outcome 是 "?"）
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (currentStepIndex < updatedSteps.size) {
                        updatedSteps[currentStepIndex] = updatedSteps[currentStepIndex].copy(
                            outcome = outcome
                        )
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 本步耗时分布：定位慢在哪一段（截图 / 规划 / 决策 / 执行校验）
                val stepEndMs = System.currentTimeMillis()
                log("⏱ 本步耗时: 截图 ${stepT1 - stepT0}ms, 规划 ${stepT2 - stepT1}ms, 决策 ${stepT3 - stepT2}ms, 执行+校验 ${stepEndMs - stepT3}ms")

                // 动作后截图留给下一步复用（失败占位图不复用）
                if (!afterScreenshotResult.isFallback && !afterScreenshotResult.isSensitive) {
                    carriedScreenshot = afterScreenshotResult
                }

                // 10. Notetaker (可选)
                if (useNotetaker && outcome == "A" && action.type != "answer") {
                    log("Notetaker 记录中...")

                    // 检查停止状态
                    if (!_state.value.isRunning) {
                        log("用户停止执行")
                        OverlayService.hide(context)
                        bringAppToFront()
                        return AgentResult(success = false, message = "用户停止")
                    }

                    val notePrompt = notetaker.getPrompt(infoPool)
                    val noteResponse = vlmClient.predict(notePrompt, listOf(afterScreenshot))
                    if (noteResponse.isSuccess) {
                        infoPool.importantNotes = notetaker.parseResponse(noteResponse.getOrThrow())
                    }
                }
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 用模型压缩长期记忆：最新的约 10% 保留原文，其余部分（约 90%）总结成摘要。
     * 压缩失败返回 null，调用方回退到原文。
     */
    private suspend fun compressLongTermMemory(memory: String): String? {
        // 保留最新约 10%：从 90% 处往后找第一个完整的【任务】条目边界
        val keepTarget = memory.length - (memory.length * 0.10).toInt()
        var splitIdx = memory.indexOf("【任务】", keepTarget)
        if (splitIdx < 0) {
            splitIdx = keepTarget
        }
        val oldPart = memory.substring(0, splitIdx).trim()
        val recentPart = memory.substring(splitIdx).trim()
        if (oldPart.isBlank()) {
            return null
        }

        val prompt = buildString {
            append("你是一个记忆压缩助手。请把下面这段「用户与角色的互动记忆」压缩成简洁的摘要，要求：\n")
            append("1) 保留用户的偏好、习惯与重要信息；\n")
            append("2) 保留角色与用户的关系进展和共同经历的关键事件；\n")
            append("3) 保留未完成的话题、约定与承诺；\n")
            append("4) 删除重复和琐碎的操作细节；\n")
            append("5) 用条目式短句输出，总长度不超过原文的三分之一。只输出摘要正文，不要解释。\n\n")
            append("原文：\n")
            append(oldPart)
        }

        val client = vlmClient ?: return null
        val result = client.predict(prompt)
        val digest = result.getOrNull()?.trim()
        if (digest.isNullOrBlank() || digest.length >= oldPart.length) {
            return null
        }

        return buildString {
            append("【记忆摘要（较早的互动，已由模型压缩）】\n")
            append(digest)
            if (recentPart.isNotBlank()) {
                append("\n\n")
                append(recentPart)
            }
        }
    }

    /**
     * GUI-Owl 模式执行指令
     * 简化流程：截图 → GUI-Owl API → 解析操作 → 执行
     */
    private suspend fun runInstructionWithGUIOwl(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = guiOwlClient ?: return AgentResult(false, "GUIOwlClient 未初始化")

        // 重置会话
        client.resetSession()
        log("GUI-Owl 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 显示悬浮窗
        OverlayService.show(context, "GUI-Owl 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (GUI-Owl) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 GUI-Owl API
                log("调用 GUI-Owl API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("GUI-Owl 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thought.take(100)}...")
                log("操作: ${result.operation}")
                log("说明: ${result.explanation}")

                // 3. 解析操作指令
                val parsedAction = client.parseOperation(result.operation)
                if (parsedAction == null) {
                    log("无法解析操作: ${result.operation}")
                    continue
                }

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = parsedAction.type,
                    description = result.explanation,
                    thought = result.thought,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (parsedAction.type == "finish") {
                    log("任务完成!")
                    OverlayService.update("完成!")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "任务完成")
                }

                // 4. 执行动作
                log("执行动作: ${parsedAction.type}")
                OverlayService.update("${parsedAction.type}: ${result.explanation.take(15)}...")
                executeGUIOwlAction(parsedAction, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 1500 else 700)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 GUI-Owl 解析的动作
     */
    private suspend fun executeGUIOwlAction(
        action: GUIOwlClient.ParsedAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        when (action.type) {
            "click" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("点击: ($x, $y)")
                controller.tap(x, y)
            }
            "swipe" -> {
                val x1 = action.x ?: return@withContext
                val y1 = action.y ?: return@withContext
                val x2 = action.x2 ?: return@withContext
                val y2 = action.y2 ?: return@withContext
                log("滑动: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2)
            }
            "long_press" -> {
                val x = action.x ?: return@withContext
                val y = action.y ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "scroll" -> {
                val direction = action.text ?: "down"
                val centerX = screenWidth / 2
                val centerY = screenHeight / 2
                log("滚动: $direction")
                when (direction) {
                    "up" -> controller.swipe(centerX, centerY + 300, centerX, centerY - 300)
                    "down" -> controller.swipe(centerX, centerY - 300, centerX, centerY + 300)
                    "left" -> controller.swipe(centerX + 300, centerY, centerX - 300, centerY)
                    "right" -> controller.swipe(centerX - 300, centerY, centerX + 300, centerY)
                }
            }
            "system_button" -> {
                when (action.text?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                }
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * MAI-UI 模式执行指令
     * 使用专用的 MAI-UI prompt 和对话历史管理
     */
    private suspend fun runInstructionWithMAIUI(
        instruction: String,
        maxSteps: Int
    ): AgentResult {
        val client = maiuiClient ?: return AgentResult(false, "MAIUIClient 未初始化")

        // 重置会话
        client.reset()
        log("MAI-UI 会话已重置")

        // 获取屏幕尺寸
        val (screenWidth, screenHeight) = controller.getScreenSize()
        log("屏幕尺寸: ${screenWidth}x${screenHeight}")

        // 设置可用应用列表
        val installedApps = appScanner.getApps().map { it.appName }
        client.setAvailableApps(installedApps)
        log("已加载 ${installedApps.size} 个应用")

        // 显示悬浮窗
        OverlayService.show(context, "MAI-UI 模式") {
            updateState { copy(isRunning = false) }
            stop()
        }

        updateState { copy(isRunning = true, currentStep = 0, instruction = instruction) }

        try {
            for (step in 0 until maxSteps) {
                coroutineContext.ensureActive()

                if (!_state.value.isRunning) {
                    log("用户停止执行")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "用户停止")
                }

                updateState { copy(currentStep = step + 1) }
                log("\n========== Step ${step + 1} (MAI-UI) ==========")
                OverlayService.update("Step ${step + 1}/$maxSteps")

                // 1. 截图
                log("截图中...")
                OverlayService.setVisible(false)
                delay(100)
                val screenshotResult = controller.screenshotWithFallback()
                OverlayService.setVisible(true)
                val screenshot = screenshotResult.bitmap

                if (screenshotResult.isSensitive) {
                    log("⚠️ 检测到敏感页面")
                    OverlayService.hide(context)
                    bringAppToFront()
                    return AgentResult(success = false, message = "敏感页面，已停止")
                }

                // 2. 调用 MAI-UI API
                log("调用 MAI-UI API...")
                val response = client.predict(instruction, screenshot)

                if (response.isFailure) {
                    log("MAI-UI 调用失败: ${response.exceptionOrNull()?.message}")
                    continue
                }

                val result = response.getOrThrow()
                log("思考: ${result.thinking.take(150)}...")

                val action = result.action
                if (action == null) {
                    log("无法解析动作")
                    continue
                }

                log("动作: ${action.type}")

                // 记录执行步骤
                val executionStep = ExecutionStep(
                    stepNumber = step + 1,
                    timestamp = System.currentTimeMillis(),
                    action = action.type,
                    description = result.thinking.take(50),
                    thought = result.thinking,
                    outcome = "?"
                )
                updateState { copy(executionSteps = executionSteps + executionStep) }

                // 检查是否完成
                if (action.type == "terminate") {
                    val success = action.status == "success"
                    log(if (success) "任务完成!" else "任务失败")
                    OverlayService.update(if (success) "完成!" else "失败")
                    delay(1500)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = success) }
                    bringAppToFront()
                    return AgentResult(success = success, message = if (success) "任务完成" else "任务失败")
                }

                // 检查是否需要人工接管
                if (action.type == "ask_user") {
                    log("请求用户介入: ${action.text}")
                    OverlayService.update("请手动操作: ${action.text?.take(20)}")
                    // 等待用户操作后继续
                    delay(5000)
                    continue
                }

                // 检查是否是回答
                if (action.type == "answer") {
                    log("回答: ${action.text}")
                    OverlayService.update("答案: ${action.text?.take(30)}")
                    delay(3000)
                    OverlayService.hide(context)
                    updateState { copy(isRunning = false, isCompleted = true) }
                    bringAppToFront()
                    return AgentResult(success = true, message = "回答: ${action.text}")
                }

                // 3. 执行动作
                log("执行动作: ${action.type}")
                OverlayService.update("${action.type}...")
                executeMAIUIAction(action, screenWidth, screenHeight)

                // 更新步骤状态
                updateState {
                    val updatedSteps = executionSteps.toMutableList()
                    if (step < updatedSteps.size) {
                        updatedSteps[step] = updatedSteps[step].copy(outcome = "A")
                    }
                    copy(executionSteps = updatedSteps)
                }

                // 等待动作生效
                delay(if (step == 0) 1200 else 700)
            }
        } catch (e: CancellationException) {
            log("任务被取消")
            OverlayService.hide(context)
            updateState { copy(isRunning = false) }
            bringAppToFront()
            throw e
        }

        log("达到最大步数限制")
        OverlayService.update("达到最大步数")
        delay(1500)
        OverlayService.hide(context)
        updateState { copy(isRunning = false, isCompleted = false) }
        bringAppToFront()
        return AgentResult(success = false, message = "达到最大步数限制")
    }

    /**
     * 执行 MAI-UI 解析的动作
     */
    private suspend fun executeMAIUIAction(
        action: com.roubao.autopilot.vlm.MAIUIAction,
        screenWidth: Int,
        screenHeight: Int
    ) = withContext(Dispatchers.IO) {
        // 转换归一化坐标到屏幕像素
        val screenAction = action.toScreenCoordinates(screenWidth, screenHeight)

        when (action.type) {
            "click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("点击: ($x, $y)")
                controller.tap(x, y)
            }
            "long_press" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("长按: ($x, $y)")
                controller.longPress(x, y)
            }
            "double_click" -> {
                val x = screenAction.x?.toInt() ?: return@withContext
                val y = screenAction.y?.toInt() ?: return@withContext
                log("双击: ($x, $y)")
                controller.tap(x, y)
                delay(100)
                controller.tap(x, y)
            }
            "type" -> {
                val text = action.text ?: return@withContext
                log("输入: $text")
                controller.type(text)
            }
            "swipe" -> {
                // 支持方向式滑动和坐标式滑动
                if (action.direction != null) {
                    val centerX = screenWidth / 2
                    val centerY = screenHeight / 2
                    val distance = screenHeight / 3
                    log("滑动: ${action.direction}")
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                    }
                } else if (screenAction.x != null && screenAction.y != null) {
                    // 从指定位置滑动
                    val startX = screenAction.x.toInt()
                    val startY = screenAction.y.toInt()
                    val distance = screenHeight / 3
                    val direction = action.direction ?: "up"
                    when (direction.lowercase()) {
                        "up" -> controller.swipe(startX, startY, startX, startY - distance)
                        "down" -> controller.swipe(startX, startY, startX, startY + distance)
                        "left" -> controller.swipe(startX, startY, startX - distance, startY)
                        "right" -> controller.swipe(startX, startY, startX + distance, startY)
                    }
                }
            }
            "drag" -> {
                val x1 = screenAction.startX?.toInt() ?: return@withContext
                val y1 = screenAction.startY?.toInt() ?: return@withContext
                val x2 = screenAction.endX?.toInt() ?: return@withContext
                val y2 = screenAction.endY?.toInt() ?: return@withContext
                log("拖拽: ($x1, $y1) -> ($x2, $y2)")
                controller.swipe(x1, y1, x2, y2, 500)
            }
            "open" -> {
                val appName = action.text ?: return@withContext
                log("打开应用: $appName")
                controller.openApp(appName)
            }
            "system_button" -> {
                when (action.button?.lowercase()) {
                    "back" -> {
                        log("按返回键")
                        controller.back()
                    }
                    "home" -> {
                        log("按 Home 键")
                        controller.home()
                    }
                    "menu" -> {
                        log("按菜单键")
                        // 通过 keyevent 模拟菜单键
                        controller.back()  // 大多数场景用返回代替
                    }
                    "enter" -> {
                        log("按回车键")
                        controller.enter()
                    }
                }
            }
            "wait" -> {
                log("等待...")
                delay(2000)
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 执行具体动作 (在 IO 线程执行，避免 ANR)
     */
    private suspend fun executeAction(action: Action, infoPool: InfoPool) = withContext(Dispatchers.IO) {
        // 优先用截图的实际尺寸（与模型看到的画面一致）；没有时再动态获取屏幕尺寸（处理横竖屏切换）
        val (screenWidth, screenHeight) = if (infoPool.screenWidth > 0 && infoPool.screenHeight > 0) {
            Pair(infoPool.screenWidth, infoPool.screenHeight)
        } else {
            controller.getScreenSize()
        }
        // 模型看到的截图尺寸（发送前缩小到 1280 以内）：用于识别模型按像素坐标输出的情况
        val (modelWidth, modelHeight) = modelImageSize(screenWidth, screenHeight)

        when (action.type) {
            "click" -> {
                // 元素序号优先：对无障碍节点直接执行点击，绕开坐标换算，不可能点偏
                val idx = action.index
                if (idx != null && AccessibilityBridge.clickElement(idx)) {
                    log("元素点击 #$idx 成功（节点直达）")
                } else {
                    if (idx != null) log("元素点击 #$idx 未成功，回退坐标点击")
                    val x = mapPoint(action.x ?: 0, screenWidth, modelWidth)
                    val y = mapPoint(action.y ?: 0, screenHeight, modelHeight)
                    controller.tap(x, y)
                }
            }
            "double_tap" -> {
                val idx = action.index
                if (idx != null && AccessibilityBridge.doubleTapElement(idx)) {
                    log("元素双击 #$idx 成功（节点直达）")
                } else {
                    if (idx != null) log("元素双击 #$idx 未成功，回退坐标双击")
                    val x = mapPoint(action.x ?: 0, screenWidth, modelWidth)
                    val y = mapPoint(action.y ?: 0, screenHeight, modelHeight)
                    controller.doubleTap(x, y)
                }
            }
            "long_press" -> {
                val idx = action.index
                if (idx != null && AccessibilityBridge.longClickElement(idx)) {
                    log("元素长按 #$idx 成功（节点直达）")
                } else {
                    if (idx != null) log("元素长按 #$idx 未成功，回退坐标长按")
                    val x = mapPoint(action.x ?: 0, screenWidth, modelWidth)
                    val y = mapPoint(action.y ?: 0, screenHeight, modelHeight)
                    controller.longPress(x, y)
                }
            }
            "swipe" -> {
                // 支持两种 swipe 方式:
                // 1. 坐标方式: coordinate + coordinate2
                // 2. 方向方式: direction (up/down/left/right) + optional coordinate
                if (action.direction != null) {
                    // 方向方式 (MAI-UI 格式)
                    val centerX = action.x?.let { mapPoint(it, screenWidth, modelWidth) } ?: (screenWidth / 2)
                    val centerY = action.y?.let { mapPoint(it, screenHeight, modelHeight) } ?: (screenHeight / 2)
                    val distance = 400  // 滑动距离
                    when (action.direction.lowercase()) {
                        "up" -> controller.swipe(centerX, centerY + distance, centerX, centerY - distance)
                        "down" -> controller.swipe(centerX, centerY - distance, centerX, centerY + distance)
                        "left" -> controller.swipe(centerX + distance, centerY, centerX - distance, centerY)
                        "right" -> controller.swipe(centerX - distance, centerY, centerX + distance, centerY)
                        else -> log("未知滑动方向: ${action.direction}")
                    }
                } else {
                    // 坐标方式
                    val x1 = mapPoint(action.x ?: 0, screenWidth, modelWidth)
                    val y1 = mapPoint(action.y ?: 0, screenHeight, modelHeight)
                    val x2 = mapPoint(action.x2 ?: 0, screenWidth, modelWidth)
                    val y2 = mapPoint(action.y2 ?: 0, screenHeight, modelHeight)
                    controller.swipe(x1, y1, x2, y2)
                }
            }
            "type" -> {
                // 带序号时直接给目标输入框设置文本（不依赖键盘弹出与剪贴板），失败再走原输入路径
                val idx = action.index
                val text = action.text
                if (idx != null && text != null && AccessibilityBridge.setTextOnElement(idx, text)) {
                    log("元素输入 #$idx 成功（节点直达）")
                } else {
                    if (idx != null) log("元素输入 #$idx 未成功，回退常规输入")
                    text?.let { controller.type(it) }
                }
            }
            "system_button" -> {
                when (action.button) {
                    "Back", "back" -> controller.back()
                    "Home", "home" -> controller.home()
                    "Enter", "enter" -> controller.enter()
                    else -> log("未知系统按钮: ${action.button}")
                }
            }
            "open_app" -> {
                action.text?.let { appName ->
                    // 智能匹配包名 (客户端模糊搜索，省 token)
                    val packageName = appScanner.findPackage(appName)
                    if (packageName != null) {
                        log("找到应用: $appName -> $packageName")
                        controller.openApp(packageName)
                    } else {
                        log("未找到应用: $appName，尝试直接打开")
                        controller.openApp(appName)
                    }
                }
            }
            "wait" -> {
                // 智能等待：模型决定等待时长
                val duration = (action.duration ?: 3).coerceIn(1, 10)
                log("等待 ${duration} 秒...")
                delay(duration * 1000L)
            }
            "take_over" -> {
                // 人机协作：暂停等待用户手动完成操作
                val message = action.message ?: "请完成操作后点击继续"
                log("🖐 人机协作: $message")
                withContext(Dispatchers.Main) {
                    waitForUserTakeOver(message)
                }
                log("✅ 用户已完成，继续执行")
            }
            else -> {
                log("未知动作类型: ${action.type}")
            }
        }
    }

    /**
     * 等待用户完成手动操作（人机协作）
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserTakeOver(message: String) = suspendCancellableCoroutine<Unit> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showTakeOver(message) {
            if (continuation.isActive) {
                continuation.resume(Unit) {}
            }
        }
    }

    /**
     * 等待用户确认敏感操作
     * @return true = 用户确认，false = 用户取消
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun waitForUserConfirm(message: String) = suspendCancellableCoroutine<Boolean> { continuation ->
        com.roubao.autopilot.ui.OverlayService.showConfirm(message) { confirmed ->
            if (continuation.isActive) {
                continuation.resume(confirmed) {}
            }
        }
    }

    /**
     * 把元素树清单格式化成提示词文本：序号、标签、类型、可交互标记，
     * 以及元素中心在 0-1000 相对坐标中的位置（与截图坐标系一致，方便模型对照画面）。
     */
    private fun formatUiElements(
        elements: List<com.roubao.autopilot.service.UiElementInfo>,
        screenWidth: Int,
        screenHeight: Int
    ): String {
        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        return elements.joinToString("\n") { el ->
            val cx = el.bounds.centerX() * 1000 / w
            val cy = el.bounds.centerY() * 1000 / h
            val flags = buildList {
                if (el.clickable) add("clickable")
                if (el.longClickable) add("long-clickable")
                if (el.editable) add("editable")
                if (el.scrollable) add("scrollable")
            }.joinToString(" ")
            val label = el.label.replace("\n", " ").take(40)
            "[$cx,$cy]".let { pos ->
                "[${el.index}] \"$label\" ${el.className} $flags @$pos"
            }
        }
    }

    /**
     * 模型实际看到的截图尺寸：截图发送前会被等比缩小到 1280 以内
     * （与 VLMClient.resizeBitmap 同一规则），由此推算模型视角的图像宽高。
     */
    private fun modelImageSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 1280 && height <= 1280) {
            return Pair(width, height)
        }
        val ratio = minOf(1280f / width, 1280f / height)
        return Pair(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1)
        )
    }

    /**
     * 坐标映射 - 兼容两种模型输出约定（提示词约定的是第一种，第二种是兜底）：
     * - 0~1000：相对坐标，按比例换算到屏幕像素（1000 = 屏幕边缘）
     * - >1000：模型按「它看到的截图」的像素坐标输出，按截图尺寸换算到屏幕像素
     *
     * @param value 模型输出的坐标值
     * @param screenMax 屏幕实际尺寸（该轴）
     * @param modelMax 模型看到的截图尺寸（该轴）
     */
    private fun mapPoint(value: Int, screenMax: Int, modelMax: Int): Int {
        val px = if (value > 1000 && modelMax > 0) {
            value * screenMax / modelMax
        } else {
            value.coerceIn(0, 1000) * screenMax / 1000
        }
        return px.coerceIn(0, (screenMax - 1).coerceAtLeast(0))
    }

    /**
     * 本地校验动作是否生效：把动作前后的截图缩到 32x32 灰度图算平均差异。
     * 点击类动作执行后画面完全不变（差异极小），基本可判定没点中或页面未响应。
     * 返回 (outcome, errorDescription)：A = 已生效，B = 疑似未生效。
     * 用本地图像对比替代原来每步一次的 Reflector 模型调用，省掉一次 API 往返。
     */
    private fun verifyActionEffect(
        action: Action,
        before: Bitmap,
        after: Bitmap,
        afterIsFallback: Boolean
    ): Pair<String, String> {
        if (afterIsFallback) {
            return Pair("A", "")
        }
        return when (action.type) {
            "click", "double_tap", "long_press", "swipe" -> {
                val diff = screenDiff(before, after)
                if (diff != null && diff < 0.5) {
                    Pair("B", "动作后屏幕几乎没有变化（差异 %.2f），可能没有点中或页面未响应".format(diff))
                } else {
                    Pair("A", "")
                }
            }
            else -> Pair("A", "")
        }
    }

    private fun screenDiff(a: Bitmap, b: Bitmap): Double? {
        return try {
            val size = 32
            val pa = IntArray(size * size)
            val pb = IntArray(size * size)
            Bitmap.createScaledBitmap(a, size, size, true).getPixels(pa, 0, size, 0, 0, size, size)
            Bitmap.createScaledBitmap(b, size, size, true).getPixels(pb, 0, size, 0, 0, size, size)
            var sum = 0.0
            for (i in pa.indices) {
                val ga = ((pa[i] shr 16 and 0xFF) + (pa[i] shr 8 and 0xFF) + (pa[i] and 0xFF)) / 3
                val gb = ((pb[i] shr 16 and 0xFF) + (pb[i] shr 8 and 0xFF) + (pb[i] and 0xFF)) / 3
                sum += kotlin.math.abs(ga - gb)
            }
            sum / pa.size
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查错误升级
     */
    private fun checkErrorEscalation(infoPool: InfoPool) {
        infoPool.errorFlagPlan = false
        val thresh = infoPool.errToManagerThresh

        if (infoPool.actionOutcomes.size >= thresh) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(thresh)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount == thresh) {
                infoPool.errorFlagPlan = true
            }
        }
    }

    // 停止回调（由 MainActivity 设置，用于取消协程）
    var onStopRequested: (() -> Unit)? = null

    /**
     * 停止执行
     */
    fun stop() {
        OverlayService.hide(context)
        updateState { copy(isRunning = false) }
        // 通知 MainActivity 取消协程
        onStopRequested?.invoke()
    }

    /**
     * 清空日志
     */
    fun clearLogs() {
        _logs.value = emptyList()
        updateState { copy(executionSteps = emptyList()) }
    }

    /**
     * 返回肉包App
     */
    private fun bringAppToFront() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        } catch (e: Exception) {
            log("返回App失败: ${e.message}")
        }
    }

    private fun log(message: String) {
        println("[肉包] $message")
        _logs.value = _logs.value + message
    }

    private fun updateState(update: AgentState.() -> AgentState) {
        _state.value = _state.value.update()
    }

}

data class AgentState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val currentStep: Int = 0,
    val instruction: String = "",
    val answer: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList()
)

data class AgentResult(
    val success: Boolean,
    val message: String
)
