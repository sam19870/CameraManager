package com.cameramanager.app.rtsp

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * libVLC 3.5.1 封装的 RTSP 播放器（VLCVideoLayout + attachViews）。
 *
 * =======================================
 *  防卡死 / 防拖垮App设计（Fail-Fast原则）：
 * =======================================
 *  1. 所有 VLC 重操作（init/play/stop/release）全部在 Dispatchers.IO 协程执行，主线程永远不阻塞
 *  2. 播放启动强门禁：15s 内未出 Playing 事件 → 自动杀播放器进入重试
 *  3. 播放中看门狗：25s 无任何VLC事件（画面卡死）→ 自动判定卡死
 *  4. 最多自动重试 3 次，超限后弹出「手动重连」入口，用户点击再进入重试循环
 *  5. release() 会立刻 cancelAll 所有协程 + watchdog token++ 清空主线程回调
 *  6. 调用方（PreviewActivity）在 onDestroy 里必须同步调用 release 释放 JNI 资源
 * =======================================
 */
class RtspPlayer(private val context: Context) {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoLayout: VLCVideoLayout? = null
    private var currentUrl: String? = null
    private var currentProfile: Int = PROFILE_SD
    @Volatile private var viewAttached: Boolean = false

    /** 所有耗时的 VLC 操作都在此 scope 执行，独立于主线程 */
    private val playerScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("RtspPlayer")
    )
    /** 跟踪当前播放 job，重试/release 时取消前一次 */
    private var currentPlayJob: Job? = null

    private val main = Handler(Looper.getMainLooper())
    private val openTimeoutMs: Long = 15_000L
    private val stallTimeoutMs: Long = 25_000L
    private val maxAutoRetries: Int = 3
    /** 单次重试间隔基准，每次退避 1.5x */
    private val baseRetryDelayMs: Long = 800L

    private var timeoutCount: Int = 0
    private val autoRetries: AtomicInteger = AtomicInteger(0)
    private val watchdogToken: AtomicInteger = AtomicInteger(0)
    @Volatile private var lastEventTime: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    private val released: AtomicBoolean = AtomicBoolean(false)
    private val stopped: AtomicBoolean = AtomicBoolean(true)

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

    /** IO线程内初始化 LibVLC，失败 fail-fast */
    private suspend fun ensureLibVLC(): LibVLC = withContext(Dispatchers.IO) {
        libVLC ?: run {
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
                // 最关键：VLC 内部超时设置，防止内部死等DNS/TCP握手卡死
                add("--http-continuous")
                add("--run-time=99999")
            }
            LibVLC(context.applicationContext, args).also { libVLC = it }
        }
    }

    private suspend fun ensureMediaPlayer(): MediaPlayer = withContext(Dispatchers.IO) {
        mediaPlayer ?: run {
            val vlc = ensureLibVLC()
            MediaPlayer(vlc).also { mp ->
                mp.setEventListener { event -> onVlcEvent(event) }
                mediaPlayer = mp
            }
        }
    }

    fun play(layout: VLCVideoLayout, url: String, profile: Int) {
        if (released.get()) return
        // UI 调用入口：快速赋值，把重操作丢 IO 协程，不阻塞UI
        this.videoLayout = layout
        this.currentUrl = url
        this.currentProfile = profile
        autoRetries.set(0)
        timeoutCount = 0
        stopped.set(false)
        // 取消上一次的播放任务（避免并发重入）
        currentPlayJob?.cancel()
        currentPlayJob = playerScope.launch { internalPlayWithRetry() }
    }

    /**
     * 核心播放循环：尝试播放 → 看门狗超时 → 退避重试 → 超限通知UI
     * 全部在 IO 线程执行，不碰主线程的消息循环
     */
    private suspend fun internalPlayWithRetry() = withContext(Dispatchers.IO) {
        while (!released.get() && !stopped.get()) {
            val attempt = autoRetries.get()
            if (attempt > 0) {
                // 退避等待（在非当前线程延迟，不占用main handler）
                val delayMs = baseRetryDelayMs * (1L shl (attempt - 1).coerceAtMost(3))
                delay(delayMs)
                if (released.get() || stopped.get()) break
                postMain { listener?.onReconnecting(attempt, maxAutoRetries) }
            }
            val ok = tryPlayOnce()
            if (ok) {
                // 进入 Playing，由 stall watchdog 管控后续
                return@withContext
            }
            if (released.get() || stopped.get()) break
            timeoutCount++
            if (attempt < maxAutoRetries) {
                autoRetries.incrementAndGet()
                Log.i(TAG, "播放失败，自动重试 ${autoRetries.get()}/$maxAutoRetries")
                continue
            }
            // 超限 -> 进入 stalled，UI 显示手动重连
            postMain { listener?.onStalled(timeoutCount, "连接超时或设备不响应") }
            break
        }
    }

    /** 单次尝试播放（异步 + 门禁超时15s）。返回 true 表示成功进入 PLAYING 状态。 */
    private suspend fun tryPlayOnce(): Boolean = withContext(Dispatchers.IO) {
        val vl = videoLayout
        val url = currentUrl
        if (vl == null || url == null || released.get()) return@withContext false
        return@withContext try {
            suspendCancellableCoroutine { cont ->
                val localToken = watchdogToken.incrementAndGet()
                val mp = runBlocking(Dispatchers.IO) { ensureMediaPlayer() }
                runCatching {
                    if (!viewAttached) mp.attachViews(vl, null, false, true)
                }.onSuccess { viewAttached = true }
                 .onFailure { Log.w(TAG, "attachViews: ${it.message}") }
                val media = Media(runBlocking { ensureLibVLC() }, android.net.Uri.parse(url))
                val cache = when (currentProfile) {
                    PROFILE_HD -> 500; PROFILE_SD -> 300; else -> 200
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
                postMain { listener?.onStateChanged(State.OPENING) }

                // 门禁：15秒超时
                val timeoutJob = playerScope.launch {
                    delay(openTimeoutMs)
                    if (!released.get() && watchdogToken.get() == localToken && !isPlayingState) {
                        postMain { listener?.onError("连接超时(${openTimeoutMs/1000}s)，未进入播放") }
                        runCatching { mp.stop() }
                        if (cont.isActive) cont.resume(false)
                    }
                }

                // 一次性等待 Playing 事件
                var done = false
                mp.setEventListener { ev ->
                    onVlcEvent(ev)
                    if (ev.type == MediaPlayer.Event.Playing && !done) {
                        done = true
                        timeoutJob.cancel()
                        // 启动 stall watchdog
                        scheduleStallWatchdog(localToken)
                        if (cont.isActive) cont.resume(true)
                    } else if (ev.type == MediaPlayer.Event.EncounteredError && !done) {
                        done = true
                        timeoutJob.cancel()
                        if (cont.isActive) cont.resume(false)
                    }
                }

                cont.invokeOnCancellation {
                    timeoutJob.cancel()
                    runCatching { mp.stop() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryPlayOnce error: ${e.message}")
            false
        }
    }

    /** 画面卡死看门狗：周期性检查 lastEventTime */
    private fun scheduleStallWatchdog(token: Int) {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (released.get() || token != watchdogToken.get()) return
                val idle = System.currentTimeMillis() - lastEventTime
                if (idle >= stallTimeoutMs - 500) {
                    // 卡死 → 判定失败，让上层重试
                    Log.w(TAG, "stall watchdog 触发，idle=${idle}ms")
                    postMain { listener?.onError("画面长时间无响应(${stallTimeoutMs/1000}s)") }
                    playerScope.launch {
                        runCatching { mediaPlayer?.stop() }
                        timeoutCount++
                        if (autoRetries.get() < maxAutoRetries) {
                            autoRetries.incrementAndGet()
                            internalPlayWithRetry()
                        } else {
                            postMain { listener?.onStalled(timeoutCount, "画面卡死") }
                        }
                    }
                } else {
                    main.postDelayed(this, (stallTimeoutMs - idle).coerceAtLeast(3000L))
                }
            }
        }, stallTimeoutMs)
    }

    fun stop() {
        stopped.set(true)
        watchdogToken.incrementAndGet()
        // 不阻塞UI线程，stop 丢 IO 线程
        playerScope.launch {
            runCatching { mediaPlayer?.stop() }
            isPlayingState = false
            postMain { listener?.onStateChanged(State.STOPPED) }
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        watchdogToken.incrementAndGet()
        stopped.set(true)
        currentPlayJob?.cancel()
        playerScope.launch {
            runCatching { mediaPlayer?.stop() }
            runCatching { if (viewAttached) mediaPlayer?.detachViews() }
            viewAttached = false
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { libVLC?.release() }
            libVLC = null
            videoLayout = null
            // 取消 scope 里所有未完成协程
            playerScope.cancel()
        }
    }

    fun isPlaying(): Boolean = isPlayingState && runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)

    fun getCurrentProfile(): Int = currentProfile
    fun getTimeoutCount(): Int = timeoutCount

    /** 用户手动点重连按钮：强制重置重试计数 + 走新的播放循环 */
    fun manualReconnect() {
        if (released.get()) return
        autoRetries.set(0)
        timeoutCount = 0
        if (currentUrl == null || videoLayout == null) return
        stopped.set(false)
        currentPlayJob?.cancel()
        currentPlayJob = playerScope.launch {
            runCatching { mediaPlayer?.stop() }
            isPlayingState = false
            delay(300L)
            if (!released.get() && !stopped.get()) internalPlayWithRetry()
        }
    }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        lastEventTime = System.currentTimeMillis()
        when (event.type) {
            MediaPlayer.Event.Opening -> postMain { listener?.onStateChanged(State.OPENING) }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering < 100f) postMain { listener?.onStateChanged(State.BUFFERING) }
            }
            MediaPlayer.Event.Playing -> {
                isPlayingState = true
                postMain { listener?.onStateChanged(State.PLAYING) }
            }
            MediaPlayer.Event.Paused -> postMain { listener?.onStateChanged(State.PAUSED) }
            MediaPlayer.Event.Stopped -> postMain { listener?.onStateChanged(State.STOPPED) }
            MediaPlayer.Event.EndReached -> {
                isPlayingState = false
                postMain { listener?.onStateChanged(State.ENDED) }
            }
            MediaPlayer.Event.EncounteredError -> {
                isPlayingState = false
                val msg = "播放错误（设备拒绝或地址不可达）"
                postMain { listener?.onError(msg)
                    listener?.onStateChanged(State.ERROR) }
            }
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

    private inline fun postMain(crossinline block: () -> Unit) {
        main.post { if (!released.get()) block() }
    }
}
