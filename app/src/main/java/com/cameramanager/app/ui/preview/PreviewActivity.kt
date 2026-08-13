package com.cameramanager.app.ui.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityPreviewBinding
import com.cameramanager.app.net.NetworkRouter
import com.cameramanager.app.net.NetworkRouter.RouteResult
import com.cameramanager.app.rtsp.RtspPlayer
import com.cameramanager.app.service.FloatingWindowService
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.PreviewViewModel
import com.cameramanager.app.ui.detection.DetectionRegionActivity
import com.cameramanager.app.ui.manage.DeviceManageActivity
import com.cameramanager.app.ui.voice.VoiceIntercomActivity
import com.cameramanager.app.util.PermissionHelper
import com.cameramanager.app.util.PtzDirection
import com.cameramanager.app.util.StorageHelper
import com.cameramanager.app.vendor.CameraController
import com.cameramanager.app.vendor.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real-time preview screen.
 *
 * Capabilities:
 *  - HD RTSP preview with switchable resolution profile (HD / SD / smooth).
 *  - PTZ: 8-direction + zoom, press-hold to move, release to stop; preset
 *    viewpoints, one-key home, auto-cruise, AI human tracking.
 *  - Picture: flip / mirror, night-vision mode, privacy mask.
 *  - Real-time screenshot and manual local recording; floating window preview.
 *  - One-tap deterrence (white light + siren) and voice intercom entry.
 *  - Custom detection region drawing, device management (restart/firmware/self-check).
 *
 * 连接选路与防卡死（v2 新增）：
 *  - 进入预览前先调用 [NetworkRouter.resolve] 根据「当前 WiFi SSID」决定走
 *    内网 / 公网 / 穿透，并把命中的 host:port 交给 [RtspPlayer]。
 *  - 顶部显示当前路由标签（内网·xxx / 穿透·xxx / 公网·xxx）。
 *  - 播放超时/卡死时 [RtspPlayer] 会自动重连 3 次，仍失败则弹「重连」按钮，
 *    避免无脑重试把 App 卡死；用户点「重连」可手动恢复。
 *
 * All advanced operations route through [CameraController] which returns a
 * capability-aware result so unsupported features prompt the user.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val viewModel: PreviewViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var player: RtspPlayer
    private var device: Device? = null
    private var controller: CameraController? = null
    private var recording = false
    private var recordingStart = 0L
    private var presets: List<Preset> = emptyList()
    /** 最近一次选路结果，用于重连时复用同一地址。 */
    private var currentRoute: RouteResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 读取 WiFi SSID 需要精确定位权限，没有就无法判断是否在设备内网。
        PermissionHelper.request(this, PermissionHelper.ROUTE_PERMISSIONS, PermissionHelper.REQ_ROUTE)

        player = RtspPlayer(this).apply {
            listener = object : RtspPlayer.Listener {
                override fun onStateChanged(state: RtspPlayer.State) {
                    runOnUiThread {
                        binding.statusText.text = stateLabel(state)
                        if (state == RtspPlayer.State.PLAYING) {
                            binding.reconnectOverlay.visibility = View.GONE
                        }
                    }
                }
                override fun onError(message: String) {
                    runOnUiThread { Toast.makeText(this@PreviewActivity, message, Toast.LENGTH_SHORT).show() }
                }
                override fun onReconnecting(attempt: Int, max: Int) {
                    runOnUiThread {
                        binding.reconnectOverlay.visibility = View.VISIBLE
                        binding.reconnectHint.text = "网络异常，重连中($attempt/$max)…"
                        binding.btnReconnect.visibility = View.GONE
                        refreshTimeoutBadge()
                    }
                }
                override fun onStalled(timeoutCount: Int, lastError: String) {
                    runOnUiThread {
                        binding.reconnectOverlay.visibility = View.VISIBLE
                        binding.reconnectHint.text = "$lastError\n已停止自动重连，点击下方按钮手动恢复"
                        binding.btnReconnect.visibility = View.VISIBLE
                        refreshTimeoutBadge()
                    }
                }
            }
        }

        binding.btnReconnect.setOnClickListener {
            binding.reconnectOverlay.visibility = View.GONE
            player.manualReconnect()
        }

        setupSurface()
        setupControls()
        viewModel.load(intent.getLongExtra(EXTRA_DEVICE_ID, -1))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.device.collectLatest { dev ->
                    if (dev != null) bindDevice(dev)
                }
            }
        }
    }

    private fun setupSurface() {
        binding.surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                device?.let { startPlayback(it) }
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { player.stop() }
        })
    }

    private fun bindDevice(dev: Device) {
        val first = device == null
        device = dev
        binding.toolbar.title = dev.name
        binding.profileText.text = dev.profileLabel()
        binding.ptzPanel.visibility = if (dev.supportsPtz) View.VISIBLE else View.GONE
        binding.btnAudio.visibility = if (dev.supportsAudio) View.VISIBLE else View.GONE
        applyTransform()
        if (first) {
            lifecycleScope.launch {
                controller = CameraController(dev).also { it.refreshCapabilities() }
            }
        }
        if (!first) startPlayback(dev)
    }

    private fun startPlayback(dev: Device) {
        if (!binding.surface.holder.isCreating) return
        binding.routeText.text = "选路中…"
        binding.reconnectOverlay.visibility = View.GONE
        refreshTimeoutBadge()
        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { NetworkRouter.resolve(this@PreviewActivity, dev) }
            currentRoute = route
            binding.routeText.text = routeLabel(route)
            // 用选路后的 host:port 拼 RTSP URL；用户名/密码/路径仍取自设备配置。
            val url = dev.rtspUrl(useHost = route.host, usePort = route.rtspPort)
            player.play(binding.surface, url, dev.streamProfile)
        }
    }

    private fun routeLabel(route: RouteResult): String {
        val tag = when (route.type) {
            RouteResult.RouteType.LAN -> "内网"
            RouteResult.RouteType.TUNNEL -> "穿透"
            RouteResult.RouteType.PUBLIC -> "公网"
        }
        val suffix = if (!route.reachable) " · 不可达" else ""
        return "$tag·${route.label.substringAfter('·', route.label)}$suffix"
    }

    private fun refreshTimeoutBadge() {
        val count = player.getTimeoutCount()
        if (count > 0) {
            binding.timeoutText.visibility = View.VISIBLE
            binding.timeoutText.text = "超时: $count"
        } else {
            binding.timeoutText.visibility = View.GONE
        }
    }

    private fun applyTransform() {
        val dev = device ?: return
        val view = binding.surface
        view.scaleX = if (dev.mirrored) -1f else 1f
        view.rotation = dev.rotation.toFloat()
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f
    }

    private fun setupControls() {
        binding.btnProfile.setOnClickListener { showProfilePicker() }
        binding.btnRotate.setOnClickListener { viewModel.updateRotation((device?.rotation ?: 0) + 90) }
        binding.btnMirror.setOnClickListener { viewModel.toggleMirror() }
        binding.btnSnapshot.setOnClickListener { captureSnapshot() }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnDeterrence.setOnClickListener { exec { it.triggerSiren(true) } }
        binding.btnAudio.setOnClickListener {
            device?.let { startActivity(VoiceIntercomActivity.intent(this, it.id)) }
        }

        // PTZ directions: press to move, release to stop
        val ptzMap = mapOf(
            binding.ptzUp to PtzDirection.UP,
            binding.ptzDown to PtzDirection.DOWN,
            binding.ptzLeft to PtzDirection.LEFT,
            binding.ptzRight to PtzDirection.RIGHT,
            binding.ptzLeftUp to PtzDirection.LEFT_UP,
            binding.ptzRightUp to PtzDirection.RIGHT_UP,
            binding.ptzLeftDown to PtzDirection.LEFT_DOWN,
            binding.ptzRightDown to PtzDirection.RIGHT_DOWN,
            binding.ptzZoomIn to PtzDirection.ZOOM_IN,
            binding.ptzZoomOut to PtzDirection.ZOOM_OUT
        )
        ptzMap.forEach { (view, dir) ->
            view.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> exec { it.move(dir.pan, dir.tilt, dir.zoom) }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> exec { it.stop() }
                }
                true
            }
        }

        // Extended controls row
        binding.btnPreset.setOnClickListener { showPresetPicker() }
        binding.btnHome.setOnClickListener { exec { it.home() } }
        binding.btnCruise.setOnClickListener { exec { it.toggleCruise() } }
        binding.btnAutoTrack.setOnClickListener { exec { it.toggleAutoTrack() } }
        binding.btnNight.setOnClickListener { showNightVisionPicker() }
        binding.btnPrivacy.setOnClickListener { exec { it.setPrivacyMask(!(device?.privacyMask ?: false)) } }
        binding.btnWhiteLight.setOnClickListener { exec { it.setWhiteLight(true) } }
        binding.btnSiren.setOnClickListener { exec { it.triggerSiren(true) } }
        binding.btnFloating.setOnClickListener { openFloatingWindow() }
        binding.btnManage.setOnClickListener {
            device?.let { startActivity(DeviceManageActivity.intent(this, it.id)) }
        }
    }

    private fun showProfilePicker() {
        val labels = arrayOf("高清(主码流)", "标清(子码流)", "流畅")
        AlertDialog.Builder(this).setTitle("选择分辨率")
            .setItems(labels) { _, which -> viewModel.setStreamProfile(which) }.show()
    }

    private fun showNightVisionPicker() {
        val labels = arrayOf("智能夜视", "红外夜视", "全彩夜视")
        AlertDialog.Builder(this).setTitle("夜视模式")
            .setItems(labels) { _, which -> exec { it.setNightVision(which) } }.show()
    }

    private fun showPresetPicker() {
        val dev = device ?: return
        val controller = controller ?: return
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                com.cameramanager.app.vendor.CameraVendorApi.forDevice(dev).listPresets(dev)
            }
            val data = (list as? com.cameramanager.app.vendor.ApiResult.Success)?.data ?: emptyList()
            presets = data
            val items = data.map { "${it.index}. ${it.name}" }.toTypedArray()
            AlertDialog.Builder(this@PreviewActivity).setTitle("预置位")
                .setItems(items) { _, which ->
                    if (which < data.size) exec { it.gotoPreset(data[which].index) }
                }.setNeutralButton("保存当前") { _, _ -> savePresetDialog() }
                .show()
        }
    }

    private fun savePresetDialog() {
        val input = android.widget.EditText(this).apply { hint = "预置位名称" }
        AlertDialog.Builder(this).setTitle("保存预置位").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().ifEmpty { "预置位" }
                val idx = (presets.maxOfOrNull { it.index } ?: 0) + 1
                exec { it.savePreset(idx, name) }
            }.setNegativeButton("取消", null).show()
    }

    private fun captureSnapshot() {
        val bmp = player.captureFrame(binding.surface) ?: run {
            Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show(); return
        }
        val path = StorageHelper.saveScreenshot(this, bmp, device?.name ?: "camera")
        Toast.makeText(this, if (path != null) "已保存截图" else "保存失败", Toast.LENGTH_SHORT).show()
        if (path != null) {
            val dev = device ?: return
            lifecycleScope.launch {
                com.cameramanager.app.CameraApp.get().repository.addRecording(
                    com.cameramanager.app.data.model.Recording(
                        deviceId = dev.id, startTime = System.currentTimeMillis(),
                        endTime = System.currentTimeMillis(), trigger = "snapshot", filePath = path
                    )
                )
            }
        }
    }

    private fun toggleRecording() {
        if (recording) {
            recording = false
            binding.btnRecord.text = "录像"
            binding.recordIndicator.visibility = View.GONE
            com.cameramanager.app.service.CameraStreamService.stop(this)
            addRecordingEntry()
            Toast.makeText(this, "已停止录像", Toast.LENGTH_SHORT).show()
        } else {
            recording = true
            recordingStart = System.currentTimeMillis()
            binding.btnRecord.text = "停止"
            binding.recordIndicator.visibility = View.VISIBLE
            com.cameramanager.app.service.CameraStreamService.start(this, recording = true)
            Toast.makeText(this, "开始本地录像", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addRecordingEntry() {
        val dev = device ?: return
        lifecycleScope.launch {
            com.cameramanager.app.CameraApp.get().repository.addRecording(
                com.cameramanager.app.data.model.Recording(
                    deviceId = dev.id, startTime = recordingStart,
                    endTime = System.currentTimeMillis(), trigger = "manual",
                    filePath = StorageHelper.recordingsDir(this@PreviewActivity).absolutePath,
                    durationMs = System.currentTimeMillis() - recordingStart
                )
            )
        }
    }

    private fun openFloatingWindow() {
        val dev = device ?: return
        val route = currentRoute
        if (!FloatingWindowService.canDrawOverlays(this)) {
            startActivity(FloatingWindowService.overlayPermissionIntent(this))
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            return
        }
        // 悬浮窗也走同一选路结果，保证内外网一致。
        val url = if (route != null) dev.rtspUrl(useHost = route.host, usePort = route.rtspPort)
                  else dev.rtspUrl()
        FloatingWindowService.start(this, url)
        Toast.makeText(this, "已开启悬浮窗预览", Toast.LENGTH_SHORT).show()
    }

    /** Run a controller action on IO and toast the result. */
    private fun exec(action: suspend (CameraController) -> CameraController.CameraCommandResult) {
        val c = controller ?: run { Toast.makeText(this, "正在连接设备…", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action(c) }
            val msg = when (result) {
                is CameraController.CameraCommandResult.Ok -> null
                is CameraController.CameraCommandResult.OkWithMessage -> result.text
                is CameraController.CameraCommandResult.Unsupported -> result.message
                is CameraController.CameraCommandResult.Failed -> result.message
            }
            if (msg != null) Toast.makeText(this@PreviewActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stateLabel(state: RtspPlayer.State): String = when (state) {
        RtspPlayer.State.IDLE -> "空闲"
        RtspPlayer.State.OPENING -> "正在打开…"
        RtspPlayer.State.BUFFERING -> "缓冲中…"
        RtspPlayer.State.PLAYING -> "播放中"
        RtspPlayer.State.PAUSED -> "已暂停"
        RtspPlayer.State.STOPPED -> "已停止"
        RtspPlayer.State.ENDED -> "已结束"
        RtspPlayer.State.ERROR -> "播放错误"
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) toggleRecording()
        player.release()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, PreviewActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
