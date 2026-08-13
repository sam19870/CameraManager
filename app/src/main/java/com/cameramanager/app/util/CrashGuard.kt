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
 * 全局防闪退护网（Cockroach 式）：
 *
 *  1. 通过 ActivityLifecycleCallbacks 记录当前前台 Activity；
 *  2. 接管主线程未捕获异常：Toast 提示 + 关闭出问题页面 + 恢复主循环，
 *     用户看到的是「某个页面弹了一下提示并返回」，而不是整个 App 闪退；
 *  3. 堆栈写入 filesDir/last_crash.log 便于排查根因；
 *  4. 非主线程异常仅记录日志，不让进程崩溃。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    @Volatile
    var currentActivity: Activity? = null
        private set

    private var installed = false

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
                    "${Date()}\nthread=${t.name}\n${Log.getStackTraceString(e)}"
                )
            }
            if (t == Looper.getMainLooper().thread) {
                // 主线程异常：提示 + 关掉出事页面 + 恢复消息循环，避免系统闪退弹窗
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        Toast.makeText(app, "页面异常已拦截：${e.message ?: e.javaClass.simpleName}",
                            Toast.LENGTH_LONG).show()
                    }
                    runCatching { currentActivity?.finish() }
                }
                while (true) {
                    try {
                        Looper.loop()
                    } catch (e2: Throwable) {
                        Log.e(TAG, "re-loop caught: ${e2.message}", e2)
                    }
                }
            }
            // 子线程异常：吞掉，仅记录，保证进程存活
        }
    }
}
