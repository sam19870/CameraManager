package com.cameramanager.app

import android.app.Application
import android.util.Log
import com.cameramanager.app.data.AppDatabase
import com.cameramanager.app.data.Repository
import com.cameramanager.app.util.CrashGuard

/**
 * Application 入口。初始化全局防闪退护网、Room 数据库与 Repository。
 */
class CameraApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: Repository by lazy { Repository(database) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        runCatching { CrashGuard.install(this) }
            .onFailure { Log.e("CameraApp", "CrashGuard install failed", it) }
    }

    companion object {
        @Volatile
        private var instance: CameraApp? = null

        fun get(): CameraApp =
            instance ?: throw IllegalStateException("CameraApp not initialized")
    }
}
