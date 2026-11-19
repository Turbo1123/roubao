package com.roubao.autopilot.agent

/**
 * Manager Agent - 负责规划和进度管理
 */
class Manager {

    /**
     * 生成规划 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String {
        return if (infoPool.plan.isEmpty()) {
            getInitialPlanPrompt(infoPool)
        } else {
            getUpdatePlanPrompt(infoPool)
        }
    }

    private fun getInitialPlanPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent who can operate an Android phone on behalf of a user. ")
        append("Your goal is to track progress and devise high-level plans to achieve the user's requests.\n\n")

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

        append("---\n")
        append("Make a high-level plan to achieve the user's request. ")
        append("If the request is complex, break it down into subgoals. ")
        append("The screenshot displays the starting state of the phone.\n\n")

        append("IMPORTANT: For requests that explicitly require an answer, ")
        append("always add 'perform the `answer` action' as the last step to the plan!\n\n")

        // Skill 上下文
        if (infoPool.skillContext.isNotEmpty()) {
            append("### Available Skills ###\n")
            append("${infoPool.skillContext}\n\n")
        }

        append("### Guidelines ###\n")
        append("1. IMPORTANT: If you see the \"肉包\" or \"Baozi\" app interface (this automation tool), press Home button first to go back to the home screen, then proceed with the task.\n")
        append("2. ALWAYS use `open_app` action to open apps - NEVER go to home screen to look for app icons! The open_app action can launch any installed app directly.\n")
        append("3. Use search to quickly find a file or entry with a specific name.\n")
        append("4. If there are relevant skills listed above, follow their suggested steps for better efficiency.\n")
        if (infoPool.additionalKnowledge.isNotEmpty()) {
            append("5. ${infoPool.additionalKnowledge}\n")
        }
        append("\n")

        append("Provide your output in the following format:\n\n")
        append("### Thought ###\n")
        append("A detailed explanation of your rationale for the plan.\n\n")
        append("### Plan ###\n")
        append("1. first subgoal\n")
        append("2. second subgoal\n")
        append("...\n")
    }

    private fun getUpdatePlanPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent who can operate an Android phone on behalf of a user. ")
        append("Your goal is to track progress and update plans.\n\n")

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

        if (infoPool.completedPlan.isNotEmpty() && infoPool.completedPlan != "No completed subgoal.") {
            append("### Historical Operations ###\n")
            append("${infoPool.completedPlan}\n\n")
        }

        append("### Current Plan ###\n")
        append("${infoPool.plan}\n\n")

        append("### Last Action ###\n")
        append("${infoPool.lastAction}\n\n")

        append("### Last Action Description ###\n")
        append("${infoPool.lastSummary}\n\n")

        // 最近的动作结果
        if (infoPool.actionOutcomes.isNotEmpty()) {
            val recentOutcomes = infoPool.actionOutcomes.takeLast(3)
            val failCount = recentOutcomes.count { it in listOf("B", "C") }
            if (failCount > 0) {
                append("### Recent Action Results ###\n")
                append("Last ${recentOutcomes.size} actions: ${recentOutcomes.joinToString(", ")} ")
                append("(A=success, B=partial, C=failed)\n")
                append("Failed attempts: $failCount\n\n")
            }
        }

        if (infoPool.importantNotes.isNotEmpty()) {
            append("### Important Notes ###\n")
            append("${infoPool.importantNotes}\n\n")
        }

        // 错误升级
        if (infoPool.errorFlagPlan) {
            append("### ⚠️ STUCK - Multiple Failed Attempts! ###\n")
            append("You have encountered several consecutive failed attempts:\n")
            val k = infoPool.errToManagerThresh
            val recentActions = infoPool.actionHistory.takeLast(k)
            val recentSummaries = infoPool.summaryHistory.takeLast(k)
            val recentErrors = infoPool.errorDescriptions.takeLast(k)

            recentActions.forEachIndexed { i, act ->
                append("- Action: $act | Description: ${recentSummaries.getOrNull(i)} | Failed: ${recentErrors.getOrNull(i)}\n")
            }
            append("\nIMPORTANT: DO NOT mark as \"Finished\" when there are failed attempts! ")
            append("Try a different approach:\n")
            append("- Wait for page to load (the UI might be loading)\n")
            append("- Try clicking at different coordinates\n")
            append("- Scroll to find the correct button\n")
            append("- Press Back and try again\n\n")
        }

        append("---\n")
        append("Assess the current status.\n\n")

        append("### ⛔ SECURITY: Sensitive Pages - MUST STOP ###\n")
        append("ONLY output \"STOP_SENSITIVE\" when the screen is ACTIVELY REQUESTING one of these:\n")
        append("- A payment confirmation button that will charge money (确认支付, 立即付款, 确认付款)\n")
        append("- A password input field that is focused and waiting for input\n")
        append("- Face ID or fingerprint verification dialog\n")
        append("DO NOT stop for: price displays, payment method selection, cart pages, or general app navigation.\n\n")

        append("### CRITICAL: When to mark \"Finished\" ###\n")
        append("ONLY mark as \"Finished\" when ALL of these are true:\n")
        append("1. The user's request has been FULLY completed (not partially)\n")
        append("2. You can SEE the final success state in the screenshot (e.g., order confirmed, message sent, setting changed)\n")
        append("3. The last action was SUCCESSFUL (outcome A), not failed (outcome C)\n")
        append("4. There are NO recent consecutive failures\n\n")
        append("If any action failed or the task is incomplete, DO NOT say \"Finished\". Instead, update the plan with a new approach.\n\n")

        append("Provide your output in the following format:\n\n")
        append("### Thought ###\n")
        append("Your rationale for the updated plan.\n\n")
        append("### Historical Operations ###\n")
        append("Add newly completed subgoals on top of existing ones.\n\n")
        append("### Plan ###\n")
        append("Updated plan or \"Finished\" if truly done.\n")
    }

    /**
     * 解析规划响应
     */
    fun parseResponse(response: String): PlanResult {
        val thought = response
            .substringAfter("### Thought", "")
            .substringBefore("### Historical Operations")
            .substringBefore("### Plan")
            .replace("###", "")
            .trim()

        val completedSubgoal = if (response.contains("### Historical Operations")) {
            response
                .substringAfter("### Historical Operations")
                .substringBefore("### Plan")
                .replace("###", "")
                .trim()
        } else {
            "No completed subgoal."
        }

        val plan = response
            .substringAfter("### Plan")
            .replace("###", "")
            .trim()

        return PlanResult(thought, completedSubgoal, plan)
    }
}

data class PlanResult(
    val thought: String,
    val completedSubgoal: String,
    val plan: String
)
