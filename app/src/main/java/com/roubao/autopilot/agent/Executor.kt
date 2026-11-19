package com.roubao.autopilot.agent

/**
 * Executor Agent - 决定具体执行什么动作
 */
class Executor {

    companion object {
        val GUIDELINES = """
General:
- For any pop-up window, close it (e.g., by clicking 'Don't Allow' or 'Accept') before proceeding.
- For requests that are questions, remember to use the `answer` action to reply before finish!
- If the desired state is already achieved, you can just complete the task.

Action Related:
- Use `open_app` to open an app, do not use the app drawer.
- Consider using `swipe` to reveal additional content.
- If swiping doesn't change the page, it may have reached the bottom.

Text Related:
- To input text: first click the input box, make sure keyboard is visible, then use `type` action.
- To clear text: long press the backspace button in the keyboard.
        """.trimIndent()
    }

    /**
     * 生成执行 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent who can operate an Android phone. ")
        append("Decide the next action based on the current state.\n\n")

        if (infoPool.customSystemPrompt.isNotEmpty()) {
            append("### System Prompt ###\n")
            append("${infoPool.customSystemPrompt}\n\n")
        }

        if (infoPool.longTermMemory.isNotEmpty()) {
            append("### Long-term Memory ###\n")
            append("以下是你与用户在之前会话中的互动记忆，请保持角色与记忆的连贯性：\n")
            append("${infoPool.longTermMemory}\n\n")
        }

        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Overall Plan ###\n")
        append("${infoPool.plan}\n\n")

        append("### Current Subgoal ###\n")
        val subgoals = infoPool.plan.split(Regex("(?<=\\d)\\. ")).take(3)
        append("${subgoals.joinToString(". ")}\n\n")

        append("### Progress Status ###\n")
        if (infoPool.progressStatus.isNotEmpty()) {
            append("${infoPool.progressStatus}\n\n")
        } else {
            append("No progress yet.\n\n")
        }

        append("### Guidelines ###\n")
        append("$GUIDELINES\n\n")

        append("---\n")
        append("Examine all information and decide on the next action.\n\n")

        append("#### Coordinate System (IMPORTANT) ####\n")
        append("All coordinates MUST be integers in [0, 1000]. (0, 0) is the TOP-LEFT corner of the screenshot, (1000, 1000) is the BOTTOM-RIGHT corner. ")
        append("x = 1000 * targetCenterX / imageWidth, y = 1000 * targetCenterY / imageHeight. ")
        append("Always aim at the CENTER of the target element. NEVER output raw pixel coordinates of the image.\n\n")

        append("#### Atomic Actions ####\n")
        append("- click(coordinate): Click at (x, y). Example: {\"action\": \"click\", \"coordinate\": [x, y]}\n")
        append("- double_tap(coordinate): Double tap at (x, y) for zoom or like. Example: {\"action\": \"double_tap\", \"coordinate\": [x, y]}\n")
        append("- long_press(coordinate): Long press at (x, y). Example: {\"action\": \"long_press\", \"coordinate\": [x, y]}\n")
        append("- type(text): Type text into activated input box. Example: {\"action\": \"type\", \"text\": \"hello\"}\n")
        append("- swipe(coordinate, coordinate2): Swipe from point1 to point2. Example: {\"action\": \"swipe\", \"coordinate\": [x1, y1], \"coordinate2\": [x2, y2]}\n")
        append("- system_button(button): Press Back/Home/Enter. Example: {\"action\": \"system_button\", \"button\": \"Back\"}\n")
        append("- open_app(text): Open an app by name. ALWAYS use this instead of looking for app icons on screen! Example: {\"action\": \"open_app\", \"text\": \"设置\"}\n")
        if (infoPool.installedApps.isNotEmpty()) {
            append("  Available apps: ${infoPool.installedApps}\n")
        }
        append("- wait(duration): Wait for page loading. Duration in seconds (1-10). Example: {\"action\": \"wait\", \"duration\": 3}\n")
        append("- take_over(message): Request user to manually complete login/captcha/verification. Example: {\"action\": \"take_over\", \"message\": \"请完成登录验证\"}\n")
        append("- answer(text): Answer user's question. Example: {\"action\": \"answer\", \"text\": \"The answer is...\"}\n")
        append("\n")

        append("#### Sensitive Operations ####\n")
        append("For payment, password, or privacy-related actions, add 'message' field to request user confirmation:\n")
        append("Example: {\"action\": \"click\", \"coordinate\": [500, 800], \"message\": \"确认支付 ¥100\"}\n")
        append("The user will see a confirmation dialog and can choose to confirm or cancel.\n")
        append("\n")

        if (infoPool.uiElements.isNotEmpty()) {
            append("#### UI Elements (accessibility tree — the REAL elements currently on screen) ####\n")
            append("The list below is extracted from the system's accessibility tree. The @ position is each element's center in the same 0-1000 coordinates as the screenshot. Indices are valid ONLY for this step's list — the list is refreshed every step.\n")
            append("${infoPool.uiElements}\n\n")
            append("STRONGLY PREFER index actions for listed elements — they act on the exact element itself and cannot miss:\n")
            append("- Click by index: {\"action\": \"click\", \"index\": 12}\n")
            append("- Long press by index: {\"action\": \"long_press\", \"index\": 12}\n")
            append("- Double tap by index: {\"action\": \"double_tap\", \"index\": 12}\n")
            append("- Type directly into a listed input field (no need to click it first): {\"action\": \"type\", \"index\": 5, \"text\": \"...\"}\n")
            append("Use coordinate actions ONLY for targets that are NOT in the list (images, game/canvas areas, web content without accessibility elements).\n")
            append("\n")
        }

        append("### Latest Action History ###\n")
        if (infoPool.actionHistory.isNotEmpty()) {
            val numActions = minOf(5, infoPool.actionHistory.size)
            val latestActions = infoPool.actionHistory.takeLast(numActions)
            val latestSummaries = infoPool.summaryHistory.takeLast(numActions)
            val latestOutcomes = infoPool.actionOutcomes.takeLast(numActions)
            val latestErrors = infoPool.errorDescriptions.takeLast(numActions)

            latestActions.forEachIndexed { i, act ->
                val outcome = latestOutcomes.getOrNull(i) ?: "?"
                if (outcome == "A") {
                    append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Successful\n")
                } else {
                    append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Failed | Error: ${latestErrors.getOrNull(i)}\n")
                }
            }
        } else {
            append("No actions have been taken yet.\n")
        }
        append("\n")

        append("---\n")
        append("IMPORTANT:\n")
        append("1. Do NOT repeat previously failed actions. Try a different approach.\n")
        append("2. Prioritize the current subgoal.\n")
        append("3. Always analyze the screen BEFORE deciding on an action.\n\n")

        append("Provide your output in the following format:\n\n")
        append("### Thought ###\n")
        append("1. **Screen**: What app/page is currently shown? Is it the expected page for the current subgoal?\n")
        append("2. **Blocker**: Any popup, dialog, keyboard, or loading state that needs to be handled first?\n")
        append("3. **Target**: Is the target element visible? If not, should I scroll or navigate?\n")
        append("4. **Decision**: Based on the above analysis, what action should I take and why?\n\n")
        append("### Action ###\n")
        append("A valid JSON specifying the action. Example: {\"action\":\"click\", \"coordinate\": [500, 800]}\n\n")
        append("### Description ###\n")
        append("A brief description of the chosen action.\n")
    }

    /**
     * 解析执行响应
     * 支持两种格式:
     * 1. 标准格式 (### Thought / ### Action / ### Description)
     * 2. MAI-UI 格式 (<thinking>...</thinking><tool_call>...</tool_call>)
     */
    fun parseResponse(response: String): ExecutorResult {
        // 检测是否为 MAI-UI 格式
        if (response.contains("<tool_call>") || response.contains("<thinking>")) {
            return parseMAIUIResponse(response)
        }

        // 标准格式解析
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Action")
            .replace("###", "")
            .trim()

        val actionStr = response
            .substringAfter("### Action", "")
            .substringBefore("### Description")
            .replace("###", "")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val description = response
            .substringAfter("### Description", "")
            .replace("###", "")
            .trim()

        val action = Action.fromJson(actionStr)

        return ExecutorResult(thought, action, actionStr, description)
    }

    /**
     * 解析 MAI-UI 格式响应
     * 格式: <thinking>...</thinking><tool_call>{"name": "mobile_use", "arguments": {...}}</tool_call>
     */
    private fun parseMAIUIResponse(response: String): ExecutorResult {
        val thought = Action.extractThinking(response)
        val action = Action.fromMAIUIFormat(response)

        // 从 action 生成描述
        val description = when (action?.type) {
            "click" -> "点击坐标 (${action.x}, ${action.y})"
            "long_press" -> "长按坐标 (${action.x}, ${action.y})"
            "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
            "swipe" -> {
                if (action.direction != null) {
                    "向${action.direction}滑动"
                } else {
                    "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                }
            }
            "type" -> "输入文字: ${action.text}"
            "open_app" -> "打开应用: ${action.text}"
            "system_button" -> "按${action.button}键"
            "wait" -> "等待"
            "terminate" -> "任务${if (action.status == "success") "完成" else "失败"}"
            "answer" -> "回答: ${action.text?.take(30)}"
            "take_over" -> "请求用户: ${action.text}"
            else -> action?.type ?: "未知动作"
        }

        val actionStr = action?.toJson() ?: ""
        return ExecutorResult(thought, action, actionStr, description)
    }
}

data class ExecutorResult(
    val thought: String,
    val action: Action?,
    val actionStr: String,
    val description: String
)
