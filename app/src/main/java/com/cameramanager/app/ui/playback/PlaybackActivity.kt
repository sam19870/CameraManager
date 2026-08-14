package com.cameramanager.app.ui.playback

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Recording
import com.cameramanager.app.databinding.ActivityPlaybackBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.PlaybackViewModel
import com.cameramanager.app.net.NetworkRouter
import com.cameramanager.app.util.StorageHelper
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraVendorApi
import com.cameramanager.app.vendor.VideoAudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * TP-LINK 风格历史回放页
 *  - 顶部播放器 + 日期选择条（点击展开日历）
 *  - 24 小时时间轴（橙色=移动侦测，绿色=常规录像）
 *  - 下方录像列表（按时间分组，带缩略图标注类型）
 *  - 原画/标清切换（回放默认走主码流=原画，保证最高分辨率）
 *  - 原画下载按钮（通过 RTSP 主码流录制为本地 MP4）
 *  - 空状态友好提示
 */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaybackBinding
    private val viewModel: PlaybackViewModel by viewModels { DeviceViewModelFactory() }
    private var exoPlayer: ExoPlayer? = null
    private var recordings: List<Recording> = emptyList()
    private var selectedDayStart: Long = 0L
    private var adapter: RecordingAdapter? = null
    private var device: Device? = null
    /** 0=原画(主码流) 1=标清(子码流) */
    private var playProfile = 0
    private var currentRec: Recording? = null
    private var videoCfg: VideoAudioConfig? = null

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val TAG = "Playback"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, PlaybackActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityPlaybackBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "历史回放"
        }.onFailure { t ->
            Log.e(TAG, "onCreate binding failed: ${t.message}", t)
            Toast.makeText(this, "回放页初始化失败: ${t.message}", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        viewModel.load(deviceId)

        runCatching {
            exoPlayer = ExoPlayer.Builder(this).build()
            binding.playerView.player = exoPlayer
            binding.playerView.useController = true
        }.onFailure { binding.durationLabel.text = "播放器初始化失败: ${it.message}" }

        // 加载设备信息（为 RTSP 回放下载做准备）
        lifecycleScope.launch {
            runCatching {
                device = withContext(Dispatchers.IO) { CameraApp.get().repository.getDevice(deviceId) }
                device?.let {
                    // 尝试读取一次摄像头视频参数（用于在 UI 上显示当前分辨率）
                    val r = withContext(Dispatchers.IO) { CameraVendorApi.forDevice(it).getVideoAudioConfig(it) }
                    if (r is ApiResult.Success) videoCfg = r.data
                }
            }
        }

        // 画质切换按钮（原画=最高分辨率主码流，标清=子码流）
        binding.btnQuality.text = if (playProfile == 0) "原画" else "标清"
        binding.btnQuality.setOnClickListener {
            val items = arrayOf(
                "原画 · 最高分辨率 (主码流·${device?.mainRtspPath?.take(18) ?: "推荐"})",
                "标清 · 流畅 (子码流·${device?.subRtspPath?.take(18) ?: "默认"})"
            )
            AlertDialog.Builder(this).setTitle("回放画质")
                .setSingleChoiceItems(items, playProfile) { dl, w ->
                    playProfile = w
                    binding.btnQuality.text = if (w == 0) "原画" else "标清"
                    Toast.makeText(this, "切换为 ${items[w].substringBefore(" (")}", Toast.LENGTH_SHORT).show()
                    currentRec?.let { r -> playRecording(r, true) }
                    dl.dismiss()
                }.setNegativeButton("取消", null).show()
        }

        // 下载原画按钮
        binding.btnDownload.setOnClickListener {
            val rec = currentRec
            when {
                rec == null -> Toast.makeText(this, "请先在列表中选择一段录像再下载", Toast.LENGTH_SHORT).show()
                rec.trigger == "snapshot" -> Toast.makeText(this, "截图无需下载，文件已在本地", Toast.LENGTH_SHORT).show()
                else -> downloadRecordingOriginal(rec)
            }
        }

        // 日期条：点展开/收起日历
        binding.barDate.setOnClickListener {
            binding.calendarView.visibility = when (binding.calendarView.visibility) {
                View.VISIBLE -> View.GONE
                else -> View.VISIBLE
            }
        }

        binding.calendarView.setOnDateChangeListener { _, year, month, day ->
            val cal = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            selectedDayStart = cal.timeInMillis
            binding.calendarView.visibility = View.GONE
            loadDay(deviceId, selectedDayStart)
        }
        // init to today
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        selectedDayStart = today.timeInMillis

        // 录像列表
        adapter = RecordingAdapter { rec -> playRecording(rec) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.timeline.setOnSegmentClickListener { position ->
            val rec = recordings.getOrNull(position) ?: return@setOnSegmentClickListener
            playRecording(rec)
        }

        loadDay(deviceId, selectedDayStart)
    }

    private fun loadDay(deviceId: Long, dayStart: Long) {
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        binding.dateLabel.text = formatDate(dayStart)
        lifecycleScope.launch {
            recordings = viewModel.recordingsForDay(deviceId, dayStart, dayEnd)
            val segs = recordings.map { it.startTime - dayStart to (it.endTime - it.startTime) }
            binding.timeline.setSegments(segs, recordings.map { it.trigger == "motion" })
            adapter?.submit(recordings)
            binding.empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
            binding.recycler.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun playRecording(rec: Recording, switchProfileOnly: Boolean = false) {
        currentRec = rec
        val uri = rec.filePath
        val typeLabel = if (rec.trigger == "motion") "移动侦测触发录像" else
            if (rec.trigger == "snapshot") "截图" else "常规录像"
        val qualLabel = if (playProfile == 0) "原画·最高分辨率" else "标清·流畅"
        val extra = videoCfg?.let { " · 摄像头当前设置 ${it.width}×${it.height} ${it.frameRate}fps" } ?: ""
        binding.durationLabel.text = "${formatTime(rec.startTime)} - ${formatTime(rec.endTime)}  ·  $typeLabel  ·  ${StorageHelper.formatDuration(rec.durationMs)}  ·  $qualLabel$extra"
        if (rec.trigger == "snapshot") {
            binding.durationLabel.text = "${binding.durationLabel.text}\n（截图无法用视频播放器播放，已显示为条目说明）"
            return
        }
        runCatching {
            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(this@PlaybackActivity).build()
            binding.playerView.player = exoPlayer
            // 本地文件直接播放；远程 TF 卡回放走 RTSP 主码流(playProfile=0 是原画)
            val playUri = buildPlaybackUri(rec, uri)
            exoPlayer?.setMediaItem(MediaItem.fromUri(playUri))
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }.onFailure {
            binding.durationLabel.text = "${binding.durationLabel.text}\n播放失败：${it.message ?: "未知错误"}"
        }
    }

    /**
     * 回放 URL 构造：
     *  - 本地 App 录制文件：直接用 filePath
     *  - 摄像头 TF 卡远程段：按当前 profile 选主/子码流 RTSP URL（保证原画=最高分辨率）
     */
    private fun buildPlaybackUri(rec: Recording, localFile: String): Uri {
        if (localFile.startsWith("/") || localFile.startsWith("content://") ||
            localFile.startsWith("file://")) {
            return Uri.parse(localFile)
        }
        val d = device ?: return Uri.parse(localFile)
        // 回放时使用简化直连；NetworkRouter.resolve 是 suspend 所以此处兜底直接用设备地址
        // 【重要】RTSP 端口用 rtspPort（554），不是管理端口 port（80）
        val host = d.host; val port = d.rtspPort
        val url = d.rtspUrlForProfile(playProfile, useHost = host, usePort = port)
        return Uri.parse(url)
    }

    /**
     * 原画下载：
     *  强制走主码流(profile=0)，拉 RTSP 流存为本地 MP4 文件。
     *  简单实现：用 Android DownloadManager 下载 RTSP 隧道化的 URL；
     *  若协议不支持则提示用户，文件保存到 DCIM/CameraManager/ 目录。
     */
    private fun downloadRecordingOriginal(rec: Recording) {
        val d = device ?: run { Toast.makeText(this, "设备不存在", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("下载原画录像")
            .setMessage("将按摄像头设置的最高分辨率（主码流原画）下载该段录像到手机相册。\n录像时长：${StorageHelper.formatDuration(rec.durationMs)}\n\n注意：远程 TF 卡录像下载需要摄像头在同一 WiFi 或内网穿透可用。")
            .setPositiveButton("开始下载") { _, _ ->
                lifecycleScope.launch { doDownload(d, rec) }
            }.setNegativeButton("取消", null).show()
    }

    private suspend fun doDownload(d: Device, rec: Recording) = withContext(Dispatchers.IO) {
        runCatching {
            // 先用 NetworkRouter 解析一次；失败就直接用设备 host/port 兜底
            val route = runCatching { NetworkRouter.resolve(this@PlaybackActivity, d) }
                .getOrNull()
            val host = route?.host ?: d.host
            val port = route?.rtspPort ?: d.rtspPort
            val rtspUrl = d.rtspUrlForProfile(0, useHost = host, usePort = port)
            val timeTag = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", rec.startTime)
            val fname = "cam_${d.id}_${timeTag}_${rec.durationMs}s_original.mp4"
            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: File(filesDir, "Movies")
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, fname)

            // 录制下载：用 FFMPEG-style 的 RTSP 直转 MP4。
            // 此处用 libVLC/RtspPlayer 的录制接口，若不支持则降级为 Copy 流。
            val recOk = com.cameramanager.app.rtsp.RtspPlayer.recordRtspToFile(
                this@PlaybackActivity, rtspUrl, dest.absolutePath, rec.durationMs
            )
            withContext(Dispatchers.Main) {
                if (recOk) {
                    Toast.makeText(this@PlaybackActivity, "原画下载完成：${dest.name}", Toast.LENGTH_LONG).show()
                    // 加入相册
                    runCatching {
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        scanIntent.data = Uri.fromFile(dest)
                        sendBroadcast(scanIntent)
                    }
                } else {
                    // 降级提示：用 DownloadManager 能下的直接下
                    Toast.makeText(this@PlaybackActivity, "该摄像头暂不支持RTSP直存下载，请使用预览页的本地录制功能保存原画", Toast.LENGTH_LONG).show()
                }
            }
        }.onFailure { t ->
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlaybackActivity, "下载失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDate(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        return sdf.format(java.util.Date(ms))
    }

    private fun formatTime(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        return sdf.format(java.util.Date(ms))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { exoPlayer?.release() }
    }
}

/** TP-LINK 风格：时间 + 类型彩色chip + 时长 */
class RecordingAdapter(
    private val onClick: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private var list: List<Recording> = emptyList()

    fun submit(data: List<Recording>) { list = data; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.tvTime)
        val chip: TextView = v.findViewById(R.id.tvChip)
        val duration: TextView = v.findViewById(R.id.tvDuration)
        val icon: ImageView = v.findViewById(R.id.ivIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = list[position]
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        holder.time.text = sdf.format(java.util.Date(r.startTime)) + " 起"
        holder.duration.text = StorageHelper.formatDuration(r.durationMs)
        when (r.trigger) {
            "motion" -> {
                holder.chip.text = "移动侦测"
                holder.chip.setBackgroundColor(0x33FF9800.toInt())
                holder.chip.setTextColor(0xFFFF9800.toInt())
                holder.icon.setImageResource(R.drawable.ic_alarm)
            }
            "snapshot" -> {
                holder.chip.text = "截图"
                holder.chip.setBackgroundColor(0x334CAF50.toInt())
                holder.chip.setTextColor(0xFF4CAF50.toInt())
                holder.icon.setImageResource(R.drawable.ic_snapshot)
            }
            else -> {
                holder.chip.text = "常规录像"
                holder.chip.setBackgroundColor(0x331677FF.toInt())
                holder.chip.setTextColor(0xFF1677FF.toInt())
                holder.icon.setImageResource(R.drawable.ic_record)
            }
        }
        holder.itemView.setOnClickListener { onClick(r) }
    }

    override fun getItemCount(): Int = list.size
}
