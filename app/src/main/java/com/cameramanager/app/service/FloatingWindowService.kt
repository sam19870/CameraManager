package com.cameramanager.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.cameramanager.app.R
import com.cameramanager.app.rtsp.RtspPlayer
import com.cameramanager.app.ui.MainActivity

/**
 * Floating-window (悬浮窗) preview service. Renders a small draggable RTSP tile
 * on top of other apps so the user can keep monitoring while using other apps.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission, which the caller must request
 * via [Settings.ACTION_MANAGE_OVERLAY_PERMISSION] before starting the service.
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var player: RtspPlayer? = null

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.createChannels(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification("悬浮窗预览运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        showFloating(url)
        return START_STICKY
    }

    private fun showFloating(url: String) {
        removeFloating()
        val view = LayoutInflater.from(this).inflate(R.layout.window_floating, null)
        val surface = view.findViewById<android.view.SurfaceView>(R.id.floatSurface)
        val closeBtn = view.findViewById<android.widget.ImageView>(R.id.floatClose)
        val expandBtn = view.findViewById<android.widget.ImageView>(R.id.floatExpand)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.45f).toInt(),
            (metrics.widthPixels * 0.45f * 9 / 16f).toInt(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 200 }

        windowManager.addView(view, params)
        floatingView = view

        // Drag handling
        var initX = 0; var initY = 0; var initTouchX = 0f; var initTouchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = e.rawX; initTouchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (e.rawX - initTouchX).toInt()
                    params.y = initY + (e.rawY - initTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                }
            }
            false
        }

        closeBtn.setOnClickListener {
            player?.stop(); player?.release(); player = null
            removeFloating(); stopSelf()
        }
        expandBtn.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            removeFloating(); stopSelf()
        }

        // Start streaming
        player = RtspPlayer(this).apply { init() }
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                player?.play(surface, url, RtspPlayer.PROFILE_SD)
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { player?.stop() }
        })
    }

    private fun removeFloating() {
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        removeFloating()
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
        private const val NOTIF_ID = 1003
        private const val EXTRA_URL = "url"
        fun start(context: Context, rtspUrl: String) {
            context.startService(Intent(context, FloatingWindowService::class.java).putExtra(EXTRA_URL, rtspUrl))
        }

        /** True if overlay permission is granted. */
        fun canDrawOverlays(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else true

        /** Intent to request overlay permission. */
        fun overlayPermissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"))
    }
}
