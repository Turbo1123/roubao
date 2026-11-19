package com.roubao.autopilot.ui.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.roubao.autopilot.BuildConfig
import com.roubao.autopilot.controller.AccessibilityBridge
import com.roubao.autopilot.controller.AdbBridge
import com.roubao.autopilot.data.ApiProvider
import com.roubao.autopilot.data.AppSettings
import com.roubao.autopilot.data.ControlMode
import com.roubao.autopilot.ui.theme.BaoziTheme
import com.roubao.autopilot.ui.theme.ThemeMode
import com.roubao.autopilot.utils.CrashHandler

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit = {},
    onUpdateApiKey: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateCachedModels: (List<String>) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateMaxSteps: (Int) -> Unit,
    onUpdateCloudCrashReport: (Boolean) -> Unit,
    onUpdateRootModeEnabled: (Boolean) -> Unit,
    onUpdateSuCommandEnabled: (Boolean) -> Unit,
    onUpdateControlMode: (ControlMode) -> Unit,
    onUpdateCustomSystemPrompt: (String) -> Unit,
    onClearLongTermMemory: () -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    shizukuAvailable: Boolean,
    shizukuPrivilegeLevel: String = "ADB", // "ADB", "ROOT", "NONE"
    onFetchModels: ((onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMaxStepsDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showShizukuHelpDialog by remember { mutableStateOf(false) }
    var showOverlayHelpDialog by remember { mutableStateOf(false) }
    var showRootModeWarningDialog by remember { mutableStateOf(false) }
    var showSuCommandWarningDialog by remember { mutableStateOf(false) }
    var showControlModeDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var showMemoryDialog by remember { mutableStateOf(false) }

    // 无障碍服务开启状态（从系统设置返回时刷新）
    val context = LocalContext.current
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityBridge.isEnabled(context)) }
    DisposableEffect(context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = AccessibilityBridge.isEnabled(context)
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题（居中 + 左侧圆形返回键，仿 DeepSeek 设置页）
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(colors.backgroundCard)
                        .align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "返回",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
        }

        // 外观设置分组
        item {
            SettingsSection(title = "外观")
        }

        // 主题模式设置
        item {
            SettingsItem(
                icon = if (colors.isDark) Icons.Default.Star else Icons.Outlined.Star,
                title = "主题模式",
                subtitle = when (settings.themeMode) {
                    ThemeMode.LIGHT -> "浅色模式"
                    ThemeMode.DARK -> "深色模式"
                    ThemeMode.SYSTEM -> "跟随系统"
                },
                onClick = { showThemeDialog = true }
            )
        }

        // 执行设置分组
        item {
            SettingsSection(title = "执行设置")
        }

        // 最大步数设置
        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "最大执行步数",
                subtitle = "${settings.maxSteps} 步",
                onClick = { showMaxStepsDialog = true }
            )
        }

        // 控制方式设置
        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "控制方式",
                subtitle = when (settings.controlMode) {
                    ControlMode.SHIZUKU -> "Shizuku"
                    ControlMode.ACCESSIBILITY -> "无障碍"
                    ControlMode.ADB -> "ADB（无线调试）"
                },
                onClick = { showControlModeDialog = true }
            )
        }

        // 无障碍服务入口（无障碍模式时显示）
        if (settings.controlMode == ControlMode.ACCESSIBILITY) {
            item {
                SettingsItem(
                    icon = Icons.Default.Settings,
                    title = "无障碍服务",
                    subtitle = if (accessibilityEnabled) "已开启" else "未开启，点击前往开启",
                    onClick = {
                        context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }

        // ADB 连接管理（ADB 模式时显示）
        if (settings.controlMode == ControlMode.ADB) {
            item {
                AdbSettingsCard()
            }
        }

        // 系统提示词设置
        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "系统提示词",
                subtitle = if (settings.customSystemPrompt.isBlank()) "使用默认提示词" else "已自定义",
                onClick = { showSystemPromptDialog = true }
            )
        }

        // 长期记忆设置
        item {
            SettingsItem(
                icon = Icons.Default.Star,
                title = "长期记忆",
                subtitle = if (settings.longTermMemory.isBlank()) "暂无记录" else "已保存，角色会记住之前的互动",
                onClick = { showMemoryDialog = true }
            )
        }

        // Shizuku 高级设置分组（仅在 Shizuku 可用时显示）
        if (shizukuAvailable) {
            item {
                SettingsSection(title = "Shizuku 高级选项")
            }

            // 显示当前权限级别
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when (shizukuPrivilegeLevel) {
                                        "ROOT" -> colors.error.copy(alpha = 0.15f)
                                        else -> colors.primary.copy(alpha = 0.15f)
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = when (shizukuPrivilegeLevel) {
                                    "ROOT" -> colors.error
                                    else -> colors.primary
                                },
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前权限级别",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary
                            )
                            Text(
                                text = when (shizukuPrivilegeLevel) {
                                    "ROOT" -> "Root 模式 (UID 0)"
                                    "ADB" -> "ADB 模式 (UID 2000)"
                                    else -> "未连接"
                                },
                                fontSize = 13.sp,
                                color = when (shizukuPrivilegeLevel) {
                                    "ROOT" -> colors.error
                                    else -> colors.textSecondary
                                },
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Root 模式开关（仅在 Shizuku 以 Root 权限运行时可用）
            item {
                val isShizukuRoot = shizukuPrivilegeLevel == "ROOT"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isShizukuRoot) colors.error.copy(alpha = 0.15f)
                                    else colors.textHint.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isShizukuRoot) colors.error else colors.textHint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Root 模式",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isShizukuRoot) colors.textPrimary else colors.textHint
                            )
                            Text(
                                text = when {
                                    !isShizukuRoot -> "需要 Shizuku 以 Root 权限运行"
                                    settings.rootModeEnabled -> "已启用高级权限"
                                    else -> "启用后可使用 Root 功能"
                                },
                                fontSize = 13.sp,
                                color = when {
                                    !isShizukuRoot -> colors.textHint
                                    settings.rootModeEnabled -> colors.error
                                    else -> colors.textSecondary
                                },
                                maxLines = 1
                            )
                        }
                        Switch(
                            checked = settings.rootModeEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    showRootModeWarningDialog = true
                                } else {
                                    onUpdateRootModeEnabled(false)
                                }
                            },
                            enabled = isShizukuRoot,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.error,
                                checkedTrackColor = colors.error.copy(alpha = 0.5f),
                                uncheckedThumbColor = colors.textHint,
                                uncheckedTrackColor = colors.backgroundInput,
                                disabledCheckedThumbColor = colors.textHint,
                                disabledCheckedTrackColor = colors.backgroundInput,
                                disabledUncheckedThumbColor = colors.textHint.copy(alpha = 0.5f),
                                disabledUncheckedTrackColor = colors.backgroundInput.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
            }

            // su -c 开关（仅在 Root 模式开启时显示）
            if (settings.rootModeEnabled) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.error.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = colors.error,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "允许 su -c 命令",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = if (settings.suCommandEnabled) "AI 可执行 Root 命令" else "禁止执行 su -c",
                                    fontSize = 13.sp,
                                    color = if (settings.suCommandEnabled) colors.error else colors.textSecondary,
                                    maxLines = 1
                                )
                            }
                            Switch(
                                checked = settings.suCommandEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        showSuCommandWarningDialog = true
                                    } else {
                                        onUpdateSuCommandEnabled(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.error,
                                    checkedTrackColor = colors.error.copy(alpha = 0.5f),
                                    uncheckedThumbColor = colors.textHint,
                                    uncheckedTrackColor = colors.backgroundInput
                                )
                            )
                        }
                    }
                }
            }
        }

        // API 设置分组
        item {
            SettingsSection(title = "API 配置")
        }

        // Base URL 设置
        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "API 服务商",
                subtitle = settings.currentProvider.name,
                onClick = { showBaseUrlDialog = true }
            )
        }

        // API Key 设置
        item {
            SettingsItem(
                icon = Icons.Default.Lock,
                title = "API Key",
                subtitle = if (settings.apiKey.isNotEmpty()) "已设置 (${maskApiKey(settings.apiKey)})" else "未设置",
                onClick = { showApiKeyDialog = true }
            )
        }

        // 模型设置
        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "模型",
                subtitle = settings.model,
                onClick = { showModelDialog = true }
            )
        }

        // 反馈分组
        item {
            SettingsSection(title = "反馈与调试")
        }

        // 云端崩溃上报开关
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "云端崩溃上报",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary
                        )
                        Text(
                            text = if (settings.cloudCrashReportEnabled) "已开启，帮助我们改进应用" else "已关闭",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                            maxLines = 1
                        )
                    }
                    Switch(
                        checked = settings.cloudCrashReportEnabled,
                        onCheckedChange = { onUpdateCloudCrashReport(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.primary,
                            checkedTrackColor = colors.primary.copy(alpha = 0.5f),
                            uncheckedThumbColor = colors.textHint,
                            uncheckedTrackColor = colors.backgroundInput
                        )
                    )
                }
            }
        }

        item {
            val context = LocalContext.current
            val logStats = remember { mutableStateOf(CrashHandler.getLogStats(context)) }

            SettingsItem(
                icon = Icons.Default.Info,
                title = "导出日志",
                subtitle = logStats.value,
                onClick = {
                    CrashHandler.shareLogs(context)
                }
            )
        }

        item {
            val context = LocalContext.current
            var showClearDialog by remember { mutableStateOf(false) }

            SettingsItem(
                icon = Icons.Default.Close,
                title = "清除日志",
                subtitle = "删除所有本地日志文件",
                onClick = { showClearDialog = true }
            )

            if (showClearDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    containerColor = BaoziTheme.colors.backgroundCard,
                    title = { Text("确认清除", color = BaoziTheme.colors.textPrimary) },
                    text = { Text("确定要删除所有日志文件吗？", color = BaoziTheme.colors.textSecondary) },
                    confirmButton = {
                        TextButton(onClick = {
                            CrashHandler.clearLogs(context)
                            showClearDialog = false
                            android.widget.Toast.makeText(context, "日志已清除", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("确定", color = BaoziTheme.colors.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) {
                            Text("取消", color = BaoziTheme.colors.textSecondary)
                        }
                    }
                )
            }
        }

        // 帮助分组
        item {
            SettingsSection(title = "帮助")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "Shizuku 使用指南",
                subtitle = "了解如何安装和配置 Shizuku",
                onClick = { showShizukuHelpDialog = true }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "悬浮窗权限说明",
                subtitle = "了解为什么需要悬浮窗权限",
                onClick = { showOverlayHelpDialog = true }
            )
        }

        // 关于分组
        item {
            SettingsSection(title = "关于")
        }

        item {
            SettingsItem(
                icon = Icons.Default.Info,
                title = "版本",
                subtitle = BuildConfig.VERSION_NAME,
                onClick = { }
            )
        }

        item {
            SettingsItem(
                icon = Icons.Default.Build,
                title = "肉包 Autopilot",
                subtitle = "基于视觉语言模型的 Android 自动化工具",
                onClick = { }
            )
        }

        // 底部间距
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 主题选择对话框
    if (showThemeDialog) {
        ThemeSelectDialog(
            currentTheme = settings.themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                onUpdateThemeMode(it)
                showThemeDialog = false
            }
        )
    }

    // 最大步数设置对话框
    if (showMaxStepsDialog) {
        MaxStepsDialog(
            currentSteps = settings.maxSteps,
            onDismiss = { showMaxStepsDialog = false },
            onConfirm = {
                onUpdateMaxSteps(it)
                showMaxStepsDialog = false
            }
        )
    }

    // 控制方式选择对话框
    if (showControlModeDialog) {
        ControlModeDialog(
            currentMode = settings.controlMode,
            onDismiss = { showControlModeDialog = false },
            onSelect = {
                onUpdateControlMode(it)
                showControlModeDialog = false
            }
        )
    }

    // 系统提示词编辑对话框
    if (showSystemPromptDialog) {
        SystemPromptDialog(
            currentPrompt = settings.customSystemPrompt,
            onDismiss = { showSystemPromptDialog = false },
            onConfirm = {
                onUpdateCustomSystemPrompt(it)
                showSystemPromptDialog = false
            }
        )
    }

    // 长期记忆对话框
    if (showMemoryDialog) {
        LongTermMemoryDialog(
            memory = settings.longTermMemory,
            onDismiss = { showMemoryDialog = false },
            onClear = {
                onClearLongTermMemory()
                showMemoryDialog = false
            }
        )
    }

    // API Key 编辑对话框
    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = settings.apiKey,
            onDismiss = { showApiKeyDialog = false },
            onConfirm = {
                onUpdateApiKey(it)
                showApiKeyDialog = false
            }
        )
    }

    // 模型选择对话框（合并了自定义输入和从 API 获取）
    if (showModelDialog) {
        ModelSelectDialogWithFetch(
            currentModel = settings.model,
            cachedModels = settings.cachedModels,
            hasApiKey = settings.apiKey.isNotEmpty(),
            onDismiss = { showModelDialog = false },
            onSelect = {
                onUpdateModel(it)
                showModelDialog = false
            },
            onFetchModels = onFetchModels,
            onUpdateCachedModels = onUpdateCachedModels
        )
    }

    // 服务商选择对话框
    if (showBaseUrlDialog) {
        ProviderSelectDialog(
            currentProviderId = settings.currentProviderId,
            customBaseUrl = settings.currentConfig.customBaseUrl,
            onDismiss = { showBaseUrlDialog = false },
            onSelectProvider = { provider ->
                onSelectProvider(provider)
                showBaseUrlDialog = false
            },
            onUpdateCustomUrl = { url ->
                onUpdateBaseUrl(url)
            }
        )
    }

    // Shizuku 帮助对话框
    if (showShizukuHelpDialog) {
        ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
    }

    // 悬浮窗权限帮助对话框
    if (showOverlayHelpDialog) {
        OverlayHelpDialog(onDismiss = { showOverlayHelpDialog = false })
    }

    // Root 模式警告对话框
    if (showRootModeWarningDialog) {
        RootModeWarningDialog(
            onDismiss = { showRootModeWarningDialog = false },
            onConfirm = {
                onUpdateRootModeEnabled(true)
                showRootModeWarningDialog = false
            }
        )
    }

    // su -c 命令警告对话框
    if (showSuCommandWarningDialog) {
        SuCommandWarningDialog(
            onDismiss = { showSuCommandWarningDialog = false },
            onConfirm = {
                onUpdateSuCommandEnabled(true)
                showSuCommandWarningDialog = false
            }
        )
    }
}

@Composable
fun SettingsSection(title: String) {
    val colors = BaoziTheme.colors
    Text(
        text = title,
        fontSize = 13.sp,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            if (trailing != null) {
                trailing()
            } else {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textHint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}



@Composable
fun ThemeSelectDialog(
    currentTheme: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择主题", color = colors.textPrimary)
        },
        text = {
            Column {
                listOf(
                    ThemeMode.LIGHT to "浅色模式",
                    ThemeMode.DARK to "深色模式",
                    ThemeMode.SYSTEM to "跟随系统"
                ).forEach { (mode, label) ->
                    val isSelected = mode == currentTheme
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(2.dp, colors.textHint, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = if (isSelected) colors.primary else colors.textPrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun ApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var key by remember { mutableStateOf(currentKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("API Key", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "请输入您的 API Key",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundInput)
                        .padding(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = key,
                            onValueChange = { key = it },
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(12.dp)
                        )
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(
                                text = if (showKey) "隐藏" else "显示",
                                fontSize = 12.sp,
                                color = colors.textHint
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key) }) {
                Text("确定", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}


/**
 * 模型选择对话框（合并了自定义输入和从 API 获取）
 */
@Composable
fun ModelSelectDialogWithFetch(
    currentModel: String,
    cachedModels: List<String>,
    hasApiKey: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onFetchModels: ((onSuccess: (List<String>) -> Unit, onError: (String) -> Unit) -> Unit)? = null,
    onUpdateCachedModels: (List<String>) -> Unit
) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    var customModel by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // 默认推荐模型
    val defaultModel = "qwen3-vl-plus"

    // 过滤后的模型列表
    val filteredModels = remember(cachedModels, searchQuery) {
        if (searchQuery.isBlank()) {
            cachedModels
        } else {
            cachedModels.filter { it.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择模型", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 默认推荐模型
                Text(
                    text = "推荐模型",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textHint,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val isDefaultSelected = currentModel == defaultModel
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(defaultModel) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDefaultSelected) colors.primary.copy(alpha = 0.15f) else colors.backgroundInput
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDefaultSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .border(2.dp, colors.textHint, CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = defaultModel,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDefaultSelected) colors.primary else colors.textPrimary
                            )
                            Text(
                                text = "阿里云通义千问视觉模型",
                                fontSize = 11.sp,
                                color = colors.textHint
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 自定义模型输入
                Text(
                    text = "自定义模型",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textHint,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.backgroundInput)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (customModel.isEmpty()) {
                            Text(
                                text = "输入模型名称，如 gpt-4o",
                                color = colors.textHint,
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = customModel,
                            onValueChange = { customModel = it },
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 确认按钮
                    Surface(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(enabled = customModel.isNotBlank()) {
                                onSelect(customModel.trim())
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (customModel.isNotBlank()) colors.primary else colors.backgroundInput
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "确认",
                                tint = if (customModel.isNotBlank()) Color.White else colors.textHint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 从 API 获取模型 - 更明显的按钮
                if (onFetchModels != null) {
                    Button(
                        onClick = {
                            if (!hasApiKey) {
                                android.widget.Toast.makeText(context, "请先设置 API Key", android.widget.Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isLoading = true
                            onFetchModels(
                                { models ->
                                    isLoading = false
                                    onUpdateCachedModels(models)
                                    android.widget.Toast.makeText(context, "获取到 ${models.size} 个模型", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                { error ->
                                    isLoading = false
                                    android.widget.Toast.makeText(context, "获取失败: $error", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        enabled = !isLoading && hasApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            disabledContainerColor = colors.backgroundInput
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("获取中...", fontSize = 14.sp, color = Color.White)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (hasApiKey) Color.White else colors.textHint
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "从 API 获取可用模型",
                                fontSize = 14.sp,
                                color = if (hasApiKey) Color.White else colors.textHint
                            )
                        }
                    }

                    if (cachedModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "API 模型列表 (${cachedModels.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textHint,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 搜索框（模型数量超过 10 个时显示）
                    if (cachedModels.size > 10) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.backgroundInput)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = colors.textHint,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索模型...",
                                            color = colors.textHint,
                                            fontSize = 14.sp
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = colors.textPrimary,
                                            fontSize = 14.sp
                                        ),
                                        cursorBrush = SolidColor(colors.primary),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清除",
                                        tint = colors.textHint,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { searchQuery = "" }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 模型列表
                if (cachedModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hasApiKey) "点击「从 API 获取」加载模型列表" else "请先设置 API Key",
                            fontSize = 13.sp,
                            color = colors.textHint
                        )
                    }
                } else if (filteredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有匹配「$searchQuery」的模型",
                            fontSize = 13.sp,
                            color = colors.textHint
                        )
                    }
                } else {
                    // 显示过滤结果数量
                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = "找到 ${filteredModels.size} 个模型",
                            fontSize = 11.sp,
                            color = colors.textHint,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    filteredModels.forEach { model ->
                        val isSelected = model == currentModel
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onSelect(model) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, colors.textHint, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = model,
                                    fontSize = 14.sp,
                                    color = if (isSelected) colors.primary else colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

private fun maskApiKey(key: String): String {
    return if (key.length > 8) {
        "${key.take(4)}****${key.takeLast(4)}"
    } else {
        "****"
    }
}

@Composable
fun ShizukuHelpDialog(onDismiss: () -> Unit) {
    val colors = BaoziTheme.colors
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("Shizuku 使用指南", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                HelpStep(
                    number = "1",
                    title = "下载 Shizuku",
                    description = "从 Google Play 或 GitHub 下载 Shizuku 应用"
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 下载按钮
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                        )
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往下载 Shizuku", color = Color.White)
                }
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "2",
                    title = "启动 Shizuku",
                    description = "打开 Shizuku 应用，根据您的设备选择启动方式：\n\n• 无线调试（推荐）：需要 Android 11+，在开发者选项中开启无线调试\n• 连接电脑：通过 ADB 命令启动"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "3",
                    title = "授权肉包",
                    description = "在 Shizuku 的「应用管理」中找到「肉包」，点击授权按钮"
                )
                Spacer(modifier = Modifier.height(16.dp))
                HelpStep(
                    number = "4",
                    title = "开始使用",
                    description = "授权完成后，返回肉包应用，即可开始使用"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = colors.primary)
            }
        }
    )
}

@Composable
fun OverlayHelpDialog(onDismiss: () -> Unit) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("悬浮窗权限说明", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "为什么需要悬浮窗权限？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "肉包在执行任务时需要显示悬浮窗来：",
                    fontSize = 14.sp,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("显示当前执行进度")
                BulletPoint("提供停止按钮，随时中断任务")
                BulletPoint("在其他应用上方显示状态信息")

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "如何开启？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. 点击执行任务时会自动提示\n2. 或前往：设置 > 应用 > 肉包 > 悬浮窗权限\n3. 开启「允许显示在其他应用上层」",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "隐私安全",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "悬浮窗仅在任务执行期间显示，不会收集任何个人信息。任务完成后悬浮窗会自动消失。",
                    fontSize = 14.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = colors.primary)
            }
        }
    )
}

@Composable
private fun HelpStep(
    number: String,
    title: String,
    description: String
) {
    val colors = BaoziTheme.colors
    Row {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = colors.textSecondary,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    val colors = BaoziTheme.colors
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp)
    ) {
        Text(
            text = "•",
            fontSize = 14.sp,
            color = colors.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = colors.textPrimary
        )
    }
}

@Composable
fun MaxStepsDialog(
    currentSteps: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = BaoziTheme.colors
    var steps by remember { mutableStateOf(currentSteps.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("最大执行步数", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "设置 Agent 单次任务的最大执行步数。步数越多，能完成的任务越复杂、会话越长，但消耗的 token 也越多。",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 当前值显示
                Text(
                    text = "${steps.toInt()} 步",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 滑块
                Slider(
                    value = steps,
                    onValueChange = { steps = it },
                    valueRange = 5f..300f,
                    steps = 58, // (300-5)/5 - 1 = 58 个刻度点，每 5 步一个
                    colors = SliderDefaults.colors(
                        thumbColor = colors.primary,
                        activeTrackColor = colors.primary,
                        inactiveTrackColor = colors.backgroundInput
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // 范围提示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "5",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                    Text(
                        text = "300",
                        fontSize = 12.sp,
                        color = colors.textHint
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 快捷选项
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(25, 50, 100, 200).forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { steps = preset.toFloat() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (steps.toInt() == preset) colors.primary else colors.backgroundInput
                        ) {
                            Text(
                                text = "$preset",
                                fontSize = 14.sp,
                                color = if (steps.toInt() == preset) Color.White else colors.textSecondary,
                                modifier = Modifier.padding(vertical = 8.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(steps.toInt()) }) {
                Text("确定", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

/**
 * 服务商选择对话框
 */
@Composable
fun ProviderSelectDialog(
    currentProviderId: String,
    customBaseUrl: String,
    onDismiss: () -> Unit,
    onSelectProvider: (ApiProvider) -> Unit,
    onUpdateCustomUrl: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var selectedProviderId by remember { mutableStateOf(currentProviderId) }
    var customUrl by remember { mutableStateOf(customBaseUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("API 服务商", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "选择 API 服务商（支持 OpenAI 兼容接口）",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 服务商列表
                ApiProvider.ALL.forEach { provider ->
                    val isSelected = provider.id == selectedProviderId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedProviderId = provider.id
                                onSelectProvider(provider)
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, colors.textHint, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = provider.name,
                                    fontSize = 14.sp,
                                    color = if (isSelected) colors.primary else colors.textPrimary
                                )
                            }
                            // 对于非自定义服务商，显示其 URL
                            if (provider.id != "custom") {
                                Text(
                                    text = provider.baseUrl,
                                    fontSize = 11.sp,
                                    color = colors.textHint,
                                    modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                                )
                            }
                        }
                    }

                    // 自定义服务商或 MAI-UI 选中时显示 URL 输入框
                    if ((provider.id == "custom" || provider.id == "mai_ui") && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 28.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.backgroundInput)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (customUrl.isEmpty()) {
                                Text(
                                    text = if (provider.id == "mai_ui") "http://your-server:8000/v1" else "https://api.example.com/v1",
                                    color = colors.textHint,
                                    fontSize = 14.sp
                                )
                            }
                            BasicTextField(
                                value = customUrl,
                                onValueChange = { newUrl ->
                                    customUrl = newUrl
                                    onUpdateCustomUrl(newUrl)
                                },
                                textStyle = TextStyle(
                                    color = colors.textPrimary,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        Text(
                            text = if (provider.id == "mai_ui") "留空使用默认地址 (localhost:8000)" else "输入自定义 API 端点地址",
                            fontSize = 11.sp,
                            color = colors.textHint,
                            modifier = Modifier.padding(start = 28.dp, top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = colors.primary)
            }
        }
    )
}

/**
 * Root 模式警告对话框
 */
@Composable
fun RootModeWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "启用 Root 模式",
                color = colors.error,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "Root 模式将允许应用使用更高级的系统权限。",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "警告：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("Root 权限可能导致系统不稳定")
                BulletPoint("不当操作可能损坏设备数据")
                BulletPoint("请确保您了解 Root 权限的风险")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "仅在您完全了解风险并需要高级功能时才启用此选项。",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error)
            ) {
                Text("我了解风险，启用", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

/**
 * su -c 命令警告对话框
 */
@Composable
fun SuCommandWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "允许 su -c 命令",
                color = colors.error,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "此选项将允许 AI 执行 su -c 命令，这意味着 AI 可以以 Root 权限执行任意 Shell 命令。",
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "极度危险：",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint("AI 可能执行危险的系统命令")
                BulletPoint("可能导致数据丢失或系统损坏")
                BulletPoint("可能被恶意指令利用")
                BulletPoint("不建议在日常使用中启用")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "强烈建议：仅在完全可控的测试环境中使用，并在使用完毕后立即关闭。",
                    fontSize = 13.sp,
                    color = colors.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error)
            ) {
                Text("我了解风险，启用", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun ControlModeDialog(
    currentMode: ControlMode,
    onDismiss: () -> Unit,
    onSelect: (ControlMode) -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择控制方式", color = colors.textPrimary)
        },
        text = {
            Column {
                listOf(
                    ControlMode.SHIZUKU to "Shizuku",
                    ControlMode.ACCESSIBILITY to "无障碍",
                    ControlMode.ADB to "ADB（无线调试）"
                ).forEach { (mode, label) ->
                    val isSelected = mode == currentMode
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onSelect(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(2.dp, colors.textHint, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = if (isSelected) colors.primary else colors.textPrimary
                            )
                        }
                    }
                }
                Text(
                    text = "Shizuku 模式通过 Shizuku 执行 shell 命令；无障碍模式通过无障碍服务执行点击、滑动、返回、输入与截图（截图需 Android 11+），无需 Shizuku；ADB 模式通过无线调试直连本机 ADB 执行 shell 命令，首次使用需配对一次（见下方的 ADB 设置）。无障碍服务开启时，任意模式下都可使用元素序号点选。",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun SystemPromptDialog(
    currentPrompt: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    var prompt by remember { mutableStateOf(currentPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("系统提示词", color = colors.textPrimary)
        },
        text = {
            Column {
                Text(
                    text = "在这里填写角色设定或系统提示词，Agent 会以这个角色的身份操控手机执行任务，并作为最高优先级指令加入每次任务。留空则使用默认提示词。",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundInput)
                        .padding(4.dp)
                ) {
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
                TextButton(onClick = { prompt = "" }) {
                    Text("恢复默认", fontSize = 13.sp, color = colors.textHint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(prompt.trim()) }) {
                Text("保存", color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )
}

@Composable
fun LongTermMemoryDialog(
    memory: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("长期记忆", color = colors.textPrimary)
        },
        text = {
            Text(
                text = if (memory.isBlank()) {
                    "暂无记录。每次任务结束后，任务指令、结果和关键过程会自动记入长期记忆，下次任务时带入上下文，让角色记得你们之前的互动。记忆写满后会自动用模型压缩：最新约 10% 保留原文，其余压成摘要。"
                } else {
                    "已保存约 ${memory.length} 字的互动记忆，下次任务时会自动带入上下文。记忆攒到 8000 字后会自动用模型压缩：其中最新 10% 保留原文，其余压成摘要，重要的事不会丢。清除后角色将不再记得之前的互动。"
                },
                fontSize = 14.sp,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            if (memory.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text("清除记忆", color = colors.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = colors.textSecondary)
            }
        }
    )
}

/**
 * ADB（无线调试）设置卡片：首次配对、连接/断开与状态显示。
 * 配对只需一次：开发者选项 → 无线调试 → 使用配对码配对设备，把端口和配对码填进来。
 * 配对后点连接即可（端口自动搜索；失败时填无线调试主页的连接端口再连）。
 */
@Composable
fun AdbSettingsCard() {
    val colors = BaoziTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        AdbBridge.init(context)
        connected = withContext(Dispatchers.IO) { AdbBridge.isConnected() }
    }

    fun runAction(okMsg: String, failMsg: String, action: () -> Boolean) {
        if (busy) return
        busy = true
        message = "处理中…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) { action() }
            connected = withContext(Dispatchers.IO) { AdbBridge.isConnected() }
            message = if (ok) okMsg else failMsg
            busy = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.backgroundCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (connected) Color(0xFF2FBF71) else colors.textHint)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (connected) "ADB 已连接" else "ADB 未连接",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "使用前：打开「开发者选项 → 无线调试」。点「使用配对码配对设备」，把弹窗里的端口和 6 位配对码填到下面配对（只需一次）；之后点「连接」即可，任务开始时也会自动重连。需要 Android 11 及以上。",
                fontSize = 13.sp,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = pairPort,
                onValueChange = { pairPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("配对端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = pairCode,
                onValueChange = { pairCode = it.filter { ch -> ch.isDigit() }.take(6) },
                label = { Text("配对码（6 位）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val port = pairPort.toIntOrNull()
                    if (port == null || pairCode.length != 6) {
                        message = "请先填好配对端口和 6 位配对码"
                    } else {
                        runAction("配对成功，点下面的连接就能用了", "配对失败：确认无线调试已打开、端口和配对码没填错（弹窗关掉就要重新打开）") {
                            AdbBridge.pair(port, pairCode)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("配对")
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = connectPort,
                onValueChange = { connectPort = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("连接端口（可留空，自动搜索）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val port = connectPort.toIntOrNull()
                        if (port != null) {
                            runAction("已连接", "连接失败：确认无线调试开着、端口填的是无线调试主页的端口") {
                                AdbBridge.connect(port)
                            }
                        } else {
                            runAction("已连接", "自动搜索连接失败：把无线调试主页的端口填进来再连") {
                                AdbBridge.connectNow(20000)
                            }
                        }
                    },
                    enabled = !busy && !connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("连接")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        runAction("已断开", "已断开") {
                            AdbBridge.disconnect()
                            true
                        }
                    },
                    enabled = !busy && connected,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("断开")
                }
            }
            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}
