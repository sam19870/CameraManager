package com.cameramanager.app.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralized runtime permission handling for the camera features.
 */
object PermissionHelper {

    val PREVIEW_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    val INTERCOM_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO
    )

    val RECORDING_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        emptyArray()
    } else {
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /**
     * 读取当前 WiFi SSID（用于内网/穿透自动选路）需要精确定位权限。
     * Android 12+ 强制要求；低于 12 也需要定位才能拿到 SSID。
     */
    val ROUTE_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    const val REQ_PREVIEW = 10
    const val REQ_INTERCOM = 11
    const val REQ_RECORDING = 12
    const val REQ_ROUTE = 13

    fun isGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun request(activity: Activity, permissions: Array<String>, requestCode: Int) {
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
        }
    }
}
