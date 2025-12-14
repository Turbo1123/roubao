package com.roubao.autopilot.macro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * 宏脚本存储仓库
 */
class MacroRepository(private val context: Context) {

    private val macrosFile: File
        get() = File(context.filesDir, "macros.json")

    /**
     * 获取所有宏脚本
     */
    suspend fun getAllMacros(): List<MacroScript> = withContext(Dispatchers.IO) {
        try {
            if (!macrosFile.exists()) return@withContext emptyList()
            val json = macrosFile.readText()
            val array = JSONArray(json)
            val macros = mutableListOf<MacroScript>()
            for (i in 0 until array.length()) {
                macros.add(MacroScript.fromJson(array.getJSONObject(i)))
            }
            macros.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取单个宏脚本
     */
    suspend fun getMacro(id: String): MacroScript? = withContext(Dispatchers.IO) {
        getAllMacros().find { it.id == id }
    }

    /**
     * 保存宏脚本
     */
    suspend fun saveMacro(macro: MacroScript) = withContext(Dispatchers.IO) {
        try {
            val macros = getAllMacros().toMutableList()
            val existingIndex = macros.indexOfFirst { it.id == macro.id }
            val updatedMacro = macro.copy(updatedAt = System.currentTimeMillis())
            if (existingIndex >= 0) {
                macros[existingIndex] = updatedMacro
            } else {
                macros.add(0, updatedMacro)
            }
            saveMacros(macros)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 删除宏脚本
     */
    suspend fun deleteMacro(id: String) = withContext(Dispatchers.IO) {
        try {
            val macros = getAllMacros().filter { it.id != id }
            saveMacros(macros)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 复制宏脚本
     */
    suspend fun duplicateMacro(id: String): MacroScript? = withContext(Dispatchers.IO) {
        val original = getMacro(id) ?: return@withContext null
        val copy = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${original.name} (副本)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveMacro(copy)
        copy
    }

    /**
     * 搜索宏脚本
     */
    suspend fun searchMacros(query: String): List<MacroScript> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext getAllMacros()
        getAllMacros().filter {
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true) ||
            it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
        }
    }

    /**
     * 按标签筛选
     */
    suspend fun getMacrosByTag(tag: String): List<MacroScript> = withContext(Dispatchers.IO) {
        getAllMacros().filter { it.tags.contains(tag) }
    }

    /**
     * 获取所有标签
     */
    suspend fun getAllTags(): List<String> = withContext(Dispatchers.IO) {
        getAllMacros()
            .flatMap { it.tags }
            .distinct()
            .sorted()
    }

    /**
     * 导出宏脚本为 JSON
     */
    suspend fun exportMacro(id: String): String? = withContext(Dispatchers.IO) {
        getMacro(id)?.toJson()?.toString(2)
    }

    /**
     * 导入宏脚本从 JSON
     */
    suspend fun importMacro(json: String): MacroScript? = withContext(Dispatchers.IO) {
        try {
            val macro = MacroScript.fromJson(org.json.JSONObject(json))
            // 生成新 ID 避免冲突
            val imported = macro.copy(
                id = java.util.UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            saveMacro(imported)
            imported
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 清空所有宏脚本
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        try {
            macrosFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveMacros(macros: List<MacroScript>) {
        val array = JSONArray().apply {
            macros.forEach { put(it.toJson()) }
        }
        macrosFile.writeText(array.toString())
    }
}
