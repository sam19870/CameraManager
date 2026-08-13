package com.cameramanager.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cameramanager.app.R
import com.cameramanager.app.ui.MainActivity

/**
 * Foreground service that holds a wake lock and a persistent notification while a
 * manual local recording is in progress, so the OS does not kill the recording
 * when the app goes to the background.
 */
class CameraStreamService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.createChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraManager:Recording").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
        val text = if (recording) "正在本地录像…" else "实时预览运行中"
        startForeground(NOTIF_ID, buildNotification(text))
        if (recording && wakeLock?.isHeld == false) wakeLock?.acquire(30 * 60 * 1000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AlarmNotifier.CHANNEL_STREAM)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1002
        private const val EXTRA_RECORDING = "recording"

        fun start(context: Context, recording: Boolean = false) {
            val intent = Intent(context, CameraStreamService::class.java).putExtra(EXTRA_RECORDING, recording)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraStreamService::class.java))
        }
    }
}
