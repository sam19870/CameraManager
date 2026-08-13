package com.cameramanager.app.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.ArrayList

/**
 * libVLC 3.5.1 封装的 RTSP 播放器（VLCVideoLayout + attachViews）。
 *
 * 为什么用 VLCVideoLayout？
 *  - libVLC 官方推荐封装，内部自动管理 SurfaceView/TextureView + VLCVout；
 *  - 对各种国产摄像头 H.264/H.265/音频兼容性碾压 Android 原生 MediaPlayer；
 *  - 强制 RTSP over TCP，避免 UDP 丢包导致的黑屏花屏。
 */
class RtspPlayer(private val context: Context) {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var currentUrl: String? = null
    private var currentProfile: Int = PROFILE_SD
    @Volatile private var viewAttached: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val openTimeoutMs: Long = 15_000
    private val stallTimeoutMs: Long = 25_000
    private val maxAutoRetries: Int = 3

    private var timeoutCount: Int = 0
    private var autoRetries: Int = 0
    private var watchdogToken: Int = 0
    private var lastEventTime: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var released: Boolean = false
    @Volatile private var stopped: Boolean = true

    var listener: Listener? = null

    interface Listener {
        fun onStateChanged(state: State)
        fun onError(message: String)
        fun onStalled(timeoutCount: Int, lastError: String)
        fun onReconnecting(attempt: Int, max: Int)
    }

    enum class State { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

    companion object {
        const val PROFILE_HD = 0
        const val PROFILE_SD = 1
        const val PROFILE_SMOOTH = 2
        private const val TAG = "RtspPlayer"
    }

    private fun ensureLibVLC(): LibVLC = libVLC ?: run {
        val args = ArrayList<String>().apply {
            add("-vvv")
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
            add("--avcodec-hw=any")
            add("--network-caching=300")
            add("--file-caching=300")
            add("--live-caching=300")
            add("--sout-mux-caching=300")
            add("--aout=android_audiotrack")
        }
        LibVLC(context.applicationContext, args).also { libVLC = it }
    }

    private fun ensureMediaPlayer(): MediaPlayer = mediaPlayer ?: run {
        MediaPlayer(ensureLibVLC()).also { mp ->
            mp.setEventListener { event -> onVlcEvent(event) }
            mediaPlayer = mp
        }
    }

    fun play(layout: VLCVideoLayout, url: String, profile: Int) {
        if (released) return
        this.videoLayout = layout
        this.currentUrl = url
        this.currentProfile = profile
        this.autoRetries = 0
        this.timeoutCount = 0
        this.stopped = false
        startPlaybackInternal()
    }

    private fun startPlaybackInternal() {
        val vl = videoLayout ?: return
        val url = currentUrl ?: return
        try {
            val mp = ensureMediaPlayer()
            if (!viewAttached) {
                runCatching {
                    mp.attachViews(vl, null, false, true)
                }.onSuccess { viewAttached = true }
                 .onFailure { Log.w(TAG, "attachViews: ${it.message}") }
            }
            val media = Media(ensureLibVLC(), android.net.Uri.parse(url))
            val cache = when (currentProfile) {
                PROFILE_HD -> 500
                PROFILE_SD -> 300
                else -> 200
            }
            media.addOption(":network-caching=$cache")
            media.addOption(":rtsp-tcp")
            media.addOption(":avcodec-hw=any")
            media.addOption(":file-caching=$cache")
            media.addOption(":live-caching=$cache")
            mp.media = media
            media.release()
            mp.play()
            isPlayingState = false
            lastEventTime = System.currentTimeMillis()
            listener?.onStateChanged(State.OPENING)
            scheduleWatchdog(openTimeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "startPlaybackInternal failed: ${e.message}")
            listener?.onError("启动播放失败: ${e.message}")
            onTimeout("启动播放失败")
        }
    }

    fun stop() {
        stopped = true
        cancelWatchdog()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        isPlayingState = false
        listener?.onStateChanged(State.STOPPED)
    }

    fun release() {
        released = true
        cancelWatchdog()
        try { stop() } catch (_: Exception) {}
        try {
            if (viewAttached) { mediaPlayer?.detachViews(); viewAttached = false }
        } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { libVLC?.release() } catch (_: Exception) {}
        libVLC = null
        videoLayout = null
    }

    fun isPlaying(): Boolean = try {
        isPlayingState && mediaPlayer?.isPlaying == true
    } catch (_: Exception) { false }

    fun getCurrentProfile(): Int = currentProfile
    fun getTimeoutCount(): Int = timeoutCount

    fun manualReconnect() {
        if (released) return
        autoRetries = 0
        if (currentUrl == null || videoLayout == null) return
        stopped = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        isPlayingState = false
        main.postDelayed({
            if (!released && !stopped) startPlaybackInternal()
        }, 300)
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        lastEventTime = System.currentTimeMillis()
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                listener?.onStateChanged(State.OPENING)
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f) listener?.onStateChanged(State.BUFFERING)
            }
            MediaPlayer.Event.Playing -> {
                isPlayingState = true
                scheduleWatchdog(stallTimeoutMs)
                listener?.onStateChanged(State.PLAYING)
            }
            MediaPlayer.Event.Paused -> listener?.onStateChanged(State.PAUSED)
            MediaPlayer.Event.Stopped -> listener?.onStateChanged(State.STOPPED)
            MediaPlayer.Event.EndReached -> {
                isPlayingState = false
                listener?.onStateChanged(State.ENDED)
            }
            MediaPlayer.Event.EncounteredError -> {
                isPlayingState = false
                val msg = "播放错误（设备拒绝或地址不可达）"
                listener?.onError(msg)
                listener?.onStateChanged(State.ERROR)
                onTimeout(msg)
            }
        }
    }

    private fun scheduleWatchdog(delayMs: Long) {
        val token = ++watchdogToken
        main.postDelayed({
            if (released || token != watchdogToken) return@postDelayed
            if (isPlayingState) {
                val idle = System.currentTimeMillis() - lastEventTime
                if (idle >= stallTimeoutMs - 500) {
                    onTimeout("画面长时间无响应")
                } else {
                    scheduleWatchdog(stallTimeoutMs)
                }
            } else {
                onTimeout("连接超时，未进入播放")
            }
        }, delayMs)
    }

    private fun cancelWatchdog() { watchdogToken++ }

    private fun onTimeout(reason: String) {
        timeoutCount++
        Log.w(TAG, "onTimeout #$timeoutCount: $reason (autoRetries=$autoRetries/$maxAutoRetries)")
        if (autoRetries < maxAutoRetries) {
            autoRetries++
            listener?.onReconnecting(autoRetries, maxAutoRetries)
            try { mediaPlayer?.stop() } catch (_: Exception) {}
            isPlayingState = false
            main.postDelayed({
                if (!released && !stopped) startPlaybackInternal()
            }, 800L * autoRetries)
        } else {
            listener?.onStalled(timeoutCount, reason)
        }
    }

    @Suppress("DEPRECATION")
    fun captureFrame(): Bitmap? {
        val vl = videoLayout ?: return null
        return try {
            vl.isDrawingCacheEnabled = true
            val bmp = Bitmap.createBitmap(vl.drawingCache)
            vl.isDrawingCacheEnabled = false
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "captureFrame failed: ${e.message}")
            null
        }
    }
}
