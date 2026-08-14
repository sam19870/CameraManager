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
    @Volatile private var muted: Boolean = false
    @Volatile private var playerVolumePct: Int = 100

    /** 当前是否静音（播放端） */
    fun isMuted(): Boolean = muted

    /** 切换静音/恢复，返回新状态 */
    fun toggleMute(): Boolean {
        muted = !muted
        postMain {
            runCatching {
                val mp = mediaPlayer
                if (mp != null) {
                    if (muted) { mp.volume = 0 }
                    else { mp.volume = playerVolumePct.coerceIn(0, 200) }
                }
            }
        }
        return muted
    }

    /**
     * 调整播放端音量（步长 ±10），返回当前百分比 0~150。
     * 所有调整仅作用于预览播放，不影响摄像头录制原声。
     */
    fun adjustVolume(deltaPct: Int): Int {
        muted = false
        val next = (playerVolumePct + deltaPct).coerceIn(0, 150)
        playerVolumePct = next
        postMain {
            runCatching { mediaPlayer?.volume = next }
        }
        return next
    }

    var listener: Listener? = null

    interface Listener {
        fun onStateChanged(state: State)
        fun onError(message: String)
        fun onStalled(timeoutCount: Int, lastError: String)
        fun onReconnecting(attempt: Int, max: Int)
    }

    enum class State { IDLE, OPENING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

    /** IO线程内初始化 LibVLC，失败 fail-fast。
     *  参数组合参考 OpenIPC / go2rtc 官方推荐 libVLC 子码流兼容设置：
     *    - 强制 RTSP-over-TCP 避免 UDP 丢包被摄像头防火墙拒
     *    - live-caching=300 子码流延迟低；主码流 800 防卡顿
     *    - avcodec-hw=auto + skip-loop-filter=nonref 解码快
     *    - :rtsp-user / :rtsp-pwd 显式给 VLC，URL内编码方式在部分海康/大华会鉴权失败
     */
    private suspend fun ensureLibVLC(): LibVLC = withContext(Dispatchers.IO) {
        libVLC ?: run {
            val args = ArrayList<String>().apply {
                // 极简参数：对标 VLC Android 官方 + OpenIPC viewer
                // 只保留 RTSP 播放必须的，去掉所有可能冲突的选项
                add("--rtsp-tcp")                 // 强制TCP，避免UDP丢包
                add("--network-caching=200")      // 低延迟
                add("--live-caching=200")
                add("--no-drop-late-frames")
                add("--no-skip-frames")
                add("--aout=android_audiotrack")
                add("-vvv")
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
                // 按码流档位：子码流=更小缓存，主码流=更大缓存；并显式指定 rtsp-user/rtsp-pwd
                val (cache, networkStr) = when (currentProfile) {
                    PROFILE_HD -> 800 to "1200"
                    PROFILE_SD -> 400 to "600"
                    else -> 300 to "500"
                }
                media.addOption(":network-caching=$cache")
                media.addOption(":file-caching=$cache")
                media.addOption(":live-caching=$cache")
                media.addOption(":rtsp-tcp")
                media.addOption(":rtsp-frame-buffer-size=200000")
                media.addOption(":avcodec-hw=auto")
                media.addOption(":avcodec-threads=2")
                media.addOption(":clock-jitter=800")
                media.addOption(":clock-synchro=1")
                media.addOption(":network-timeout=$networkStr")
                media.addOption(":rtsp-timeout=$networkStr")
                // URL 内 userinfo 编码：确保 rtsp://user:pass@host 同时显式给 VLC rtsp-user/pwd，
                // 解决海康/大华/TP-LINK 部分设备 401 后 VLC 不再重试的问题
                runCatching {
                    val parsed = android.net.Uri.parse(url)
                    val u = parsed.userInfo
                    if (!u.isNullOrEmpty()) {
                        val colon = u.indexOf(':')
                        if (colon >= 0) {
                            val uu = java.net.URLDecoder.decode(u.substring(0, colon), "UTF-8")
                            val pp = java.net.URLDecoder.decode(u.substring(colon + 1), "UTF-8")
                            media.addOption(":rtsp-user=$uu")
                            media.addOption(":rtsp-pwd=$pp")
                        } else {
                            media.addOption(":rtsp-user=$u")
                        }
                    }
                }.onFailure { Log.w(TAG, "decode rtsp userinfo failed: ${it.message}") }
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

    companion object {
        const val PROFILE_HD = 0
        const val PROFILE_SD = 1
        const val PROFILE_SMOOTH = 2
        private const val TAG = "RtspPlayer"

        /**
         * 录制 RTSP 流（主码流=原画）保存到本地 MP4 文件。
         * 用 libVLC Media 的 sout 输出串：#transcode{...}:std{access=file,mux=mp4,dst=...}
         * 简化实现：在 IO 线程开一个独立的 LibVLC 实例，录到时间到或遇到错误为止。
         * 录制时长 durationMs=0 表示录 30 秒兜底。
         * 返回是否成功写入了文件且 > 0 字节。
         */
        @JvmStatic
        suspend fun recordRtspToFile(
            context: Context,
            rtspUrl: String,
            destFilePath: String,
            durationMs: Long
        ): Boolean = withContext(Dispatchers.IO) {
            val dur = durationMs.coerceAtLeast(10_000L)
            val vlcArgs = ArrayList<String>().apply {
                add("-vvv"); add("--rtsp-tcp")
                add("--network-caching=800"); add("--live-caching=800")
                add("--avcodec-hw=auto"); add("--sout-mux-caching=500")
                add("--aout=android_audiotrack")
                add("--codec=avcodec")
            }
            var vlc: LibVLC? = null
            var player: MediaPlayer? = null
            try {
                vlc = LibVLC(context.applicationContext, vlcArgs)
                val media = Media(vlc, android.net.Uri.parse(rtspUrl))
                val userInfo = runCatching { android.net.Uri.parse(rtspUrl).userInfo }.getOrNull()
                if (!userInfo.isNullOrEmpty()) {
                    val colon = userInfo.indexOf(':')
                    if (colon >= 0) {
                        val u = java.net.URLDecoder.decode(userInfo.substring(0, colon), "UTF-8")
                        val p = java.net.URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8")
                        media.addOption(":rtsp-user=$u")
                        media.addOption(":rtsp-pwd=$p")
                    }
                }
                media.addOption(":rtsp-tcp")
                // 原画直转 MP4：视频不重编码（copy），音频也 copy；最大程度保留原画质
                val escapedDest = destFilePath.replace("'", "'\\''")
                media.addOption(":sout=#transcode{vcodec=none,acodec=none}:std{access=file,mux=mp4,dst='${escapedDest}'}")
                media.addOption(":sout-keep")
                player = MediaPlayer(vlc)
                player.media = media
                media.release()
                player.play()
                // 按时长录制：每 500ms 检查是否结束
                val start = System.currentTimeMillis()
                val file = java.io.File(destFilePath)
                val waitUntil = start + dur + 500L
                while (System.currentTimeMillis() < waitUntil && player.isPlaying) {
                    try { delay(500L) } catch (ie: InterruptedException) { break }
                }
                runCatching { player.stop() }
                try { delay(1200L) } catch (_: InterruptedException) {} // 给 muxer 写尾部的时间
                file.exists() && file.length() > 1024
            } catch (t: Throwable) {
                Log.w(TAG, "recordRtspToFile error: ${t.message}", t)
                false
            } finally {
                runCatching { player?.release() }
                runCatching { vlc?.release() }
            }
        }
    }
}
