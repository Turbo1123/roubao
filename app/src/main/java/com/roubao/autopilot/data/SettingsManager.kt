package com.roubao.autopilot.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.roubao.autopilot.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * API 提供商配置
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val isGUIAgent: Boolean = false  // 是否为 GUI Agent 专用协议（非 OpenAI 兼容）
) {
    companion object {
        val GUI_OWL = ApiProvider(
            id = "gui_owl",
            name = "GUI-Owl (阿里云)",
            baseUrl = "https://dashscope.aliyuncs.com/api/v2/apps/gui-owl/gui_agent_server",
            defaultModel = "pre-gui_owl_7b",
            isGUIAgent = true
        )
        val MAI_UI = ApiProvider(
            id = "mai_ui",
            name = "MAI-UI (本地部署)",
            baseUrl = "http://localhost:8000/v1",  // vLLM 默认地址
            defaultModel = "MAI-UI-2B"  // 支持 MAI-UI-2B 或 MAI-UI-8B
        )
        val ALIYUN = ApiProvider(
            id = "aliyun",
            name = "阿里云 (Qwen-VL)",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3-vl-plus"
        )
        val OPENAI = ApiProvider(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o"
        )
        val OPENROUTER = ApiProvider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "anthropic/claude-3.5-sonnet"
        )
        val CUSTOM = ApiProvider(
            id = "custom",
            name = "自定义",
            baseUrl = "",
            defaultModel = ""
        )

        val ALL = listOf(GUI_OWL, MAI_UI, ALIYUN, OPENAI, OPENROUTER, CUSTOM)
    }
}

/**
 * 服务商配置（每个服务商独立保存）
 */
data class ProviderConfig(
    val apiKey: String = "",
    val model: String = "",
    val cachedModels: List<String> = emptyList(),
    val customBaseUrl: String = ""  // 仅 custom 服务商使用
)

/**
 * 默认推荐模型
 */
const val DEFAULT_MODEL = "qwen3-vl-plus"

/**
 * 设备控制方式
 */
enum class ControlMode {
    SHIZUKU,        // 通过 Shizuku 执行 shell 命令（默认）
    ACCESSIBILITY,  // 通过无障碍服务执行手势与全局操作
    ADB             // 通过本机 ADB（无线调试自连）执行 shell 命令
}

/**
 * 应用设置
 */
data class AppSettings(
    val currentProviderId: String = ApiProvider.ALIYUN.id,  // 当前选中的服务商
    val providerConfigs: Map<String, ProviderConfig> = emptyMap(),  // 每个服务商的配置
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasSeenOnboarding: Boolean = false,
    val maxSteps: Int = 25,
    val cloudCrashReportEnabled: Boolean = true,
    val rootModeEnabled: Boolean = false,
    val suCommandEnabled: Boolean = false,
    val controlMode: ControlMode = ControlMode.SHIZUKU,  // 设备控制方式
    val customSystemPrompt: String = "",  // 自定义系统提示词（为空时使用内置默认提示词）
    val longTermMemory: String = ""  // 跨会话长期记忆（之前任务的指令与过程摘要）
) {
    // 便捷属性：获取当前服务商的配置
    val currentConfig: ProviderConfig
        get() = providerConfigs[currentProviderId] ?: ProviderConfig()

    val currentProvider: ApiProvider
        get() = ApiProvider.ALL.find { it.id == currentProviderId } ?: ApiProvider.ALIYUN

    val apiKey: String get() = currentConfig.apiKey
    val model: String get() = currentConfig.model.ifEmpty { currentProvider.defaultModel }
    val cachedModels: List<String> get() = currentConfig.cachedModels

    val baseUrl: String
        get() = when {
            currentProviderId == "custom" -> currentConfig.customBaseUrl
            // MAI-UI 支持自定义 URL（用于远程部署）
            currentProviderId == "mai_ui" && currentConfig.customBaseUrl.isNotEmpty() -> currentConfig.customBaseUrl
            else -> currentProvider.baseUrl
        }
}

/**
 * 设置管理器
 */
class SettingsManager(context: Context) {

    // 普通设置存储
    private val prefs: SharedPreferences =
        context.getSharedPreferences("baozi_settings", Context.MODE_PRIVATE)

    // 加密存储（用于敏感数据如 API Key）
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "baozi_secure_settings",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 加密失败时回退到普通存储（不应该发生）
            android.util.Log.e("SettingsManager", "Failed to create encrypted prefs", e)
            prefs
        }
    }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    init {
        // 迁移旧的明文 API Key 到加密存储
        migrateApiKeyToSecureStorage()
    }

    /**
     * 迁移旧的明文 API Key 到加密存储
     */
    private fun migrateApiKeyToSecureStorage() {
        val oldApiKey = prefs.getString("api_key", null)
        if (!oldApiKey.isNullOrEmpty()) {
            // 保存到加密存储
            securePrefs.edit().putString("api_key", oldApiKey).apply()
            // 删除旧的明文存储
            prefs.edit().remove("api_key").apply()
            android.util.Log.d("SettingsManager", "API Key migrated to secure storage")
        }
    }

    private fun loadSettings(): AppSettings {
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }

        // 加载当前选中的服务商
        val currentProviderId = prefs.getString("current_provider_id", ApiProvider.ALIYUN.id) ?: ApiProvider.ALIYUN.id

        // 加载每个服务商的配置
        val providerConfigs = mutableMapOf<String, ProviderConfig>()
        for (provider in ApiProvider.ALL) {
            val config = loadProviderConfig(provider.id)
            providerConfigs[provider.id] = config
        }

        // 迁移旧数据（如果有）
        val oldApiKey = securePrefs.getString("api_key", null)
        val oldModel = prefs.getString("model", null)
        val oldBaseUrl = prefs.getString("base_url", null)
        val oldCachedModels = prefs.getStringSet("cached_models", null)

        if (oldApiKey != null || oldModel != null) {
            // 找到旧数据对应的服务商
            val oldProviderId = when (oldBaseUrl) {
                ApiProvider.ALIYUN.baseUrl -> ApiProvider.ALIYUN.id
                ApiProvider.OPENAI.baseUrl -> ApiProvider.OPENAI.id
                ApiProvider.OPENROUTER.baseUrl -> ApiProvider.OPENROUTER.id
                else -> "custom"
            }

            // 迁移到新格式
            val migratedConfig = ProviderConfig(
                apiKey = oldApiKey ?: "",
                model = oldModel ?: "",
                cachedModels = oldCachedModels?.toList() ?: emptyList(),
                customBaseUrl = if (oldProviderId == "custom") oldBaseUrl ?: "" else ""
            )
            providerConfigs[oldProviderId] = migratedConfig
            saveProviderConfig(oldProviderId, migratedConfig)

            // 清除旧数据
            securePrefs.edit().remove("api_key").apply()
            prefs.edit()
                .remove("model")
                .remove("base_url")
                .remove("cached_models")
                .putString("current_provider_id", oldProviderId)
                .apply()

            android.util.Log.d("SettingsManager", "Migrated old settings to provider: $oldProviderId")
        }

        return AppSettings(
            currentProviderId = currentProviderId,
            providerConfigs = providerConfigs,
            themeMode = themeMode,
            hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false),
            maxSteps = prefs.getInt("max_steps", 25),
            cloudCrashReportEnabled = prefs.getBoolean("cloud_crash_report_enabled", true),
            rootModeEnabled = prefs.getBoolean("root_mode_enabled", false),
            suCommandEnabled = prefs.getBoolean("su_command_enabled", false),
            controlMode = try {
                ControlMode.valueOf(
                    prefs.getString("control_mode", ControlMode.SHIZUKU.name) ?: ControlMode.SHIZUKU.name
                )
            } catch (e: Exception) {
                ControlMode.SHIZUKU
            },
            customSystemPrompt = prefs.getString("custom_system_prompt", "") ?: "",
            longTermMemory = prefs.getString("long_term_memory", "") ?: ""
        )
    }

    /**
     * 加载指定服务商的配置
     */
    private fun loadProviderConfig(providerId: String): ProviderConfig {
        val prefix = "provider_${providerId}_"
        return ProviderConfig(
            apiKey = securePrefs.getString("${prefix}api_key", "") ?: "",
            model = prefs.getString("${prefix}model", "") ?: "",
            cachedModels = prefs.getStringSet("${prefix}cached_models", emptySet())?.toList() ?: emptyList(),
            customBaseUrl = prefs.getString("${prefix}custom_base_url", "") ?: ""
        )
    }

    /**
     * 保存指定服务商的配置
     */
    private fun saveProviderConfig(providerId: String, config: ProviderConfig) {
        val prefix = "provider_${providerId}_"
        securePrefs.edit().putString("${prefix}api_key", config.apiKey).apply()
        prefs.edit()
            .putString("${prefix}model", config.model)
            .putStringSet("${prefix}cached_models", config.cachedModels.toSet())
            .putString("${prefix}custom_base_url", config.customBaseUrl)
            .apply()
    }

    /**
     * 更新当前服务商的配置
     */
    private fun updateCurrentConfig(update: (ProviderConfig) -> ProviderConfig) {
        val currentId = _settings.value.currentProviderId
        val currentConfig = _settings.value.currentConfig
        val newConfig = update(currentConfig)

        saveProviderConfig(currentId, newConfig)

        val newConfigs = _settings.value.providerConfigs.toMutableMap()
        newConfigs[currentId] = newConfig
        _settings.value = _settings.value.copy(providerConfigs = newConfigs)
    }

    fun updateApiKey(apiKey: String) {
        updateCurrentConfig { it.copy(apiKey = apiKey) }
    }

    fun updateBaseUrl(baseUrl: String) {
        // 自定义服务商和 MAI-UI 可以修改 URL
        val providerId = _settings.value.currentProviderId
        if (providerId == "custom" || providerId == "mai_ui") {
            updateCurrentConfig { it.copy(customBaseUrl = baseUrl) }
        }
    }

    fun updateModel(model: String) {
        updateCurrentConfig { it.copy(model = model) }
    }

    /**
     * 更新缓存的模型列表（从 API 获取后调用）
     */
    fun updateCachedModels(models: List<String>) {
        val distinctModels = models.distinct()
        updateCurrentConfig { it.copy(cachedModels = distinctModels) }
    }

    /**
     * 清空缓存的模型列表
     */
    fun clearCachedModels() {
        updateCurrentConfig { it.copy(cachedModels = emptyList()) }
    }

    /**
     * 选择服务商（切换时自动加载该服务商的配置）
     */
    fun selectProvider(provider: ApiProvider) {
        prefs.edit().putString("current_provider_id", provider.id).apply()
        _settings.value = _settings.value.copy(currentProviderId = provider.id)
    }

    /**
     * 获取当前服务商
     */
    fun getCurrentProvider(): ApiProvider {
        return _settings.value.currentProvider
    }

    /**
     * 判断是否使用自定义 URL
     */
    fun isCustomUrl(): Boolean {
        return _settings.value.currentProviderId == "custom"
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        prefs.edit().putString("theme_mode", themeMode.name).apply()
        _settings.value = _settings.value.copy(themeMode = themeMode)
    }

    fun setOnboardingSeen() {
        prefs.edit().putBoolean("has_seen_onboarding", true).apply()
        _settings.value = _settings.value.copy(hasSeenOnboarding = true)
    }

    fun updateMaxSteps(maxSteps: Int) {
        val validSteps = maxSteps.coerceIn(5, 100) // 限制范围 5-100
        prefs.edit().putInt("max_steps", validSteps).apply()
        _settings.value = _settings.value.copy(maxSteps = validSteps)
    }

    fun updateCloudCrashReportEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("cloud_crash_report_enabled", enabled).apply()
        _settings.value = _settings.value.copy(cloudCrashReportEnabled = enabled)
    }

    fun updateRootModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("root_mode_enabled", enabled).apply()
        _settings.value = _settings.value.copy(rootModeEnabled = enabled)
        // 关闭 Root 模式时，同时关闭 su -c
        if (!enabled) {
            updateSuCommandEnabled(false)
        }
    }

    fun updateSuCommandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("su_command_enabled", enabled).apply()
        _settings.value = _settings.value.copy(suCommandEnabled = enabled)
    }

    fun updateControlMode(mode: ControlMode) {
        prefs.edit().putString("control_mode", mode.name).apply()
        _settings.value = _settings.value.copy(controlMode = mode)
    }

    fun updateCustomSystemPrompt(prompt: String) {
        prefs.edit().putString("custom_system_prompt", prompt).apply()
        _settings.value = _settings.value.copy(customSystemPrompt = prompt)
    }

    /**
     * 追加一条长期记忆（任务结束后调用）。
     * 攒到 8000 字即触发模型压缩（任务开始时执行：最新 10% 留原文，其余压成摘要）；
     * 这里的 12000 字只是防止极端情况下无限增长的兜底硬顶。
     */
    fun appendLongTermMemory(entry: String) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val current = _settings.value.longTermMemory
        var combined = if (current.isBlank()) trimmed else current + "\n\n" + trimmed
        if (combined.length > 12000) {
            combined = combined.takeLast(12000)
            // 尽量从一条完整记忆的开头截断
            val firstBreak = combined.indexOf("【任务】")
            if (firstBreak > 0) {
                combined = combined.substring(firstBreak)
            }
        }
        prefs.edit().putString("long_term_memory", combined).apply()
        _settings.value = _settings.value.copy(longTermMemory = combined)
    }

    fun clearLongTermMemory() {
        prefs.edit().remove("long_term_memory").apply()
        _settings.value = _settings.value.copy(longTermMemory = "")
    }

    /**
     * 整体替换长期记忆（模型压缩后回写时使用）
     */
    fun updateLongTermMemory(text: String) {
        prefs.edit().putString("long_term_memory", text).apply()
        _settings.value = _settings.value.copy(longTermMemory = text)
    }
}
