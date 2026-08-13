package com.cameramanager.app.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File system helpers for screenshots and local recordings.
 */
object StorageHelper {

    private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun rootDir(context: Context): File {
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            File(Environment.getExternalStorageDirectory(), "CameraManager").apply { mkdirs() }
        }
        return dir
    }

    fun screenshotsDir(context: Context): File =
        File(rootDir(context), "screenshots").apply { mkdirs() }

    fun recordingsDir(context: Context): File =
        File(rootDir(context), "recordings").apply { mkdirs() }

    fun alarmsDir(context: Context): File =
        File(rootDir(context), "alarms").apply { mkdirs() }

    /** Save a bitmap as PNG and return the absolute path. */
    fun saveScreenshot(context: Context, bitmap: Bitmap, deviceName: String): String? {
        val dir = screenshotsDir(context)
        val file = File(dir, "IMG_${sanitize(deviceName)}_${DATE_FMT.format(Date())}.png")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Create a new recording output file for manual recording. */
    fun newRecordingFile(context: Context, deviceName: String): File {
        val dir = recordingsDir(context)
        return File(dir, "VID_${sanitize(deviceName)}_${DATE_FMT.format(Date())}.mp4")
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** Format a duration in ms as mm:ss. */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/**
 * PTZ direction model.
 */
enum class PtzDirection(val pan: Float, val tilt: Float, val zoom: Float) {
    UP(0f, 0.5f, 0f),
    DOWN(0f, -0.5f, 0f),
    LEFT(-0.5f, 0f, 0f),
    RIGHT(0.5f, 0f, 0f),
    LEFT_UP(-0.5f, 0.5f, 0f),
    RIGHT_UP(0.5f, 0.5f, 0f),
    LEFT_DOWN(-0.5f, -0.5f, 0f),
    RIGHT_DOWN(0.5f, -0.5f, 0f),
    ZOOM_IN(0f, 0f, 0.3f),
    ZOOM_OUT(0f, 0f, -0.3f),
    STOP(0f, 0f, 0f)
}
