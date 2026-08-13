package com.cameramanager.app

import android.app.Application
import com.cameramanager.app.data.AppDatabase
import com.cameramanager.app.data.Repository

/**
 * Application entry point. Initializes the Room database and the single Repository
 * instance used across the app.
 */
class CameraApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: Repository by lazy { Repository(database) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        private var instance: CameraApp? = null

        fun get(): CameraApp =
            instance ?: throw IllegalStateException("CameraApp not initialized")
    }
}
