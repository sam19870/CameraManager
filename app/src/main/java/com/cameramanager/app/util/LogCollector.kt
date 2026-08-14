package com.cameramanager.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志收集器：把关键运行日志（视频播放、协议探测、模块报错、崩溃异常）写入
 * filesDir/logs/ 下，供用户从「设置 → 日志收集」导出分享。
 *
 * 设计：
 *  - 按天分文件 app_YYYYMMDD.log，超 512KB 自动滚动到 .1
 *  - 所有写入 try-catch，绝不因写日志而崩溃
 *  - 导出时把 last_crash.log + 所有日志文件 + 设备信息合并成一个 txt，
 *    通过 FileProvider 分享（微信/邮箱/文件管理器）
 */
object LogCollector {

    private const val TAG = "LogCollector"
    private const val MAX_FILE_BYTES = 512 * 1024L

    @Volatile
    private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "logs").apply { mkdirs() }.takeIf { it.isDirectory }
    }

    @Synchronized
    fun log(source: String, message: String) {
        val d = dir() ?: return
        try {
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(d, "app_$stamp.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                f.renameTo(File(d, f.name + ".1"))
            }
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("[$time][$source] $message\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed: ${e.message}")
        }
    }

    fun logError(source: String, what: String, t: Throwable?) {
        log(source, "!! $what：${t?.message ?: "未知异常"}")
        t?.let {
            val trace = Log.getStackTraceString(it)
            if (trace.isNotEmpty()) log(source, trace)
        }
    }

    /** 导出所有日志 + 崩溃日志 + 设备信息，返回可分享的 Uri（通过 FileProvider）。 */
    fun exportAsUri(): Uri? {
        val ctx = appContext ?: return null
        return try {
            val sb = StringBuilder()
            sb.append("===== CameraManager 日志导出 ").append(Date()).append(" =====\n\n")

            val crash = File(ctx.filesDir, "last_crash.log")
            if (crash.exists()) {
                sb.append("----- last_crash.log -----\n")
                sb.append(crash.readText()).append("\n\n")
            }

            val files = dir()?.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.name }
                ?: emptyList()
            if (files.isEmpty()) sb.append("（暂无运行日志）\n\n")
            for (f in files) {
                sb.append("----- ").append(f.name).append(" -----\n")
                sb.append(f.readText()).append("\n\n")
            }

            sb.append("----- 设备信息 -----\n")
            sb.append("模型: ").append(android.os.Build.MODEL).append("\n")
            sb.append("系统版本: Android ").append(android.os.Build.VERSION.RELEASE)
                .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            sb.append("App标识: ").append(ctx.packageName).append("\n")

            val out = File(ctx.cacheDir, "camera_logs_${System.currentTimeMillis()}.txt")
            out.writeText(sb.toString())
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        } catch (e: Exception) {
            Log.w(TAG, "export failed: ${e.message}")
            null
        }
    }

    /** 日志目录总大小（字节）。 */
    fun logDirSize(): Long =
        dir()?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** 日志文件数量。 */
    fun logFileCount(): Int =
        dir()?.listFiles()?.filter { it.isFile }?.size ?: 0

    /** 清空全部日志（含崩溃日志）。 */
    fun clearAll() {
        dir()?.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        val ctx = appContext ?: return
        runCatching { File(ctx.filesDir, "last_crash.log").delete() }
    }
}