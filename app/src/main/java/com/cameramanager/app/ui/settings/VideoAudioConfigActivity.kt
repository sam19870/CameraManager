package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityVideoAudioConfigBinding
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraVendorApi
import com.cameramanager.app.vendor.ImageSettings
import com.cameramanager.app.vendor.VideoAudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 摄像头音视频参数设置页
 *  - 所有参数一律从摄像头里读取出来（探测）
 *  - 用户修改后点击「保存到摄像头」写回设备
 *  - 不支持写回的设备（乐橙/通用 ONVIF）提示只读，按钮灰显
 */
class VideoAudioConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoAudioConfigBinding
    private var device: Device? = null
    /** 摄像头读出来的原始配置（用于重置） */
    private var originalCfg: VideoAudioConfig? = null
    /** UI 当前编辑的配置 */
    private var current: VideoAudioConfig? = null
    /** 设备是否支持写回（=Tapo 支持，其他只读） */
    private var canWrite = false
    /** 图像参数当前编辑值（ONVIF Imaging） */
    private var currentImage: ImageSettings? = null
    /** 是否允许写回图像参数（ONVIF 设备支持 SetImagingSettings） */
    private var imageCanWrite = false

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val TAG = "VideoAudioCfg"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, VideoAudioConfigActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityVideoAudioConfigBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            binding.toolbar.setNavigationOnClickListener { finish() }
        }.onFailure { t ->
            Log.e(TAG, "onCreate binding failed: ${t.message}", t)
            Toast.makeText(this, "页面初始化失败: ${t.message}", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        lifecycleScope.launch {
            runCatching {
                device = withContext(Dispatchers.IO) { CameraApp.get().repository.getDevice(deviceId) }
                val d = device
                if (d == null) {
                    Toast.makeText(this@VideoAudioConfigActivity, "设备不存在", Toast.LENGTH_SHORT).show()
                    finish(); return@launch
                }
                canWrite = d.vendor == "tapo"
                reloadFromCamera()
                loadImageSettings()
            }.onFailure { t ->
                Log.w(TAG, "load device failed: ${t.message}", t)
                Toast.makeText(this@VideoAudioConfigActivity, "加载设备失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // 绑定所有行的点击事件
        bindRowClicks()
        bindImageSeekbars()

        binding.btnReset.setOnClickListener { reloadFromCamera() }
        binding.btnSave.setOnClickListener { saveToCamera() }
    }

    /** 图像参数 SeekBar 监听：拖动即更新当前值，保存时统一下发。 */
    private fun bindImageSeekbars() {
        fun wire(seek: SeekBar, value: TextView, updater: (Int) -> ImageSettings) {
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentImage = updater(p)
                        value.text = "$p"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        wire(binding.seekBrightness, binding.tvBrightnessValue) { p -> (currentImage ?: ImageSettings()).copy(brightness = p) }
        wire(binding.seekContrast, binding.tvContrastValue) { p -> (currentImage ?: ImageSettings()).copy(contrast = p) }
        wire(binding.seekSaturation, binding.tvSaturationValue) { p -> (currentImage ?: ImageSettings()).copy(saturation = p) }
        wire(binding.seekSharpness, binding.tvSharpnessValue) { p -> (currentImage ?: ImageSettings()).copy(sharpness = p) }
    }

    /** 从摄像头读取图像参数（ONVIF Imaging），不支持则隐藏该分组。 */
    private fun loadImageSettings() {
        val d = device ?: return
        val api = CameraVendorApi.forDevice(d)
        imageCanWrite = d.onvifPort > 0
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.getImageSettings(d) }.getOrNull() }
            val s = (r as? ApiResult.Success)?.data
            binding.tvImageGroupTitle.visibility = View.GONE
            binding.cardImage.visibility = View.GONE
            if (s != null) {
                currentImage = s
                binding.seekBrightness.progress = s.brightness; binding.tvBrightnessValue.text = "${s.brightness}"
                binding.seekContrast.progress = s.contrast; binding.tvContrastValue.text = "${s.contrast}"
                binding.seekSaturation.progress = s.saturation; binding.tvSaturationValue.text = "${s.saturation}"
                binding.seekSharpness.progress = s.sharpness; binding.tvSharpnessValue.text = "${s.sharpness}"
                binding.tvImageGroupTitle.visibility =
                    if (imageCanWrite) View.VISIBLE else View.GONE
                binding.cardImage.visibility =
                    if (imageCanWrite) View.VISIBLE else View.GONE
            }
        }
    }

    /** 从摄像头重新读取所有参数 */
    private fun reloadFromCamera() {
        val d = device ?: return
        val api = CameraVendorApi.forDevice(d)
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            binding.btnReset.isEnabled = false
            val r = withContext(Dispatchers.IO) { runCatching { api.getVideoAudioConfig(d) }.getOrNull() }
            when (r) {
                is ApiResult.Success -> {
                    val cfg = r.data
                    originalCfg = cfg
                    current = cfg
                    applyCfgToUi(cfg)
                    binding.btnReset.isEnabled = true
                    binding.btnSave.isEnabled = canWrite
                    binding.tvReadonlyHint.visibility = if (canWrite) View.GONE else View.VISIBLE
                    Toast.makeText(this@VideoAudioConfigActivity, "参数读取成功", Toast.LENGTH_SHORT).show()
                }
                is ApiResult.Error -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "读取失败：${r.message}", Toast.LENGTH_LONG).show()
                    binding.btnReset.isEnabled = true
                }
                is ApiResult.Unsupported -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "摄像头不支持视频参数读取", Toast.LENGTH_LONG).show()
                    binding.btnReset.isEnabled = true
                    binding.tvReadonlyHint.visibility = View.VISIBLE
                }
                else -> {
                    // null：兜底默认值也展示
                    originalCfg = VideoAudioConfig()
                    current = VideoAudioConfig()
                    applyCfgToUi(VideoAudioConfig())
                    binding.btnSave.isEnabled = false
                    binding.btnReset.isEnabled = true
                }
            }
        }
    }

    private fun applyCfgToUi(cfg: VideoAudioConfig) {
        // 视频流开关
        binding.rowVideoEnabled.ivIcon.setImageResource(R.drawable.ic_record)
        binding.rowVideoEnabled.tvTitle.text = "视频流"
        binding.rowVideoEnabled.tvSubtitle.text = "关闭后摄像头不再出画面"
        binding.rowVideoEnabled.tvSubtitle.visibility = View.VISIBLE
        binding.rowVideoEnabled.switchRow.isChecked = cfg.videoEnabled
        binding.rowVideoEnabled.switchRow.isEnabled = canWrite
        binding.rowVideoEnabled.switchRow.setOnCheckedChangeListener { _, v ->
            current = current?.copy(videoEnabled = v)
        }

        // 编码
        binding.rowCodec.tvTitle.text = "视频编码"
        binding.rowCodec.tvSubtitle.text = "H.265 画质相同体积更小，老设备兼容性稍差"
        binding.rowCodec.tvSubtitle.visibility = View.VISIBLE
        binding.rowCodec.tvValue.text = cfg.videoCodec
        binding.rowCodec.root.setOnClickListener { pickCodec() }

        // 分辨率
        binding.rowResolution.tvTitle.text = "分辨率（决定原画画质）"
        binding.rowResolution.tvSubtitle.text = "录制和回放都以此分辨率保存"
        binding.rowResolution.tvSubtitle.visibility = View.VISIBLE
        binding.rowResolution.tvValue.text = "${cfg.width} × ${cfg.height}"
        binding.rowResolution.root.setOnClickListener { pickResolution() }

        // 帧率
        binding.rowFps.tvTitle.text = "帧率（fps）"
        binding.rowFps.tvSubtitle.text = "越高画面越流畅，但码率需求越高"
        binding.rowFps.tvSubtitle.visibility = View.VISIBLE
        binding.rowFps.tvValue.text = "${cfg.frameRate} fps"
        binding.rowFps.root.setOnClickListener { pickFps() }

        // 码率控制
        binding.rowRcMode.tvTitle.text = "码率控制"
        binding.rowRcMode.tvSubtitle.text = "VBR 动态码率（推荐）/ CBR 固定码率"
        binding.rowRcMode.tvSubtitle.visibility = View.VISIBLE
        binding.rowRcMode.tvValue.text = if (cfg.rateControl == "CBR") "CBR 固定码率" else "VBR 动态码率"
        binding.rowRcMode.root.setOnClickListener { pickRcMode() }

        // 码率 SeekBar: 0~12 -> 512 kbps ~ 12288 kbps
        val brIdx = cfg.bitrateKbps.coerceIn(512, 12288).let { (it / 1024f).coerceIn(0.5f, 12f) * 1f / 1024f * 1024f }
        val prog = ((cfg.bitrateKbps - 512).coerceAtLeast(0).toFloat() / 1024f).toInt().coerceIn(0, 12)
        binding.seekBitrate.progress = prog
        binding.tvBitrateValue.text = "${cfg.bitrateKbps} kbps"
        binding.seekBitrate.isEnabled = canWrite
        binding.seekBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val kbps = (512 + p * 1024).coerceIn(512, 12288)
                    binding.tvBitrateValue.text = "$kbps kbps"
                    current = current?.copy(bitrateKbps = kbps)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // GOP SeekBar: 0~40 -> 10~210 帧
        val gopProg = (cfg.iFrameInterval - 10).coerceIn(0, 200).let { (it / 5).coerceIn(0, 40) }
        binding.seekGop.progress = gopProg
        binding.tvGopValue.text = "${cfg.iFrameInterval} 帧"
        binding.seekGop.isEnabled = canWrite
        binding.seekGop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val g = 10 + p * 5
                    binding.tvGopValue.text = "$g 帧"
                    current = current?.copy(iFrameInterval = g)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 流类型
        binding.rowStreamType.tvTitle.text = "流类型"
        binding.rowStreamType.tvSubtitle.text = "视频/音频/音视频"
        binding.rowStreamType.tvSubtitle.visibility = View.VISIBLE
        binding.rowStreamType.tvValue.text = when {
            cfg.videoEnabled && cfg.audioEnabled -> "音视频"
            cfg.videoEnabled -> "仅视频"
            else -> "仅音频"
        }
        binding.rowStreamType.root.setOnClickListener { pickStreamType() }

        // 音频流开关
        binding.rowAudioEnabled.ivIcon.setImageResource(R.drawable.ic_volume)
        binding.rowAudioEnabled.tvTitle.text = "音频流"
        binding.rowAudioEnabled.tvSubtitle.text = "开启后录像含声音、预览有声"
        binding.rowAudioEnabled.tvSubtitle.visibility = View.VISIBLE
        binding.rowAudioEnabled.switchRow.isChecked = cfg.audioEnabled
        binding.rowAudioEnabled.switchRow.isEnabled = canWrite
        binding.rowAudioEnabled.switchRow.setOnCheckedChangeListener { _, v ->
            current = current?.copy(audioEnabled = v)
            binding.rowStreamType.tvValue.text = when {
                (current?.videoEnabled ?: true) && v -> "音视频"
                current?.videoEnabled ?: true -> "仅视频"
                else -> "仅音频"
            }
        }

        // 音频编码
        binding.rowAudioCodec.tvTitle.text = "音频编码"
        binding.rowAudioCodec.tvSubtitle.visibility = View.GONE
        binding.rowAudioCodec.tvValue.text = cfg.audioCodec
        binding.rowAudioCodec.root.setOnClickListener { pickAudioCodec() }

        // 采样率
        binding.rowAudioSample.tvTitle.text = "音频采样率"
        binding.rowAudioSample.tvSubtitle.visibility = View.GONE
        binding.rowAudioSample.tvValue.text = "${cfg.audioSampleRate} Hz"
        binding.rowAudioSample.root.setOnClickListener { pickSampleRate() }

        // 收音音量
        binding.rowMicVolume.tvTitle.text = "摄像机收音音量"
        binding.rowMicVolume.tvValue.text = "${cfg.micVolume}%"
        binding.rowMicVolume.seekRow.progress = cfg.micVolume
        binding.rowMicVolume.seekRow.isEnabled = canWrite
        binding.rowMicVolume.seekRow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.rowMicVolume.tvValue.text = "$p%"
                    current = current?.copy(micVolume = p)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 扬声音量
        binding.rowSpkVolume.tvTitle.text = "摄像机扬声音量"
        binding.rowSpkVolume.tvValue.text = "${cfg.speakerVolume}%"
        binding.rowSpkVolume.seekRow.progress = cfg.speakerVolume
        binding.rowSpkVolume.seekRow.isEnabled = canWrite
        binding.rowSpkVolume.seekRow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.rowSpkVolume.tvValue.text = "$p%"
                    current = current?.copy(speakerVolume = p)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.rowCodec.ivIcon.setImageResource(R.drawable.ic_settings)
        binding.rowResolution.ivIcon.setImageResource(R.drawable.ic_snapshot)
        binding.rowFps.ivIcon.setImageResource(R.drawable.ic_snapshot)
        binding.rowRcMode.ivIcon.setImageResource(R.drawable.ic_settings)
        binding.rowStreamType.ivIcon.setImageResource(R.drawable.ic_volume)
        binding.rowAudioCodec.ivIcon.setImageResource(R.drawable.ic_volume)
        binding.rowAudioSample.ivIcon.setImageResource(R.drawable.ic_volume)
        binding.rowVideoEnabled.ivIcon.setColorFilter(0xFF1677FF.toInt())
        binding.rowAudioEnabled.ivIcon.setColorFilter(0xFF1677FF.toInt())
    }

    private fun bindRowClicks() {
        // 默认图标灰显（让布局不太空）
        listOf(binding.rowCodec, binding.rowResolution, binding.rowFps,
            binding.rowRcMode, binding.rowStreamType, binding.rowAudioCodec, binding.rowAudioSample
        ).forEach {
            it.iconWrap.visibility = View.VISIBLE
        }
    }

    // ------ 选择对话框 ------

    private fun pickCodec() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改编码", Toast.LENGTH_SHORT).show()
        val opts = (current?.availableCodecs ?: listOf("H.264", "H.265")).toTypedArray()
        val cur = current?.videoCodec ?: "H.264"
        val idx = opts.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("视频编码")
            .setSingleChoiceItems(opts, idx) { dl, w ->
                current = current?.copy(videoCodec = opts[w])
                binding.rowCodec.tvValue.text = opts[w]
                Toast.makeText(this, "选择了 ${opts[w]}，请点击底部保存写回摄像头", Toast.LENGTH_SHORT).show()
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickResolution() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改分辨率", Toast.LENGTH_SHORT).show()
        val c = current ?: return
        val labels = c.availableResolutions.map { "${it.first} × ${it.second}" }.toTypedArray()
        val curIdx = c.availableResolutions.indexOfFirst { it.first == c.width && it.second == c.height }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("分辨率（原画画质 = 录制分辨率）")
            .setSingleChoiceItems(labels, curIdx) { dl, w ->
                val (w_, h) = c.availableResolutions[w]
                current = current?.copy(width = w_, height = h)
                binding.rowResolution.tvValue.text = "$w_ × $h"
                Toast.makeText(this, "已选 ${labels[w]}，保存后回放/下载即为该分辨率原画", Toast.LENGTH_SHORT).show()
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickFps() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改帧率", Toast.LENGTH_SHORT).show()
        val c = current ?: return
        val labels = c.availableFrameRates.map { "$it fps" }.toTypedArray()
        val curIdx = c.availableFrameRates.indexOf(c.frameRate).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("帧率 (fps)")
            .setSingleChoiceItems(labels, curIdx) { dl, w ->
                val fps = c.availableFrameRates[w]
                current = current?.copy(frameRate = fps)
                binding.rowFps.tvValue.text = "$fps fps"
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickRcMode() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改码率控制", Toast.LENGTH_SHORT).show()
        val items = arrayOf("VBR 动态码率（推荐，清晰自适应）", "CBR 固定码率（网络传输稳定）")
        val cur = if ((current?.rateControl ?: "VBR").uppercase() == "CBR") 1 else 0
        AlertDialog.Builder(this).setTitle("码率控制")
            .setSingleChoiceItems(items, cur) { dl, w ->
                val rc = if (w == 1) "CBR" else "VBR"
                current = current?.copy(rateControl = rc)
                binding.rowRcMode.tvValue.text = if (w == 1) "CBR 固定码率" else "VBR 动态码率"
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickStreamType() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改流类型", Toast.LENGTH_SHORT).show()
        val items = arrayOf("音视频（推荐，画面+声音）", "仅视频（无声，节省带宽）", "仅音频")
        val cur = current ?: return
        val idx = when {
            cur.videoEnabled && cur.audioEnabled -> 0
            cur.videoEnabled -> 1
            else -> 2
        }
        AlertDialog.Builder(this).setTitle("流类型")
            .setSingleChoiceItems(items, idx) { dl, w ->
                when (w) {
                    0 -> current = current?.copy(videoEnabled = true, audioEnabled = true)
                    1 -> current = current?.copy(videoEnabled = true, audioEnabled = false)
                    2 -> current = current?.copy(videoEnabled = false, audioEnabled = true)
                }
                val c2 = current ?: return@setSingleChoiceItems
                binding.rowStreamType.tvValue.text = when {
                    c2.videoEnabled && c2.audioEnabled -> "音视频"
                    c2.videoEnabled -> "仅视频"
                    else -> "仅音频"
                }
                binding.rowVideoEnabled.switchRow.isChecked = c2.videoEnabled
                binding.rowAudioEnabled.switchRow.isChecked = c2.audioEnabled
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickAudioCodec() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改音频编码", Toast.LENGTH_SHORT).show()
        val items = arrayOf("G.711A (PCMA, 对讲/监控常用)", "G.711U (PCMU)", "AAC (高音质)", "OPUS")
        val cur = current?.audioCodec ?: "G.711A"
        val idx = when {
            cur.contains("PCMU") || cur.contains("711U", true) -> 1
            cur.contains("AAC", true) -> 2
            cur.contains("OPUS", true) -> 3
            else -> 0
        }
        AlertDialog.Builder(this).setTitle("音频编码")
            .setSingleChoiceItems(items, idx) { dl, w ->
                val code = arrayOf("G.711A", "G.711U", "AAC", "OPUS")
                current = current?.copy(audioCodec = code[w])
                binding.rowAudioCodec.tvValue.text = code[w]
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickSampleRate() {
        if (!canWrite) return Toast.makeText(this, "当前设备不支持修改采样率", Toast.LENGTH_SHORT).show()
        val items = arrayOf(8000, 16000, 22050, 44100, 48000)
        val labels = items.map { "$it Hz" }.toTypedArray()
        val idx = items.indexOf(current?.audioSampleRate ?: 8000).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("音频采样率")
            .setSingleChoiceItems(labels, idx) { dl, w ->
                val sr = items[w]
                current = current?.copy(audioSampleRate = sr)
                binding.rowAudioSample.tvValue.text = "$sr Hz"
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    /** 保存到摄像头 */
    private fun saveToCamera() {
        val d = device
        val cfg = current
        if (d == null || cfg == null) return
        if (!canWrite && !imageCanWrite) {
            Toast.makeText(this, "该设备协议不支持参数写入，请使用厂商官方App", Toast.LENGTH_LONG).show()
            return
        }
        val api = CameraVendorApi.forDevice(d)
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            // 图像参数（ONVIF Imaging）单独下发
            if (imageCanWrite && currentImage != null) {
                val img = currentImage!!
                withContext(Dispatchers.IO) { runCatching { api.setImageSettings(d, img) } }
                Toast.makeText(this@VideoAudioConfigActivity, "图像参数已写入摄像头", Toast.LENGTH_SHORT).show()
            }
            if (!canWrite) {
                binding.btnSave.isEnabled = true
                return@launch
            }
            val r = withContext(Dispatchers.IO) { runCatching { api.setVideoAudioConfig(d, cfg) }.getOrNull() }
            when (r) {
                is ApiResult.Success -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "参数已写入摄像头，录制/回放分辨率已更新为 ${cfg.width}×${cfg.height} 原画", Toast.LENGTH_LONG).show()
                    // 重新读取一次做校验
                    delay(600L)
                    reloadFromCamera()
                }
                is ApiResult.Error -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "写入失败：${r.message}", Toast.LENGTH_LONG).show()
                    binding.btnSave.isEnabled = true
                }
                is ApiResult.Unsupported -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "摄像头不支持：${r.feature}", Toast.LENGTH_LONG).show()
                    binding.btnSave.isEnabled = true
                }
                else -> {
                    Toast.makeText(this@VideoAudioConfigActivity, "写入失败（未知错误）", Toast.LENGTH_LONG).show()
                    binding.btnSave.isEnabled = true
                }
            }
        }
    }

    private fun delay(ms: Long) { Thread.sleep(ms) }
}
