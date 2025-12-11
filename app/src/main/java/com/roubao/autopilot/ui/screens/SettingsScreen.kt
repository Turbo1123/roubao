package com.roubao.autopilot.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.roubao.autopilot.data.AppSettings
import com.roubao.autopilot.data.ProviderConfig
import com.roubao.autopilot.ui.theme.BaoziTheme
import com.roubao.autopilot.ui.theme.ThemeMode
import com.roubao.autopilot.utils.CrashHandler

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAddProvider: (String, String, String) -> Unit,
    onUpdateProvider: (String, String, String, String) -> Unit,
    onRemoveProvider: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onAddModelsToProvider: (String, List<String>) -> Unit,
    onRemoveModelFromProvider: (String, String) -> Unit,
    onSelectModel: (String) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateMaxSteps: (Int) -> Unit,
    shizukuAvailable: Boolean,
    onFetchModels: ((String, String, (List<String>) -> Unit, (String) -> Unit) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMaxStepsDialog by remember { mutableStateOf(false) }
    var showProviderListDialog by remember { mutableStateOf(false) }
    var showModelSelectDialog by remember { mutableStateOf(false) }
    var showShizukuHelpDialog by remember { mutableStateOf(false) }
    var showOverlayHelpDialog by remember { mutableStateOf(false) }

    val activeProvider = settings.providers.find { it.id == settings.activeProviderId }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 顶部标题
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        text = "设置",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary
                    )
                    Text(
                        text = "配置 API 和应用选项",
                        fontSize = 14.sp,
                        color = colors.textSecondary
                    )
                }
            }
        }

        // 连接状态卡片
        item {
            StatusCard(shizukuAvailable = shizukuAvailable)
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

        // API 设置分组
        item {
            SettingsSection(title = "API 配置")
        }

        // API 服务商
        item {
            SettingsItem(
                icon = Icons.Default.Settings,
                title = "API 服务商",
                subtitle = activeProvider?.name ?: "未选择",
                onClick = { showProviderListDialog = true }
            )
        }

        // 模型选择 (仅当有 active provider 时显示)
        if (activeProvider != null) {
            item {
                SettingsItem(
                    icon = Icons.Default.Build,
                    title = "模型",
                    subtitle = settings.model.ifEmpty { "未选择" },
                    onClick = { showModelSelectDialog = true }
                )
            }
        }

        // 反馈分组
        item {
            SettingsSection(title = "反馈与调试")
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
                subtitle = "1.0.0",
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

    // 服务商列表对话框
    if (showProviderListDialog) {
        ProviderListDialog(
            providers = settings.providers,
            activeProviderId = settings.activeProviderId,
            onDismiss = { showProviderListDialog = false },
            onSelect = {
                onSelectProvider(it)
                showProviderListDialog = false
            },
            onAdd = onAddProvider,
            onUpdate = onUpdateProvider,
            onRemove = onRemoveProvider,
            onAddModels = onAddModelsToProvider,
            onRemoveModel = onRemoveModelFromProvider,
            onFetchModels = onFetchModels
        )
    }

    // 模型选择对话框
    if (showModelSelectDialog && activeProvider != null) {
        ModelSelectDialog(
            currentModel = settings.model,
            models = activeProvider.models,
            onDismiss = { showModelSelectDialog = false },
            onSelect = {
                onSelectModel(it)
                showModelSelectDialog = false
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
}

@Composable
fun ProviderListDialog(
    providers: List<ProviderConfig>,
    activeProviderId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (String, String, String) -> Unit,
    onUpdate: (String, String, String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddModels: (String, List<String>) -> Unit,
    onRemoveModel: (String, String) -> Unit,
    onFetchModels: ((String, String, (List<String>) -> Unit, (String) -> Unit) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var showEditDialog by remember { mutableStateOf(false) }
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    val editingProvider = remember(providers, editingProviderId) {
        if (editingProviderId != null) {
            providers.find { it.id == editingProviderId }
        } else {
            null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("API 服务商", color = colors.textPrimary)
                IconButton(onClick = {
                    editingProviderId = null
                    showEditDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "添加", tint = colors.primary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                if (providers.isEmpty()) {
                    Text(
                        text = "暂无服务商，请点击右上角添加",
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    providers.forEach { provider ->
                        val isActive = provider.id == activeProviderId
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSelect(provider.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isActive) colors.primary.copy(alpha = 0.15f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = provider.name,
                                        fontSize = 14.sp,
                                        color = if (isActive) colors.primary else colors.textPrimary
                                    )
                                    Text(
                                        text = provider.baseUrl,
                                        fontSize = 11.sp,
                                        color = colors.textSecondary,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = {
                                    editingProviderId = provider.id
                                    showEditDialog = true
                                }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "编辑",
                                        tint = colors.textHint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
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

    if (showEditDialog) {
        // 使用局部不可变引用以便 smart cast
        val providerToEdit = editingProvider
        ProviderEditDialog(
            provider = providerToEdit,
            onDismiss = { showEditDialog = false },
            onConfirm = { name, url, key ->
                if (providerToEdit == null) {
                    onAdd(name, url, key)
                } else {
                    onUpdate(providerToEdit.id, name, url, key)
                }
                showEditDialog = false
            },
            onDelete = providerToEdit?.let { provider ->
                {
                    onRemove(provider.id)
                    showEditDialog = false
                }
            },
            onAddModels = providerToEdit?.let { provider ->
                { models -> onAddModels(provider.id, models) }
            },
            onRemoveModel = providerToEdit?.let { provider ->
                { model -> onRemoveModel(provider.id, model) }
            },
            onFetchModels = onFetchModels
        )
    }
}

@Composable
fun ProviderEditDialog(
    provider: ProviderConfig?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onAddModels: ((List<String>) -> Unit)?,
    onRemoveModel: ((String) -> Unit)?,
    onFetchModels: ((String, String, (List<String>) -> Unit, (String) -> Unit) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var name by remember { mutableStateOf(provider?.name ?: "") }
    var url by remember { mutableStateOf(provider?.baseUrl ?: "") }
    var key by remember { mutableStateOf(provider?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var showModelManager by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text(if (provider == null) "添加服务商" else "编辑服务商", color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Name
                Text("名称", fontSize = 12.sp, color = colors.textHint, modifier = Modifier.padding(bottom = 4.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.backgroundInput)
                        .padding(12.dp),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                // URL
                Text("Base URL", fontSize = 12.sp, color = colors.textHint, modifier = Modifier.padding(bottom = 4.dp))
                BasicTextField(
                    value = url,
                    onValueChange = { url = it },
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.backgroundInput)
                        .padding(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Key
                Text("API Key", fontSize = 12.sp, color = colors.textHint, modifier = Modifier.padding(bottom = 4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.backgroundInput)
                        .padding(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = key,
                            onValueChange = { key = it },
                            textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                            cursorBrush = SolidColor(colors.primary),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp),
                            singleLine = true
                        )
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Default.Close else Icons.Default.Lock, // 简化图标
                                contentDescription = null,
                                tint = colors.textHint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (provider != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showModelManager = true },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.backgroundInput),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("管理模型 (${provider.models.size})", color = colors.textPrimary)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = colors.error)
                    }
                }
                TextButton(
                    onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(name, url, key) },
                    enabled = name.isNotBlank() && url.isNotBlank()
                ) {
                    Text("保存", color = if (name.isNotBlank() && url.isNotBlank()) colors.primary else colors.textHint)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = colors.textSecondary)
            }
        }
    )

    if (showModelManager && provider != null && onAddModels != null && onRemoveModel != null) {
        ModelManagerDialog(
            models = provider.models,
            onDismiss = { showModelManager = false },
            onAddModels = onAddModels,
            onRemove = onRemoveModel,
            onFetchModels = if (onFetchModels != null) {
                { onSuccess, onError -> onFetchModels(url, key, onSuccess, onError) }
            } else null
        )
    }
}

@Composable
fun ModelManagerDialog(
    models: List<String>,
    onDismiss: () -> Unit,
    onAddModels: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onFetchModels: (( (List<String>) -> Unit, (String) -> Unit ) -> Unit)? = null
) {
    val colors = BaoziTheme.colors
    var newModel by remember { mutableStateOf("") }
    var isFetching by remember { mutableStateOf(false) }
    var showFetchDialog by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = { Text("管理模型", color = colors.textPrimary) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Add Model Input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    BasicTextField(
                        value = newModel,
                        onValueChange = { newModel = it },
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.backgroundInput)
                            .padding(12.dp),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (newModel.isEmpty()) {
                                Text("输入模型名称", color = colors.textHint, fontSize = 14.sp)
                            }
                            innerTextField()
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (newModel.isNotBlank()) {
                                onAddModels(listOf(newModel.trim()))
                                newModel = ""
                            }
                        },
                        enabled = newModel.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "添加", tint = if (newModel.isNotBlank()) colors.primary else colors.textHint)
                    }
                }

                // Fetch Button
                if (onFetchModels != null) {
                    Button(
                        onClick = {
                            isFetching = true
                            onFetchModels(
                                { models ->
                                    isFetching = false
                                    fetchedModels = models
                                    showFetchDialog = true
                                },
                                { _ ->
                                    isFetching = false
                                    // 这里可以显示 Toast，但 Compose 中需要 Context
                                }
                            )
                        },
                        enabled = !isFetching,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在获取...", color = Color.White)
                        } else {
                            Text("从 API 获取模型列表", color = Color.White)
                        }
                    }
                }

                // Model List
                if (models.isEmpty()) {
                    Text("暂无模型", color = colors.textSecondary, fontSize = 14.sp)
                } else {
                    models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(model, color = colors.textPrimary, fontSize = 14.sp)
                            IconButton(
                                onClick = { onRemove(model) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "删除", tint = colors.textHint, modifier = Modifier.size(16.dp))
                            }
                        }
                        Divider(color = colors.backgroundInput, thickness = 0.5.dp)
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

    if (showFetchDialog) {
        FetchedModelsDialog(
            models = fetchedModels,
            existingModels = models,
            onDismiss = { 
                showFetchDialog = false
                fetchedModels = emptyList()
            },
            onSelect = { selectedModels ->
                showFetchDialog = false
                fetchedModels = emptyList()
                onAddModels(selectedModels)
            }
        )
    }
}

@Composable
fun FetchedModelsDialog(
    models: List<String>,
    existingModels: List<String>,
    onDismiss: () -> Unit,
    onSelect: (List<String>) -> Unit
) {
    val colors = BaoziTheme.colors
    val selectedModels = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = { Text("选择要添加的模型", color = colors.textPrimary) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(models.size) { index ->
                    val model = models[index]
                    val isExisting = existingModels.contains(model)
                    val isSelected = selectedModels.contains(model)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isExisting) {
                                if (isSelected) {
                                    selectedModels.remove(model)
                                } else {
                                    selectedModels.add(model)
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected || isExisting,
                            onCheckedChange = { checked ->
                                if (!isExisting) {
                                    if (checked) selectedModels.add(model) else selectedModels.remove(model)
                                }
                            },
                            enabled = !isExisting,
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.primary,
                                uncheckedColor = colors.textHint,
                                disabledCheckedColor = colors.textHint.copy(alpha = 0.5f)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = model,
                            color = if (isExisting) colors.textHint else colors.textPrimary,
                            fontSize = 14.sp
                        )
                        if (isExisting) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text("已添加", color = colors.textHint, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(selectedModels.toList()) },
                enabled = selectedModels.isNotEmpty()
            ) {
                Text("添加 (${selectedModels.size})", color = if (selectedModels.isNotEmpty()) colors.primary else colors.textHint)
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
fun ModelSelectDialog(
    currentModel: String,
    models: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val colors = BaoziTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundCard,
        title = {
            Text("选择模型", color = colors.textPrimary)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (models.isEmpty()) {
                    Text("该服务商暂无模型，请先在服务商设置中添加模型。", color = colors.textSecondary, fontSize = 14.sp)
                } else {
                    models.forEach { model ->
                        val isSelected = model == currentModel
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSelect(model) },
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

@Composable
fun StatusCard(shizukuAvailable: Boolean) {
    val colors = BaoziTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (shizukuAvailable) colors.success.copy(alpha = 0.15f) else colors.error.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (shizukuAvailable) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = null,
                tint = if (shizukuAvailable) colors.success else colors.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = if (shizukuAvailable) "Shizuku 已连接" else "Shizuku 未连接",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (shizukuAvailable) colors.success else colors.error
                )
                Text(
                    text = if (shizukuAvailable) "设备控制功能可用" else "请启动 Shizuku 并授权",
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
fun SettingsSection(title: String) {
    val colors = BaoziTheme.colors
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = colors.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
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
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    maxLines = 1
                )
            }
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
                    text = "设置 Agent 单次任务的最大执行步数。步数越多，能完成的任务越复杂，但消耗的 token 也越多。",
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
                    valueRange = 5f..100f,
                    steps = 18, // (100-5)/5 - 1 = 18 个刻度点，每 5 步一个
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
                        text = "100",
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
                    listOf(15, 25, 50).forEach { preset ->
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
