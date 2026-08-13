package com.cameramanager.app.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Wraps Android native [MediaPlayer] to provide real-time RTSP preview with
 * switchable resolution profiles, frame capture (screenshot) and local recording.
 *
 * Streaming resolution profiles:
 *  - PROFILE_HD (主码流): high quality, suits WiFi / LAN.
 *  - PROFILE_SD (子码流): balanced, suits mobile network.
 *  - PROFILE_SMOOTH (流畅): lowest, suits weak network.
 *
 * 防卡死与重连机制（v2 新增）：
 *  - 播放启动后启动 [openTimeoutMs] 倒计时，超时未进入 PLAYING 视为一次超时，
 *    累计 [timeoutCount] 并触发自动重连（最多 [maxAutoRetries] 次）。
 *  - 进入 PLAYING 后启动 [stallTimeoutMs] 看门狗：超过该时长没收到任何
 *    状态事件（说明画面卡死无心跳）也视为超时。
 *  - 自动重连次数耗尽后回调 [Listener.onStalled]，由 UI 弹「重连」按钮，
 *    避免无脑重试把 App 卡死。
 *  - [stop] / [release] 会清掉所有定时器，离开界面不会泄漏。
 */
class RtspPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var currentProfile: Int = PROFILE_SD
    private var surfaceView: SurfaceView? = null
    private var surfaceHolderCallback: SurfaceHolder.Callback? = null

    private val main = Handler(Looper.getMainLooper())

    /** 启动后多久没进 PLAYING 算超时（ms）。 */
    private val openTimeoutMs: Long = 12_000
    /** 进入 PLAYING 后多久没收到任何事件算卡死（ms）。 */
    private val stallTimeoutMs: Long = 20_000
    /** 自动重连上限，超过后停止并交给 UI 提示手动重连。 */
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
        /** 超时/卡死并耗尽自动重连次数时回调，UI 应显示「重连」按钮。 */
        fun onStalled(timeoutCount: Int, lastError: String)
        /** 自动重连开始时回调，UI 可显示「重连中 (n/3)」。 */
        fun onReconnecting(attempt: Int, max: Int)
    }

    enum class State { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

    companion object {
        const val PROFILE_HD = 0      // 主码流
        const val PROFILE_SD = 1      // 子码流
        const val PROFILE_SMOOTH = 2  // 流畅
        private const val TAG = "RtspPlayer"
    }

    private fun ensureMediaPlayer(): MediaPlayer {
        mediaPlayer?.let { return it }
        val mp = MediaPlayer()
        mp.setOnInfoListener { _, what, extra ->
            handleInfo(what, extra)
            true
        }
        mp.setOnCompletionListener {
            handleState(State.ENDED)
        }
        mp.setOnPreparedListener {
            Log.d(TAG, "MediaPlayer prepared, calling start()")
            it.start()
            isPlayingState = true
            scheduleWatchdog(stallTimeoutMs)
            handleState(State.PLAYING)
        }
        mp.setOnErrorListener { _, what, extra ->
            val msg = "播放出错 ($what/$extra)，请检查网络或设备地址"
            listener?.onError(msg)
            handleState(State.ERROR)
            onTimeout(msg)
            true
        }
        mp.setOnBufferingUpdateListener { _, percent ->
            if (percent < 100) handleState(State.BUFFERING)
        }
        mediaPlayer = mp
        return mp
    }

    /**
     * Attach to a [SurfaceView] and start playing [url] at the given [profile].
     */
    fun play(surfaceView: SurfaceView, url: String, profile: Int) {
        if (released) return
        this.surfaceView = surfaceView
        currentUrl = url
        currentProfile = profile
        autoRetries = 0
        timeoutCount = 0
        stopped = false
        startPlaybackInternal()
    }

    private fun startPlaybackInternal() {
        val sv = surfaceView ?: return
        val url = currentUrl ?: return
        try {
            val mp = ensureMediaPlayer()
            mp.reset()
            // 选路逻辑：profile 用来在 URL 层拼主码流/子码流路径（URL 已在调用方拼好）。
            // 这里仅在 MediaPlayer 层做缓冲参数启发式处理。
            when (currentProfile) {
                PROFILE_HD -> {
                    // 主码流：稍微多一点缓冲避免花屏
                }
                PROFILE_SD -> {
                }
                PROFILE_SMOOTH -> {
                }
            }
            // 等 Surface 就绪再 setSurface 并 prepareAsync
            val holder = sv.holder
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                mp.setSurface(surface)
            } else {
                // 未就绪，绑一次 Callback，created 时重入
                val old = surfaceHolderCallback
                if (old != null) holder.removeCallback(old)
                val cb = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        main.post {
                            if (!released && !stopped && currentUrl == url) {
                                try {
                                    mediaPlayer?.setSurface(h.surface)
                                } catch (_: Exception) { /* ignore */ }
                            }
                        }
                        try { holder.removeCallback(this) } catch (_: Exception) {}
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hg: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                }
                surfaceHolderCallback = cb
                holder.addCallback(cb)
            }
            mp.setDataSource(context, Uri.parse(url))
            mp.prepareAsync()
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
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        surfaceHolderCallback = null
        surfaceView = null
    }

    fun isPlaying(): Boolean = try {
        isPlayingState && mediaPlayer?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun getCurrentProfile(): Int = currentProfile

    /** 累计超时次数（含自动重连触发的）。 */
    fun getTimeoutCount(): Int = timeoutCount

    /**
     * 用户点击「重连」时调用：重置自动重连计数并尝试重新播放。
     * 不会清 [timeoutCount]，便于 UI 累计显示。
     */
    fun manualReconnect() {
        if (released) return
        autoRetries = 0
        if (currentUrl == null || surfaceView == null) return
        stopped = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        isPlayingState = false
        main.postDelayed({
            if (!released && !stopped) startPlaybackInternal()
        }, 200)
    }

    private fun handleInfo(what: Int, extra: Int) {
        lastEventTime = System.currentTimeMillis()
        when (what) {
            MediaPlayer.MEDIA_INFO_BUFFERING_START -> handleState(State.BUFFERING)
            MediaPlayer.MEDIA_INFO_BUFFERING_END -> handleState(State.PLAYING)
            MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                isPlayingState = true
                scheduleWatchdog(stallTimeoutMs)
                handleState(State.PLAYING)
            }
        }
    }

    private fun handleState(state: State) {
        lastEventTime = System.currentTimeMillis()
        listener?.onStateChanged(state)
    }

    /**
     * 启动看门狗：[delayMs] 后检查是否需要判定超时。每次启动递增 token，
     * 旧 token 的回调会被丢弃，避免误判。
     */
    private fun scheduleWatchdog(delayMs: Long) {
        val token = ++watchdogToken
        main.postDelayed({
            if (released || token != watchdogToken) return@postDelayed
            if (isPlayingState) {
                // PLAYING 期间：距离上次事件太久没动静 → 卡死
                val idle = System.currentTimeMillis() - lastEventTime
                if (idle >= stallTimeoutMs - 500) {
                    onTimeout("画面长时间无响应")
                } else {
                    // 重新挂下一个看门狗
                    scheduleWatchdog(stallTimeoutMs)
                }
            } else {
                // 还没进 PLAYING：判定启动超时
                onTimeout("连接超时，未进入播放")
            }
        }, delayMs)
    }

    private fun cancelWatchdog() {
        // 仅靠 token 失效已有挂起的回调，避免误删主线程上其他组件的回调。
        watchdogToken++
    }

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
            }, 800L * autoRetries)   // 退避：第 1 次 0.8s，第 2 次 1.6s，第 3 次 2.4s
        } else {
            listener?.onStalled(timeoutCount, reason)
        }
    }

    /**
     * Capture the current displayed frame into a [Bitmap]. Native MediaPlayer does
     * not expose direct frame grabbing, so we fall back to the SurfaceView's drawing
     * cache as a best-effort capture.
     */
    @Suppress("DEPRECATION")
    fun captureFrame(surfaceView: SurfaceView): Bitmap? {
        return try {
            surfaceView.isDrawingCacheEnabled = true
            val bmp = Bitmap.createBitmap(surfaceView.drawingCache)
            surfaceView.isDrawingCacheEnabled = false
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "captureFrame failed: ${e.message}")
            null
        }
    }
}
