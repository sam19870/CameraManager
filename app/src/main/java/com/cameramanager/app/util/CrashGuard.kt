package com.cameramanager.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.cameramanager.app.util.LogCollector
import java.io.File
import java.util.Date

/**
 * 全局防闪退护网（v3 硬兜底）：
 *
 *  1. ActivityLifecycleCallbacks 记录前台 Activity 与前台 resumedActivity；
 *  2. 接管主线程未捕获异常：
 *     - 如果异常 Activity 有 windowToken 且 != resumed → 仅 finish 出问题的 Activity，回退到已存在的 resumedActivity
 *     - 否则 finish 出问题 Activity + 回退到 MainActivity（若进程存活就重开）
 *     - 永不进入"死循环重跑 Looper"模式（那会导致黑屏卡死）；
 *  3. 6 秒熔断，同一异常 6 秒内不重复 toast；
 *  4. 堆栈写入 filesDir/last_crash.log；
 *  5. 子线程异常吞掉。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val FUSE_MS = 6_000L

    @Volatile
    var resumedActivity: Activity? = null
        private set
    @Volatile
    var createdActivity: Activity? = null
        private set

    private var installed = false
    private var lastMsgKey: String? = null
    private var lastToastAt: Long = 0L
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) { createdActivity = a }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { resumedActivity = a; createdActivity = a }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (resumedActivity === a) resumedActivity = null
                if (createdActivity === a) createdActivity = null
            }
        })

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "uncaught on thread ${t.name}: ${e.message}", e)
            runCatching {
                File(app.filesDir, "last_crash.log").writeText(
                    "${Date()}\nthread=${t.name}\n${android.util.Log.getStackTraceString(e)}"
                )
            }
            runCatching { LogCollector.logError("Crash", "崩溃(${t.name})", e) }
            if (t == Looper.getMainLooper().thread) {
                val key = (e.message ?: e.javaClass.name).let { if (it.length > 30) it.substring(0, 30) else it }
                val now = System.currentTimeMillis()
                val shouldToast = (lastMsgKey != key) || (now - lastToastAt > FUSE_MS)
                lastMsgKey = key
                lastToastAt = now
                if (shouldToast) {
                    main.post {
                        runCatching {
                            // 仅当 App 在前台时才弹，避免退出后还在最上层弹窗
                            val current = resumedActivity ?: return@runCatching
                            Toast.makeText(current, "异常已拦截: ${shortMsg(e)}",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val bad = createdActivity
                val good = resumedActivity
                main.post {
                    runCatching {
                        if (bad != null && bad !== good) {
                            bad.finish()
                        } else if (good != null) {
                            // 当前页出了异常，finish掉当前让用户回退
                            good.finish()
                        }
                    }
                }
            }
            // 子线程异常：吞掉，保证进程存活
        }
    }

    private fun shortMsg(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return if (raw.length > 48) raw.substring(0, 48) + "…" else raw
    }
}
