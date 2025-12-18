package com.roubao.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.tools.ToolManager
import com.roubao.autopilot.ui.theme.BaoziTheme

/**
 * 工具信息（用于展示）
 */
data class ToolInfo(
    val name: String,
    val description: String
)

/**
 * Agent 角色信息
 */
data class AgentInfo(
    val name: String,
    val icon: String,
    val role: String,
    val description: String,
    val responsibilities: List<String>
)

/**
 * 预定义的 Agents 列表
 */
val agentsList = listOf(
    AgentInfo(
        name = "Manager",
        icon = "🎯",
        role = "规划者",
        description = "负责理解用户意图，制定高层次的执行计划，并跟踪任务进度。",
        responsibilities = listOf(
            "分析用户请求，理解真实意图",
            "将复杂任务分解为可执行的子目标",
            "制定执行计划和步骤顺序",
            "根据执行反馈动态调整计划"
        )
    ),
    AgentInfo(
        name = "Executor",
        icon = "⚡",
        role = "执行者",
        description = "负责分析当前屏幕状态，决定具体的操作动作。",
        responsibilities = listOf(
            "分析屏幕截图，理解界面元素",
            "根据计划选择下一步操作",
            "确定点击、滑动、输入等具体动作",
            "输出精确的操作坐标和参数"
        )
    ),
    AgentInfo(
        name = "Reflector",
        icon = "🔍",
        role = "反思者",
        description = "负责评估操作结果，判断动作是否成功执行。",
        responsibilities = listOf(
            "对比操作前后的屏幕变化",
            "判断操作是否达到预期效果",
            "识别异常情况（如弹窗、错误）",
            "提供反馈帮助调整后续策略"
        )
    ),
    AgentInfo(
        name = "Notetaker",
        icon = "📝",
        role = "记录者",
        description = "负责记录执行过程中的关键信息，供其他 Agent 参考。",
        responsibilities = listOf(
            "记录任务执行的重要节点",
            "保存中间结果和状态信息",
            "为后续步骤提供上下文参考",
            "生成执行摘要和日志"
        )
    )
)

/**
 * 能力展示页面
 *
 * 展示 Agents 和 Tools（只读）
 */
@Composable
fun CapabilitiesScreen() {
    val colors = BaoziTheme.colors

    // 获取 Tools
    val tools = remember {
        if (ToolManager.isInitialized()) {
            ToolManager.getInstance().getAvailableTools().map { tool ->
                ToolInfo(name = tool.name, description = tool.description)
            }
        } else {
            emptyList()
        }
    }

    // 额外的内置工具（不在 ToolManager 中但是系统能力）
    val builtInTools = listOf(
        ToolInfo("screenshot", "截取当前屏幕，获取界面图像供 AI 分析"),
        ToolInfo("tap", "点击屏幕指定坐标位置"),
        ToolInfo("swipe", "在屏幕上滑动，支持上下左右方向"),
        ToolInfo("type", "输入文本内容到当前焦点位置"),
        ToolInfo("press_key", "按下系统按键（Home、Back、Enter 等）")
    )

    val allTools = tools + builtInTools

    // Tab 状态
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Agents (${agentsList.size})", "Tools (${allTools.size})")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Column {
                Text(
                    text = "能力",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "${agentsList.size} 个 Agent，${allTools.size} 个工具",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        // Tab 切换
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = colors.background,
            contentColor = colors.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) colors.primary else colors.textSecondary
                        )
                    }
                )
            }
        }

        // 内容区域
        when (selectedTab) {
            0 -> AgentsListView()
            1 -> ToolsListView(tools = allTools)
        }
    }
}

@Composable
fun AgentsListView() {
    val colors = BaoziTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 架构说明卡片
        item(key = "arch_intro") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.primary.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "🧠 多 Agent 协作架构",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "肉包采用多 Agent 协作架构，每个 Agent 专注于特定职责，通过协作完成复杂的手机自动化任务。",
                        fontSize = 13.sp,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manager → Executor → Reflector → Notetaker",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textHint
                    )
                }
            }
        }

        // Agent 列表
        items(agentsList, key = { it.name }) { agent ->
            AgentCard(agent = agent)
        }
    }
}

@Composable
fun AgentCard(agent: AgentInfo) {
    val colors = BaoziTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Agent 图标
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = agent.icon,
                        fontSize = 28.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = agent.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.secondary.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = agent.role,
                                fontSize = 11.sp,
                                color = colors.secondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = agent.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textHint
                )
            }

            // 展开显示职责列表
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "职责",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    agent.responsibilities.forEach { responsibility ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                fontSize = 14.sp,
                                color = colors.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = responsibility,
                                fontSize = 13.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolsListView(tools: List<ToolInfo>) {
    if (tools.isEmpty()) {
        EmptyState(message = "暂无工具")
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                ToolCard(tool = tool)
            }
        }
    }
}

@Composable
fun ToolCard(tool: ToolInfo) {
    val colors = BaoziTheme.colors

    // 根据工具名获取图标
    val toolIcon = when (tool.name) {
        "search_apps" -> "🔍"
        "open_app" -> "📱"
        "deep_link" -> "🔗"
        "clipboard" -> "📋"
        "shell" -> "💻"
        "http" -> "🌐"
        else -> "🔧"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 工具图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.secondary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = toolIcon,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyState(message: String) {
    val colors = BaoziTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📦",
                fontSize = 64.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = colors.textSecondary
            )
        }
    }
}
