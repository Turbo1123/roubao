package com.roubao.autopilot.data

import android.content.Context
import android.content.SharedPreferences
import com.roubao.autopilot.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * API 提供商配置
 */
data class ProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList()
)

/**
 * 应用设置
 */
data class AppSettings(
    // 兼容旧字段，实际上由 activeProvider 决定
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    
    // 新增字段
    val providers: List<ProviderConfig> = emptyList(),
    val activeProviderId: String = "",
    
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasSeenOnboarding: Boolean = false,
    val maxSteps: Int = 25
)

/**
 * 设置管理器
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("baozi_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    companion object {
        private const val KEY_PROVIDERS = "providers_json"
        private const val KEY_ACTIVE_PROVIDER_ID = "active_provider_id"
        
        val DEFAULT_PROVIDERS = listOf(
            ProviderConfig(
                id = "aliyun",
                name = "阿里云 (Qwen-VL)",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "",
                models = listOf("qwen3-vl-plus", "qwen-vl-max", "qwen-vl-plus")
            ),
            ProviderConfig(
                id = "modelscope",
                name = "ModelScope",
                baseUrl = "https://api-inference.modelscope.cn/v1",
                apiKey = "",
                models = listOf("iic/GUI-Owl-7B")
            )
        )
    }

    private fun loadSettings(): AppSettings {
        val themeModeStr = prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val themeMode = try {
            ThemeMode.valueOf(themeModeStr)
        } catch (e: Exception) {
            ThemeMode.DARK
        }

        // 加载 Providers
        val providersJson = prefs.getString(KEY_PROVIDERS, null)
        val providers = if (providersJson != null) {
            deserializeProviders(providersJson)
        } else {
            DEFAULT_PROVIDERS
        }

        // 加载 Active Provider ID
        var activeProviderId = prefs.getString(KEY_ACTIVE_PROVIDER_ID, "") ?: ""
        if (activeProviderId.isEmpty() || providers.none { it.id == activeProviderId }) {
            activeProviderId = providers.firstOrNull()?.id ?: ""
        }

        // 获取当前 active provider 的配置
        val activeProvider = providers.find { it.id == activeProviderId }
        
        // 加载当前选中的 model (存储在 prefs 中，key 为 "current_model_{providerId}")
        val currentModel = if (activeProvider != null) {
            prefs.getString("current_model_${activeProvider.id}", activeProvider.models.firstOrNull() ?: "") ?: ""
        } else {
            ""
        }

        return AppSettings(
            apiKey = activeProvider?.apiKey ?: "",
            baseUrl = activeProvider?.baseUrl ?: "",
            model = currentModel,
            providers = providers,
            activeProviderId = activeProviderId,
            themeMode = themeMode,
            hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false),
            maxSteps = prefs.getInt("max_steps", 25)
        )
    }

    private fun saveProviders(providers: List<ProviderConfig>) {
        val json = serializeProviders(providers)
        prefs.edit().putString(KEY_PROVIDERS, json).apply()
    }

    fun addProvider(name: String, baseUrl: String, apiKey: String) {
        val newProvider = ProviderConfig(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = emptyList()
        )
        val newProviders = _settings.value.providers + newProvider
        saveProviders(newProviders)
        
        // 如果是第一个，设为默认
        if (newProviders.size == 1) {
            setActiveProvider(newProvider.id)
        } else {
            _settings.value = _settings.value.copy(providers = newProviders)
        }
    }

    fun updateProvider(id: String, name: String, baseUrl: String, apiKey: String) {
        val currentProviders = _settings.value.providers
        val index = currentProviders.indexOfFirst { it.id == id }
        if (index != -1) {
            val updatedProvider = currentProviders[index].copy(
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey
            )
            val newProviders = currentProviders.toMutableList().apply {
                set(index, updatedProvider)
            }
            saveProviders(newProviders)
            
            // 如果更新的是当前 active provider，需要更新 AppSettings 的顶层字段
            if (id == _settings.value.activeProviderId) {
                _settings.value = _settings.value.copy(
                    providers = newProviders,
                    apiKey = apiKey,
                    baseUrl = baseUrl
                )
            } else {
                _settings.value = _settings.value.copy(providers = newProviders)
            }
        }
    }

    fun removeProvider(id: String) {
        val currentProviders = _settings.value.providers
        val newProviders = currentProviders.filter { it.id != id }
        saveProviders(newProviders)

        if (id == _settings.value.activeProviderId) {
            val nextProvider = newProviders.firstOrNull()
            if (nextProvider != null) {
                setActiveProvider(nextProvider.id)
            } else {
                _settings.value = _settings.value.copy(
                    providers = newProviders,
                    activeProviderId = "",
                    apiKey = "",
                    baseUrl = "",
                    model = ""
                )
                prefs.edit().remove(KEY_ACTIVE_PROVIDER_ID).apply()
            }
        } else {
            _settings.value = _settings.value.copy(providers = newProviders)
        }
    }

    fun setActiveProvider(id: String) {
        val provider = _settings.value.providers.find { it.id == id } ?: return
        
        prefs.edit().putString(KEY_ACTIVE_PROVIDER_ID, id).apply()
        
        // 加载该 provider 上次选中的 model
        val savedModel = prefs.getString("current_model_${id}", provider.models.firstOrNull() ?: "") ?: ""
        
        _settings.value = _settings.value.copy(
            activeProviderId = id,
            apiKey = provider.apiKey,
            baseUrl = provider.baseUrl,
            model = savedModel
        )
    }

    fun updateModel(model: String) {
        val activeId = _settings.value.activeProviderId
        if (activeId.isNotEmpty()) {
            prefs.edit().putString("current_model_$activeId", model).apply()
            _settings.value = _settings.value.copy(model = model)
        }
    }

    fun addModelsToProvider(providerId: String, models: List<String>) {
        val currentProviders = _settings.value.providers
        val index = currentProviders.indexOfFirst { it.id == providerId }
        if (index == -1) return
        
        val provider = currentProviders[index]
        val validModels = models.filter { it.isNotBlank() }
        if (validModels.isEmpty()) return

        val newModels = (provider.models + validModels).distinct()
        val updatedProvider = provider.copy(models = newModels)
        
        val newProviders = currentProviders.toMutableList().apply {
            set(index, updatedProvider)
        }
        saveProviders(newProviders)
        
        _settings.value = if (providerId == _settings.value.activeProviderId) {
            val modelToSelect = if (_settings.value.model.isEmpty()) validModels.first() else _settings.value.model
            _settings.value.copy(providers = newProviders, model = modelToSelect)
        } else {
            _settings.value.copy(providers = newProviders)
        }
    }
    
    fun removeModelFromProvider(providerId: String, model: String) {
        val currentProviders = _settings.value.providers
        val index = currentProviders.indexOfFirst { it.id == providerId }
        if (index == -1) return
        
        val provider = currentProviders[index]
        val newModels = provider.models - model
        val updatedProvider = provider.copy(models = newModels)
        
        val newProviders = currentProviders.toMutableList().apply {
            set(index, updatedProvider)
        }
        saveProviders(newProviders)
        
        _settings.value = if (providerId == _settings.value.activeProviderId) {
            val nextModel = if (_settings.value.model == model) {
                newModels.firstOrNull() ?: ""
            } else {
                _settings.value.model
            }
            _settings.value.copy(providers = newProviders, model = nextModel)
        } else {
            _settings.value.copy(providers = newProviders)
        }
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
        val validSteps = maxSteps.coerceIn(5, 100)
        prefs.edit().putInt("max_steps", validSteps).apply()
        _settings.value = _settings.value.copy(maxSteps = validSteps)
    }

    // JSON Serialization Helpers
    private fun serializeProviders(providers: List<ProviderConfig>): String {
        val jsonArray = JSONArray()
        providers.forEach { provider ->
            val jsonObject = JSONObject()
            jsonObject.put("id", provider.id)
            jsonObject.put("name", provider.name)
            jsonObject.put("baseUrl", provider.baseUrl)
            jsonObject.put("apiKey", provider.apiKey)
            
            val modelsArray = JSONArray()
            provider.models.forEach { modelsArray.put(it) }
            jsonObject.put("models", modelsArray)
            
            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }

    private fun deserializeProviders(json: String): List<ProviderConfig> {
        val providers = mutableListOf<ProviderConfig>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val modelsList = mutableListOf<String>()
                val modelsArray = obj.optJSONArray("models")
                if (modelsArray != null) {
                    for (j in 0 until modelsArray.length()) {
                        modelsList.add(modelsArray.getString(j))
                    }
                }
                
                providers.add(ProviderConfig(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    baseUrl = obj.getString("baseUrl"),
                    apiKey = obj.getString("apiKey"),
                    models = modelsList
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return providers
    }
}