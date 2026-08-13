package com.cameramanager.app.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceView
import org.videolan.libvlc.IVLCVout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Wraps libVLC to provide real-time RTSP preview with switchable resolution profiles,
 * frame capture (screenshot) and local recording.
 *
 * Streaming resolution profiles:
 *  - PROFILE_HD (主码流): high quality, suits WiFi / LAN.
 *  - PROFILE_SD (子码流): balanced, suits mobile network.
 *  - PROFILE_SMOOTH (流畅): lowest, suits weak network.
 *
 * 防卡死与重连机制（v2 新增）：
 *  - 播放启动后启动 [OPEN_TIMEOUT_MS] 倒计时，超时未进入 PLAYING 视为一次超时，
 *    累计 [timeoutCount] 并触发自动重连（最多 [MAX_AUTO_RETRIES] 次）。
 *  - 进入 PLAYING 后启动 [STALL_TIMEOUT_MS] 看门狗：超过该时长没收到任何
 *    状态事件（说明画面卡死无心跳）也视为超时。
 *  - 自动重连次数耗尽后回调 [Listener.onStalled]，由 UI 弹「重连」按钮，
 *    避免无脑重试把 App 卡死。
 *  - [stop] / [release] 会清掉所有定时器，离开界面不会泄漏。
 */
class RtspPlayer(private val context: Context) {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vout: IVLCVout? = null
    private var currentUrl: String? = null
    private var currentProfile: Int = PROFILE_SD
    private var surfaceView: SurfaceView? = null

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

    fun init() {
        if (libVlc != null) return
        val options = ArrayList<String>().apply {
            add("--rtsp-tcp")
            add("--network-caching=300")
            add("--clock-jitter=0")
            add("--codec=avcodec")
            add("--no-drop-late-frames")
            add("--no-skip-frames")
        }
        libVlc = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVlc).apply {
            setEventListener { event ->
                handleEvent(event.type)
            }
        }
    }

    /**
     * Attach to a [SurfaceView] and start playing [url] at the given [profile].
     */
    fun play(surfaceView: SurfaceView, url: String, profile: Int) {
        if (released) return
        init()
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
        detachVout()
        val mp = mediaPlayer ?: return
        vout = mp.vlcVout
        vout?.setVideoView(sv)
        vout?.apply {
            setWindowSize(sv.width, sv.height)
            attach()
        }
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            when (currentProfile) {
                PROFILE_HD -> {
                    addOption(":rtsp-frame-buffer-size=2000000")
                    addOption(":network-caching=500")
                }
                PROFILE_SD -> {
                    addOption(":network-caching=300")
                }
                PROFILE_SMOOTH -> {
                    addOption(":network-caching=150")
                    addOption(":rtsp-jitter=0")
                }
            }
        }
        mp.media = media
        isPlayingState = false
        lastEventTime = System.currentTimeMillis()
        listener?.onStateChanged(State.OPENING)
        mp.play()
        scheduleWatchdog(openTimeoutMs)
    }

    fun stop() {
        stopped = true
        cancelWatchdog()
        mediaPlayer?.stop()
        detachVout()
        isPlayingState = false
        listener?.onStateChanged(State.STOPPED)
    }

    fun release() {
        released = true
        cancelWatchdog()
        stop()
        mediaPlayer?.release()
        libVlc?.release()
        mediaPlayer = null
        libVlc = null
        surfaceView = null
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

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
        // stop 内部已 cancelWatchdog，这里直接重新起播
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        isPlayingState = false
        main.postDelayed({
            if (!released && !stopped) startPlaybackInternal()
        }, 200)
    }

    private fun detachVout() {
        vout?.let {
            try { it.detach() } catch (_: Exception) {}
        }
        vout = null
    }

    private fun handleEvent(type: Int) {
        lastEventTime = System.currentTimeMillis()
        val state = when (type) {
            MediaPlayer.Event.Opening -> State.OPENING
            MediaPlayer.Event.Buffering -> State.BUFFERING
            MediaPlayer.Event.Playing -> {
                isPlayingState = true
                // 进入播放后切换为「卡死看门狗」
                scheduleWatchdog(stallTimeoutMs)
                State.PLAYING
            }
            MediaPlayer.Event.Paused -> State.PAUSED
            MediaPlayer.Event.Stopped -> State.STOPPED
            MediaPlayer.Event.EndReached -> State.ENDED
            MediaPlayer.Event.EncounteredError -> {
                listener?.onError("播放出错，请检查网络或设备地址")
                State.ERROR
            }
            else -> return
        }
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
     * Capture the current displayed frame into a [Bitmap]. libVLC does not expose
     * direct frame grabbing without native builds, so for screenshot we render the
     * surface view's drawing cache as a best-effort capture.
     */
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
