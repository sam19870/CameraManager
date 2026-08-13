package com.cameramanager.app.ui.preview

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cameramanager.app.CameraApp
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityPreviewBinding
import com.cameramanager.app.net.NetworkRouter
import com.cameramanager.app.net.NetworkRouter.RouteResult
import com.cameramanager.app.rtsp.RtspPlayer
import com.cameramanager.app.service.CameraStreamService
import com.cameramanager.app.service.FloatingWindowService
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.PreviewViewModel
import com.cameramanager.app.ui.settings.SettingsActivity
import com.cameramanager.app.ui.voice.VoiceIntercomActivity
import com.cameramanager.app.util.PermissionHelper
import com.cameramanager.app.util.PtzDirection
import com.cameramanager.app.util.StorageHelper
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraCapabilities
import com.cameramanager.app.vendor.CameraController
import com.cameramanager.app.vendor.CameraVendorApi
import com.cameramanager.app.vendor.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏沉浸式实时预览（参考乐橙 / TP-Link App）。
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val viewModel: PreviewViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var player: RtspPlayer
    private var device: Device? = null
    private var controller: CameraController? = null
    private var caps: CameraCapabilities? = null
    private var recording = false
    private var recordingStart = 0L
    private var presets: List<Preset> = emptyList()
    private var currentRoute: RouteResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                        binding.reconnectProgress.visibility = View.VISIBLE
                        binding.reconnectHint.text = "网络异常，重连中($attempt/$max)…"
                        binding.btnReconnect.visibility = View.GONE
                        refreshTimeoutBadge()
                    }
                }
                override fun onStalled(timeoutCount: Int, lastError: String) {
                    runOnUiThread {
                        binding.reconnectOverlay.visibility = View.VISIBLE
                        binding.reconnectProgress.visibility = View.GONE
                        binding.reconnectHint.text = "$lastError\n已停止自动重连，点击手动恢复"
                        binding.btnReconnect.visibility = View.VISIBLE
                        refreshTimeoutBadge()
                    }
                }
            }
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener {
            runCatching {
                device?.let {
                    val i = SettingsActivity.intent(this, it.id)
                    val component = i.resolveActivity(packageManager)
                    if (component != null) startActivity(i)
                }
            }.onFailure { t ->
                val msg = "打开设置失败: ${t.message ?: ""}".take(42)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnReconnect.setOnClickListener {
            binding.reconnectOverlay.visibility = View.GONE
            player.manualReconnect()
        }

        setupControls()

        val tempDev = intent.getParcelableExtra<Device>(EXTRA_TEMP_DEVICE)
        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
        if (tempDev != null) {
            // 免添加临时预览：直接 bind，不进 Room
            bindDevice(tempDev)
        } else {
            viewModel.load(deviceId)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.device.collectLatest { dev ->
                        if (dev != null) bindDevice(dev)
                    }
                }
            }
        }
    }

    private fun bindDevice(dev: Device) {
        val first = device == null
        device = dev
        binding.titleText.text = dev.name
        binding.profileText.text = dev.profileLabel()
        applyVisibilityByDevice(dev)
        applyTransform()
        if (first) {
            lifecycleScope.launch {
                controller = CameraController(dev).also {
                    caps = it.refreshCapabilities()
                    runOnUiThread { applyVisibilityByCaps(caps) }
                }
            }
            binding.videoLayout.post { startPlayback(dev) }
        } else {
            startPlayback(dev)
        }
    }

    private fun applyVisibilityByDevice(d: Device) {
        binding.ptzPanel.visibility = if (d.supportsPtz) View.VISIBLE else View.GONE
        binding.btnAudio.visibility = if (d.supportsAudio) View.VISIBLE else View.GONE
    }

    private fun applyVisibilityByCaps(c: CameraCapabilities?) {
        if (c == null) return
        binding.ptzPanel.visibility = if (c.ptz) View.VISIBLE else View.GONE
        binding.btnAudio.visibility = if (c.voiceIntercom) View.VISIBLE else View.GONE
    }

    private fun startPlayback(dev: Device) {
        binding.routeText.text = "选路中…"
        binding.reconnectOverlay.visibility = View.GONE
        refreshTimeoutBadge()
        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { NetworkRouter.resolve(this@PreviewActivity, dev) }
            currentRoute = route
            binding.routeText.text = routeLabel(route)
            val url = dev.rtspUrl(useHost = route.host, usePort = route.rtspPort)
            player.play(binding.videoLayout, url, dev.streamProfile)
        }
    }

    private fun routeLabel(route: RouteResult): String {
        val tag = when (route.type) {
            NetworkRouter.RouteType.LAN -> "内网"
            NetworkRouter.RouteType.TUNNEL -> "穿透"
            NetworkRouter.RouteType.PUBLIC -> "公网"
        }
        val suffix = if (!route.reachable) " · 不可达" else ""
        val short = route.label.substringAfter('·', route.label)
        return "$tag·$short$suffix"
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
        val v = binding.videoLayout
        v.scaleX = if (dev.mirrored) -1f else 1f
        v.rotation = dev.rotation.toFloat()
        v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
    }

    private fun tap(v: View, action: () -> Unit) {
        v.setOnClickListener {
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            action()
        }
    }

    private fun pressHold(v: View, onDown: suspend (CameraController) -> Unit, onUp: suspend (CameraController) -> Unit) {
        v.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    lifecycleScope.launch { controller?.let { onDown(it) } }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    lifecycleScope.launch { controller?.let { onUp(it) } }
                }
            }
            true
        }
    }

    private fun setupControls() {
        tap(binding.btnSnapshot) { captureSnapshot() }
        tap(binding.btnRecord) { toggleRecording() }
        tap(binding.btnAudio) {
            device?.let { startActivity(VoiceIntercomActivity.intent(this, it.id)) }
        }
        tap(binding.btnProfile) { showProfilePicker() }
        tap(binding.btnMoreTools) { showMoreTools() }
        tap(binding.btnPreset) { showPresetPicker() }
        tap(binding.btnCruise) { exec { it.toggleCruise() } }

        pressHold(binding.ptzUp,
            { it.move(0f, PtzDirection.UP.tilt) }, { it.stop() })
        pressHold(binding.ptzDown,
            { it.move(0f, PtzDirection.DOWN.tilt) }, { it.stop() })
        pressHold(binding.ptzLeft,
            { it.move(PtzDirection.LEFT.pan, 0f) }, { it.stop() })
        pressHold(binding.ptzRight,
            { it.move(PtzDirection.RIGHT.pan, 0f) }, { it.stop() })
        pressHold(binding.ptzZoomIn,
            { it.move(0f, 0f, PtzDirection.ZOOM_IN.zoom) }, { it.stop() })
        pressHold(binding.ptzZoomOut,
            { it.move(0f, 0f, PtzDirection.ZOOM_OUT.zoom) }, { it.stop() })
    }

    private fun showProfilePicker() {
        val labels = arrayOf("高清(主码流)", "标清(子码流)", "流畅")
        AlertDialog.Builder(this).setTitle("选择清晰度")
            .setItems(labels) { _, which -> viewModel.setStreamProfile(which) }.show()
    }

    private fun showMoreTools() {
        val items = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()
        val c = caps

        items.add("一键复位"); actions.add { exec { it.home() } }
        if (c?.autoTrack == true) { items.add("AI追踪"); actions.add { exec { it.toggleAutoTrack() } } }
        if (c?.nightVision == true) { items.add("夜视模式"); actions.add { showNightVisionPicker() } }
        if (c?.privacyMask == true) { items.add("隐私遮蔽"); actions.add { exec { it.setPrivacyMask(!(device?.privacyMask ?: false)) } } }
        if (c?.whiteLight == true) { items.add("白光灯"); actions.add { exec { it.setWhiteLight(true) } } }
        if (c?.siren == true) { items.add("声光威慑"); actions.add { exec { it.triggerSiren(true) } } }
        items.add("悬浮窗预览"); actions.add { openFloatingWindow() }
        items.add("画面旋转"); actions.add { viewModel.updateRotation((device?.rotation ?: 0) + 90) }
        items.add("镜像翻转"); actions.add { viewModel.toggleMirror() }

        AlertDialog.Builder(this).setTitle("更多功能")
            .setItems(items.toTypedArray()) { _, i -> actions[i]() }.show()
    }

    private fun showNightVisionPicker() {
        val labels = arrayOf("智能夜视", "红外夜视", "全彩夜视")
        AlertDialog.Builder(this).setTitle("夜视模式")
            .setItems(labels) { _, which -> exec { it.setNightVision(which) } }.show()
    }

    private fun showPresetPicker() {
        val dev = device ?: return
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                CameraVendorApi.forDevice(dev).listPresets(dev)
            }
            val data = (list as? ApiResult.Success)?.data ?: emptyList()
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
        val bmp = player.captureFrame() ?: run {
            Toast.makeText(this, "截图失败（libVLC暂不支持此机型）", Toast.LENGTH_SHORT).show(); return
        }
        val path = StorageHelper.saveScreenshot(this, bmp, device?.name ?: "camera")
        Toast.makeText(this, if (path != null) "已保存截图" else "保存失败", Toast.LENGTH_SHORT).show()
        if (path != null) {
            val dev = device ?: return
            lifecycleScope.launch {
                CameraApp.get().repository.addRecording(
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
            CameraStreamService.stop(this)
            addRecordingEntry()
            Toast.makeText(this, "已停止录像", Toast.LENGTH_SHORT).show()
        } else {
            recording = true
            recordingStart = System.currentTimeMillis()
            binding.btnRecord.text = "停止"
            binding.recordIndicator.visibility = View.VISIBLE
            CameraStreamService.start(this, recording = true)
            Toast.makeText(this, "开始本地录像", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addRecordingEntry() {
        val dev = device ?: return
        lifecycleScope.launch {
            CameraApp.get().repository.addRecording(
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
        val url = if (route != null) dev.rtspUrl(useHost = route.host, usePort = route.rtspPort)
                  else dev.rtspUrl()
        FloatingWindowService.start(this, url)
        Toast.makeText(this, "已开启悬浮窗预览", Toast.LENGTH_SHORT).show()
    }

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
        RtspPlayer.State.OPENING -> "连接中…"
        RtspPlayer.State.BUFFERING -> "缓冲中…"
        RtspPlayer.State.PLAYING -> "在线"
        RtspPlayer.State.PAUSED -> "已暂停"
        RtspPlayer.State.STOPPED -> "已停止"
        RtspPlayer.State.ENDED -> "已结束"
        RtspPlayer.State.ERROR -> "连接失败"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        if (recording) toggleRecording()
        player.release()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_TEMP_DEVICE = "temp_device"

        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, PreviewActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)

        /**
         * 免添加直预览入口（局域网扫描发现后，还没入库，填完账号密码走这里）。
         * Device 用 Parcelable 序列化直接传，不进 Room，PreviewActivity 照样能播。
         */
        fun intentTemp(context: Context, tempDevice: Device): Intent =
            Intent(context, PreviewActivity::class.java)
                .putExtra(EXTRA_TEMP_DEVICE, tempDevice)
                .putExtra(EXTRA_DEVICE_ID, -1L)
    }
}
