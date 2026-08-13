package com.cameramanager.app.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.Date

/**
 * 全局防闪退护网（v2）：
 *
 *  1. ActivityLifecycleCallbacks 记录前台 Activity；
 *  2. 接管主线程未捕获异常：Toast 提示 + 关闭出问题页面 + 恢复主循环，
 *     并且加「6秒熔断」：相同异常 6 秒内不再重复 toast，防止点按钮反复点导致
 *     Handler.post 积压而出现二次崩溃；
 *  3. 堆栈写入 filesDir/last_crash.log；
 *  4. 非主线程异常仅记录日志；
 *  5. 通过 PackageManager.resolveActivity 的二次校验在调用端（safeStart）。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val FUSE_MS = 6_000L

    @Volatile
    var currentActivity: Activity? = null
        private set

    private var installed = false
    private var lastMsgKey: String? = null
    private var lastToastAt: Long = 0L
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) { currentActivity = a }
            override fun onActivityStarted(a: Activity) { currentActivity = a }
            override fun onActivityResumed(a: Activity) { currentActivity = a }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (currentActivity === a) currentActivity = null
            }
        })

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "uncaught on thread ${t.name}: ${e.message}", e)
            runCatching {
                File(app.filesDir, "last_crash.log").writeText(
                    "${Date()}\nthread=${t.name}\n${android.util.Log.getStackTraceString(e)}"
                )
            }
            if (t == Looper.getMainLooper().thread) {
                val key = (e.message ?: e.javaClass.name).let { if (it.length > 30) it.substring(0, 30) else it }
                val now = System.currentTimeMillis()
                val shouldToast = (lastMsgKey != key) || (now - lastToastAt > FUSE_MS)
                lastMsgKey = key
                lastToastAt = now
                if (shouldToast) {
                    main.post {
                        runCatching {
                            Toast.makeText(app, "异常已拦截: ${shortMsg(e)}",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
                main.post {
                    runCatching { currentActivity?.finish() }
                }
                while (true) {
                    try {
                        Looper.loop()
                    } catch (e2: Throwable) {
                        Log.e(TAG, "re-loop caught: ${shortMsg(e2)}", e2)
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
