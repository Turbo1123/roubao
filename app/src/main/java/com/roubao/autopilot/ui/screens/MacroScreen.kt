package com.roubao.autopilot.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roubao.autopilot.macro.*
import com.roubao.autopilot.ui.theme.BaoziTheme

/**
 * 宏脚本列表页面
 */
@Composable
fun MacroScreen(
    macros: List<MacroScript>,
    playProgress: MacroPlayProgress,
    onMacroClick: (MacroScript) -> Unit,
    onPlayMacro: (MacroScript) -> Unit,
    onPauseMacro: () -> Unit,
    onResumeMacro: () -> Unit,
    onStopMacro: () -> Unit,
    onDeleteMacro: (String) -> Unit,
    onDuplicateMacro: (String) -> Unit
) {
    val colors = BaoziTheme.colors

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
                    text = "宏脚本",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                Text(
                    text = "共 ${macros.size} 个脚本",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        }

        // 播放状态卡片
        AnimatedVisibility(
            visible = playProgress.state != MacroPlayState.IDLE,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            MacroPlayStatusCard(
                progress = playProgress,
                onPause = onPauseMacro,
                onResume = onResumeMacro,
                onStop = onStopMacro
            )
        }

        if (macros.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = colors.textHint,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无宏脚本",
                        fontSize = 16.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "执行任务时可以录制操作保存为脚本",
                        fontSize = 14.sp,
                        color = colors.textHint
                    )
                }
            }
        } else {
            // 脚本列表
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = macros,
                    key = { it.id }
                ) { macro ->
                    MacroCard(
                        macro = macro,
                        isPlaying = playProgress.state == MacroPlayState.PLAYING,
                        onClick = { onMacroClick(macro) },
                        onPlay = { onPlayMacro(macro) },
                        onDelete = { onDeleteMacro(macro.id) },
                        onDuplicate = { onDuplicateMacro(macro.id) }
                    )
                }
            }
        }
    }
}

/**
 * 播放状态卡片
 */
@Composable
fun MacroPlayStatusCard(
    progress: MacroPlayProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    val colors = BaoziTheme.colors

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                MacroPlayState.PLAYING -> colors.primary.copy(alpha = 0.15f)
                MacroPlayState.PAUSED -> colors.warning.copy(alpha = 0.15f)
                MacroPlayState.STOPPED -> colors.error.copy(alpha = 0.15f)
                else -> colors.backgroundCard
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            when (progress.state) {
                                MacroPlayState.PLAYING -> colors.primary
                                MacroPlayState.PAUSED -> colors.warning
                                else -> colors.error
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (progress.state) {
                            MacroPlayState.PLAYING -> Icons.Default.PlayArrow
                            MacroPlayState.PAUSED -> Icons.Default.PlayArrow
                            else -> Icons.Default.Close
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (progress.state) {
                            MacroPlayState.PLAYING -> "正在播放"
                            MacroPlayState.PAUSED -> "已暂停"
                            MacroPlayState.STOPPED -> "已停止"
                            else -> ""
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = progress.currentActionDescription,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 控制按钮
                if (progress.state == MacroPlayState.PLAYING) {
                    IconButton(onClick = onPause) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "暂停",
                            tint = colors.primary
                        )
                    }
                } else if (progress.state == MacroPlayState.PAUSED) {
                    IconButton(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "继续",
                            tint = colors.warning
                        )
                    }
                }
                if (progress.state != MacroPlayState.IDLE && progress.state != MacroPlayState.STOPPED) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "停止",
                            tint = colors.error
                        )
                    }
                }
            }

            // 进度条
            if (progress.state == MacroPlayState.PLAYING || progress.state == MacroPlayState.PAUSED) {
                Spacer(modifier = Modifier.height(12.dp))
                val progressValue = if (progress.totalActions > 0) {
                    progress.currentAction.toFloat() / progress.totalActions
                } else 0f
                LinearProgressIndicator(
                    progress = progressValue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = when (progress.state) {
                        MacroPlayState.PLAYING -> colors.primary
                        MacroPlayState.PAUSED -> colors.warning
                        else -> colors.error
                    },
                    trackColor = colors.backgroundInput,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "步骤 ${progress.currentAction}/${progress.totalActions}",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    if (progress.totalLoops != 1) {
                        Text(
                            text = if (progress.totalLoops == -1) "循环 ${progress.currentLoop}/∞"
                            else "循环 ${progress.currentLoop}/${progress.totalLoops}",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                    }
                }
            }

            // 错误信息
            if (progress.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progress.errorMessage,
                    fontSize = 13.sp,
                    color = colors.error
                )
            }
        }
    }
}

/**
 * 宏脚本卡片
 */
@Composable
fun MacroCard(
    macro: MacroScript,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val colors = BaoziTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.backgroundCard,
            title = { Text("删除脚本", color = colors.textPrimary) },
            text = { Text("确定要删除「${macro.name}」吗？此操作无法撤销。", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = macro.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (macro.description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = macro.description,
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${macro.actionCount}步",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = macro.formattedEstimatedDuration,
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "·",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = macro.formattedCreatedAt,
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                }
            }

            // 播放按钮
            IconButton(
                onClick = onPlay,
                enabled = !isPlaying
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = if (isPlaying) colors.textHint else colors.primary
                )
            }

            // 更多菜单
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = colors.textHint
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("复制", color = colors.textPrimary) },
                        onClick = {
                            onDuplicate()
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Add, contentDescription = null, tint = colors.textSecondary)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = colors.error) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = colors.error)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 宏脚本详情页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroDetailScreen(
    macro: MacroScript,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onEdit: (MacroScript) -> Unit,
    isPlaying: Boolean
) {
    val colors = BaoziTheme.colors
    var editedName by remember { mutableStateOf(macro.name) }
    var editedDescription by remember { mutableStateOf(macro.description) }
    var isEditing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                if (isEditing) {
                    Text(
                        text = "编辑脚本",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                } else {
                    Column {
                        Text(
                            text = macro.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${macro.actionCount} 个动作",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.textPrimary
                    )
                }
            },
            actions = {
                if (isEditing) {
                    TextButton(onClick = {
                        onEdit(macro.copy(name = editedName, description = editedDescription))
                        isEditing = false
                    }) {
                        Text("保存", color = colors.primary)
                    }
                } else {
                    IconButton(onClick = { isEditing = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = colors.textPrimary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.background
            )
        )

        // 信息卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isEditing) {
                    // 编辑模式
                    Text(
                        text = "脚本名称",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.backgroundInput,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "描述",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editedDescription,
                        onValueChange = { editedDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.backgroundInput,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                } else {
                    // 查看模式
                    if (macro.description.isNotEmpty()) {
                        Text(
                            text = "描述",
                            fontSize = 12.sp,
                            color = colors.textHint
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = macro.description,
                            fontSize = 15.sp,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("动作数", fontSize = 12.sp, color = colors.textHint)
                            Text("${macro.actionCount}", fontSize = 14.sp, color = colors.textPrimary)
                        }
                        Column {
                            Text("预计时长", fontSize = 12.sp, color = colors.textHint)
                            Text(macro.formattedEstimatedDuration, fontSize = 14.sp, color = colors.textPrimary)
                        }
                        Column {
                            Text("循环", fontSize = 12.sp, color = colors.textHint)
                            Text(
                                if (macro.loopCount == 0) "无限" else "${macro.loopCount}次",
                                fontSize = 14.sp,
                                color = colors.textPrimary
                            )
                        }
                    }
                }
            }
        }

        // 播放按钮
        if (!isEditing) {
            Button(
                onClick = onPlay,
                enabled = !isPlaying,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    disabledContainerColor = colors.backgroundInput
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isPlaying) "播放中..." else "开始播放",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 动作列表标题
        Text(
            text = "动作列表",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = colors.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        // 动作列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            itemsIndexed(macro.actions) { index, action ->
                MacroActionItem(
                    action = action,
                    index = index + 1,
                    isLast = index == macro.actions.lastIndex
                )
            }
        }
    }
}

/**
 * 宏动作条目
 */
@Composable
fun MacroActionItem(
    action: MacroAction,
    index: Int,
    isLast: Boolean
) {
    val colors = BaoziTheme.colors

    Row(modifier = Modifier.fillMaxWidth()) {
        // 时间线指示器
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 序号圆点
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$index",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
            }
            // 连接线
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(colors.backgroundInput)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 动作内容
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 动作类型图标
                val (icon, iconColor) = when (action.type) {
                    MacroActionType.CLICK -> Icons.Default.PlayArrow to colors.primary
                    MacroActionType.LONG_PRESS -> Icons.Default.PlayArrow to colors.warning
                    MacroActionType.DOUBLE_TAP -> Icons.Default.PlayArrow to colors.secondary
                    MacroActionType.SWIPE -> Icons.Default.KeyboardArrowRight to colors.primary
                    MacroActionType.TYPE -> Icons.Default.Edit to colors.success
                    MacroActionType.SYSTEM_BUTTON -> Icons.Default.Home to colors.textSecondary
                    MacroActionType.WAIT -> Icons.Default.Refresh to colors.textHint
                    MacroActionType.OPEN_APP -> Icons.Default.Star to colors.primary
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.getShortDescription(),
                        fontSize = 14.sp,
                        color = colors.textPrimary
                    )
                    if (action.delay > 0) {
                        Text(
                            text = "延迟 ${action.delay}ms",
                            fontSize = 11.sp,
                            color = colors.textHint
                        )
                    }
                }
            }
        }
    }
}
