package com.roubao.autopilot.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局崩溃捕获器
 * 捕获未处理的异常并保存到本地文件
 */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var context: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    companion object {
        private const val LOG_DIR = "crash_logs"
        private const val MAX_LOG_FILES = 10 // 最多保留10个日志文件

        @Volatile
        private var instance: CrashHandler? = null

        fun getInstance(): CrashHandler {
            return instance ?: synchronized(this) {
                instance ?: CrashHandler().also { instance = it }
            }
        }

        /**
         * 获取日志目录
         */
        fun getLogDir(context: Context): File {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * 获取所有日志文件
         */
        fun getLogFiles(context: Context): List<File> {
            val dir = getLogDir(context)
            return dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        /**
         * 导出日志（合并所有日志到一个文件）
         */
        fun exportLogs(context: Context): File? {
            val logFiles = getLogFiles(context)
            if (logFiles.isEmpty()) {
                return null
            }

            val exportFile = File(context.cacheDir, "roubao_logs_${System.currentTimeMillis()}.txt")
            try {
                FileWriter(exportFile).use { writer ->
                    // 写入设备信息
                    writer.write("========== 设备信息 ==========\n")
                    writer.write("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    writer.write("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                    writer.write("应用版本: ${getAppVersion(context)}\n")
                    writer.write("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                    writer.write("\n")

                    // 合并所有日志
                    logFiles.forEach { file ->
                        writer.write("========== ${file.name} ==========\n")
                        writer.write(file.readText())
                        writer.write("\n\n")
                    }
                }
                return exportFile
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        /**
         * 分享日志文件
         */
        fun shareLogs(context: Context) {
            val exportFile = exportLogs(context)
            if (exportFile == null) {
                android.widget.Toast.makeText(context, "没有日志可导出", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "肉包 App 日志")
                    putExtra(Intent.EXTRA_TEXT, "请查看附件中的日志文件")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(intent, "分享日志"))
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "分享失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * 清除所有日志
         */
        fun clearLogs(context: Context) {
            val dir = getLogDir(context)
            dir.listFiles()?.forEach { it.delete() }
        }

        /**
         * 获取日志统计信息
         */
        fun getLogStats(context: Context): String {
            val files = getLogFiles(context)
            if (files.isEmpty()) {
                return "暂无日志"
            }
            val totalSize = files.sumOf { it.length() }
            val sizeStr = if (totalSize > 1024) "${totalSize / 1024} KB" else "$totalSize B"
            return "${files.size} 个文件, $sizeStr"
        }

        private fun getAppVersion(context: Context): String {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode.toLong()
                }
                "${pInfo.versionName} ($versionCode)"
            } catch (_: Exception) {
                "Unknown"
            }
        }
    }

    /**
     * 初始化
     */
    fun init(context: Context) {
        this.context = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)

        // 清理旧日志
        cleanOldLogs()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 保存崩溃日志
        saveCrashLog(throwable)

        // 调用默认处理器（让系统显示崩溃对话框或直接退出）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    /**
     * 保存崩溃日志
     */
    private fun saveCrashLog(throwable: Throwable) {
        val ctx = context ?: return

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "crash_$timestamp.log"
            val file = File(getLogDir(ctx), fileName)

            PrintWriter(FileWriter(file)).use { writer ->
                // 时间
                writer.println("========== 崩溃时间 ==========")
                writer.println(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                writer.println()

                // 设备信息
                writer.println("========== 设备信息 ==========")
                writer.println("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
                writer.println("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                writer.println("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                writer.println("应用版本: ${getAppVersion(ctx)}")
                writer.println()

                // 异常信息
                writer.println("========== 异常信息 ==========")
                throwable.printStackTrace(writer)
            }

            println("[CrashHandler] 崩溃日志已保存: $fileName")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 记录普通日志（非崩溃）
     */
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val ctx = context ?: return

        try {
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "log_$today.log"
            val file = File(getLogDir(ctx), fileName)

            FileWriter(file, true).use { writer ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                writer.appendLine("[$time] [$tag] $message")
                throwable?.let {
                    val sw = java.io.StringWriter()
                    it.printStackTrace(PrintWriter(sw))
                    writer.appendLine(sw.toString())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清理旧日志，只保留最近的文件
     */
    private fun cleanOldLogs() {
        val ctx = context ?: return
        val files = getLogFiles(ctx)
        if (files.size > MAX_LOG_FILES) {
            files.drop(MAX_LOG_FILES).forEach { it.delete() }
        }
    }
}
