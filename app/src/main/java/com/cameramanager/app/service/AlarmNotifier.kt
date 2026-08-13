package com.cameramanager.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cameramanager.app.R
import com.cameramanager.app.ui.MainActivity

/**
 * Posts alarm push notifications to the system notification shade, including
 * the sound/light deterrence action.
 */
object AlarmNotifier {

    const val CHANNEL_ALARM = "channel_alarm"
    const val CHANNEL_STREAM = "channel_stream"
    private var nextId = 5000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "告警通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "智能侦测告警实时推送"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STREAM, "实时预览", NotificationManager.IMPORTANCE_LOW).apply {
                description = "实时预览与录像前台服务"
            }
        )
    }

    fun postAlarm(
        context: Context,
        deviceName: String,
        message: String,
        snapshotPath: String? = null
    ): Int {
        val id = nextId++
        val openIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context, id + 1,
            Intent(context, AlarmReceiver::class.java).apply {
                action = "com.cameramanager.app.action.DISMISS_ALARM"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("$deviceName 检测到异常")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_close, "关闭", dismissIntent)

        snapshotPath?.let { path ->
            runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle().bigPicture(bmp).setSummaryText(message)
                    )
                }
            }
        }

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id, builder.build())
        return id
    }

    fun cancel(context: Context, id: Int) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }
}
