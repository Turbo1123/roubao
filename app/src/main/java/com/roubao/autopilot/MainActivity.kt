package com.roubao.autopilot

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import android.net.Uri
import android.provider.Settings
import com.roubao.autopilot.agent.MobileAgent
import com.roubao.autopilot.controller.AppScanner
import com.roubao.autopilot.controller.DeviceController
import com.roubao.autopilot.data.*
import com.roubao.autopilot.ui.screens.*
import com.roubao.autopilot.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.roubao.autopilot.vlm.VLMClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.util.Log

private const val TAG = "MainActivity"

sealed class Screen(val route: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    object Home : Screen("home", "肉包", Icons.Outlined.Home, Icons.Filled.Home)
    object Capabilities : Screen("capabilities", "能力", Icons.Outlined.Star, Icons.Filled.Star)
    object History : Screen("history", "记录", Icons.Outlined.List, Icons.Filled.List)
    object Settings : Screen("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings)
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceController: DeviceController
    private lateinit var settingsManager: SettingsManager
    private lateinit var executionRepository: ExecutionRepository

    private val mobileAgent = mutableStateOf<MobileAgent?>(null)
    private var shizukuAvailable = mutableStateOf(false)

    // 当前执行的协程 Job（用于停止任务）
    private var currentExecutionJob: kotlinx.coroutines.Job? = null

    // 执行记录列表
    private val executionRecords = mutableStateOf<List<ExecutionRecord>>(emptyList())

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        shizukuAvailable.value = true
        if (checkShizukuPermission()) {
            Log.d(TAG, "Shizuku permission granted, binding service")
            deviceController.bindService()
        } else {
            Log.d(TAG, "Shizuku permission not granted")
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        shizukuAvailable.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        Log.d(TAG, "Shizuku permission result: $grantResult")
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            deviceController.bindService()
            Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 设置边到边显示，深色状态栏和导航栏
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        deviceController = DeviceController(this)
        deviceController.setCacheDir(cacheDir)
        settingsManager = SettingsManager(this)
        executionRepository = ExecutionRepository(this)

        // 加载执行记录
        lifecycleScope.launch {
            executionRecords.value = executionRepository.getAllRecords()
        }

        // 添加 Shizuku 监听器
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        // 检查 Shizuku 状态
        checkAndUpdateShizukuStatus()

        // 预加载已安装应用
        lifecycleScope.launch(Dispatchers.IO) {
            AppScanner(this@MainActivity).getApps()
        }

        setContent {
            val settings by settingsManager.settings.collectAsState()
            BaoziTheme(themeMode = settings.themeMode) {
                val colors = BaoziTheme.colors
                // 动态更新系统栏颜色
                SideEffect {
                    val window = this@MainActivity.window
                    window.statusBarColor = colors.background.toArgb()
                    window.navigationBarColor = colors.backgroundCard.toArgb()
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !colors.isDark
                        isAppearanceLightNavigationBars = !colors.isDark
                    }
                }

                // 首次启动显示引导画面
                if (!settings.hasSeenOnboarding) {
                    OnboardingScreen(
                        onComplete = {
                            settingsManager.setOnboardingSeen()
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainApp() {
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
        var selectedRecord by remember { mutableStateOf<ExecutionRecord?>(null) }
        var showShizukuHelpDialog by remember { mutableStateOf(false) }
        var hasShownShizukuHelp by remember { mutableStateOf(false) }

        val settings by settingsManager.settings.collectAsState()
        val colors = BaoziTheme.colors
        val agent = mobileAgent.value
        val agentState by agent?.state?.collectAsState() ?: remember { mutableStateOf(null) }
        val logs by agent?.logs?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val records by remember { executionRecords }
        val isShizukuAvailable = shizukuAvailable.value && checkShizukuPermission()

        // 首次进入且 Shizuku 未连接时，显示帮助引导（只显示一次）
        LaunchedEffect(Unit) {
            if (!isShizukuAvailable && settings.hasSeenOnboarding && !hasShownShizukuHelp) {
                hasShownShizukuHelp = true
                showShizukuHelpDialog = true
            }
        }

        Scaffold(
            modifier = Modifier.background(colors.background),
            containerColor = colors.background,
            bottomBar = {
                if (selectedRecord == null) {
                    NavigationBar(
                        containerColor = colors.background,
                        contentColor = colors.textPrimary,
                        tonalElevation = 0.dp
                    ) {
                        listOf(Screen.Home, Screen.Capabilities, Screen.History, Screen.Settings).forEach { screen ->
                            val selected = currentScreen == screen
                            NavigationBarItem(
                                icon = {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.icon,
                                        contentDescription = screen.title
                                    )
                                },
                                label = { Text(screen.title) },
                                selected = selected,
                                onClick = { currentScreen = screen },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = if (colors.isDark) colors.textPrimary else Color.White,
                                    selectedTextColor = colors.primary,
                                    unselectedIconColor = colors.textSecondary,
                                    unselectedTextColor = colors.textSecondary,
                                    indicatorColor = colors.primary
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 处理系统返回手势
                BackHandler(enabled = selectedRecord != null) {
                    selectedRecord = null
                }

                // 详情页优先显示
                if (selectedRecord != null) {
                    HistoryDetailScreen(
                        record = selectedRecord!!,
                        onBack = { selectedRecord = null }
                    )
                } else {
                    // 主页面切换
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "screen"
                    ) { screen ->
                        when (screen) {
                            Screen.Home -> {
                                // 每次进入首页都检测 Shizuku 状态
                                LaunchedEffect(Unit) {
                                    checkAndUpdateShizukuStatus()
                                }
                                HomeScreen(
                                    agentState = agentState,
                                    logs = logs,
                                    onExecute = { instruction ->
                                        runAgent(instruction, settings.apiKey, settings.baseUrl, settings.model, settings.maxSteps)
                                    },
                                    onStop = { mobileAgent.value?.stop() },
                                    shizukuAvailable = isShizukuAvailable,
                                    onRefreshShizuku = { refreshShizukuStatus() },
                                    onShizukuRequired = { showShizukuHelpDialog = true }
                                )
                            }
                            Screen.Capabilities -> CapabilitiesScreen()
                            Screen.History -> HistoryScreen(
                                records = records,
                                onRecordClick = { record -> selectedRecord = record },
                                onDeleteRecord = { id -> deleteRecord(id) }
                            )
                            Screen.Settings -> SettingsScreen(
                                settings = settings,
                                onAddProvider = { name, url, key -> settingsManager.addProvider(name, url, key) },
                                onUpdateProvider = { id, name, url, key -> settingsManager.updateProvider(id, name, url, key) },
                                onRemoveProvider = { id -> settingsManager.removeProvider(id) },
                                onSelectProvider = { id -> settingsManager.setActiveProvider(id) },
                                onAddModelsToProvider = { providerId, models -> settingsManager.addModelsToProvider(providerId, models) },
                                onRemoveModelFromProvider = { providerId, model -> settingsManager.removeModelFromProvider(providerId, model) },
                                onSelectModel = { model -> settingsManager.updateModel(model) },
                                onUpdateThemeMode = { settingsManager.updateThemeMode(it) },
                                onUpdateMaxSteps = { settingsManager.updateMaxSteps(it) },
                                shizukuAvailable = isShizukuAvailable,
                                onFetchModels = { url, key, onSuccess, onError ->
                                    lifecycleScope.launch {
                                        val result = VLMClient.fetchModels(url, key)
                                        result.onSuccess { models ->
                                            onSuccess(models)
                                        }.onFailure { error ->
                                            onError(error.message ?: "Unknown error")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Shizuku 帮助对话框
        if (showShizukuHelpDialog) {
            ShizukuHelpDialog(onDismiss = { showShizukuHelpDialog = false })
        }
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            executionRepository.deleteRecord(id)
            executionRecords.value = executionRepository.getAllRecords()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        deviceController.unbindService()
    }

    private fun checkShizukuPermission(): Boolean {
        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "checkShizukuPermission: $granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "checkShizukuPermission error", e)
            false
        }
    }

    private fun checkAndUpdateShizukuStatus() {
        Log.d(TAG, "checkAndUpdateShizukuStatus called")
        try {
            val binderAlive = Shizuku.pingBinder()
            Log.d(TAG, "Shizuku pingBinder: $binderAlive")

            if (binderAlive) {
                shizukuAvailable.value = true
                val hasPermission = checkShizukuPermission()
                Log.d(TAG, "Shizuku hasPermission: $hasPermission")

                if (hasPermission) {
                    Log.d(TAG, "Binding Shizuku service")
                    deviceController.bindService()
                } else {
                    Log.d(TAG, "Requesting Shizuku permission")
                    requestShizukuPermission()
                }
            } else {
                Log.d(TAG, "Shizuku binder not alive")
                shizukuAvailable.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkAndUpdateShizukuStatus error", e)
            shizukuAvailable.value = false
        }
    }

    private fun refreshShizukuStatus() {
        Log.d(TAG, "refreshShizukuStatus called by user")
        Toast.makeText(this, "正在检查 Shizuku 状态...", Toast.LENGTH_SHORT).show()
        checkAndUpdateShizukuStatus()

        if (shizukuAvailable.value && checkShizukuPermission()) {
            Toast.makeText(this, "Shizuku 已连接", Toast.LENGTH_SHORT).show()
        } else if (shizukuAvailable.value) {
            Toast.makeText(this, "请在弹窗中授权 Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku 版本过低", Toast.LENGTH_SHORT).show()
                return
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
                shizukuAvailable.value = true
                deviceController.bindService()
                return
            }

            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Toast.makeText(this, "请先启动 Shizuku App", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runAgent(instruction: String, apiKey: String, baseUrl: String, model: String, maxSteps: Int) {
        if (instruction.isBlank()) {
            Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show()
            return
        }
        if (apiKey.isBlank()) {
            Toast.makeText(this, "请输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val vlmClient = VLMClient(
            apiKey = apiKey,
            baseUrl = baseUrl.ifBlank { "https://dashscope.aliyuncs.com/compatible-mode/v1" },
            model = model.ifBlank { "qwen3-vl-plus" }
        )

        mobileAgent.value = MobileAgent(vlmClient, deviceController, this)

        // 设置停止回调，用于取消协程
        mobileAgent.value?.onStopRequested = {
            currentExecutionJob?.cancel()
            currentExecutionJob = null
        }

        // 创建执行记录
        val record = ExecutionRecord(
            title = generateTitle(instruction),
            instruction = instruction,
            startTime = System.currentTimeMillis(),
            status = ExecutionStatus.RUNNING
        )

        // 取消之前的任务（如果有）
        currentExecutionJob?.cancel()

        currentExecutionJob = lifecycleScope.launch {
            // 保存初始记录
            executionRepository.saveRecord(record)
            executionRecords.value = executionRepository.getAllRecords()

            try {
                val result = mobileAgent.value!!.runInstruction(instruction, maxSteps)

                // 更新记录状态
                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = if (result.success) ExecutionStatus.COMPLETED else ExecutionStatus.FAILED,
                    steps = steps,
                    resultMessage = result.message
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消任务
                val agentState = mobileAgent.value?.state?.value
                val steps = agentState?.executionSteps ?: emptyList()

                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.STOPPED,
                    steps = steps,
                    resultMessage = "用户停止"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, "任务已停止", Toast.LENGTH_SHORT).show()
                mobileAgent.value?.clearLogs()
                // 重新抛出取消异常（协程规范）
                throw e
            } catch (e: Exception) {
                // 更新失败记录
                val updatedRecord = record.copy(
                    endTime = System.currentTimeMillis(),
                    status = ExecutionStatus.FAILED,
                    resultMessage = "错误: ${e.message}"
                )
                executionRepository.saveRecord(updatedRecord)
                executionRecords.value = executionRepository.getAllRecords()

                Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()

                // 延迟3秒后清空日志，恢复默认状态
                kotlinx.coroutines.delay(3000)
                mobileAgent.value?.clearLogs()
            }
        }
    }

    private fun generateTitle(instruction: String): String {
        // 生成简短标题
        val keywords = listOf(
            "打开" to "打开应用",
            "点" to "点餐",
            "发" to "发送消息",
            "看" to "浏览内容",
            "搜" to "搜索",
            "设置" to "调整设置",
            "播放" to "播放媒体"
        )
        for ((key, title) in keywords) {
            if (instruction.contains(key)) {
                return title
            }
        }
        return if (instruction.length > 10) instruction.take(10) + "..." else instruction
    }
}
