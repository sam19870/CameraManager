package com.cameramanager.app.ui.preview

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.audio.VoiceIntercom
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityPreviewBinding
import com.cameramanager.app.net.NetworkRouter
import com.cameramanager.app.net.NetworkRouter.RouteResult
import com.cameramanager.app.rtsp.RtspPlayer
import com.cameramanager.app.service.CameraStreamService
import com.cameramanager.app.service.FloatingWindowService
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.PreviewViewModel
import com.cameramanager.app.ui.playback.PlaybackActivity
import com.cameramanager.app.ui.settings.SettingsActivity
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

    // 内联对讲引擎
    private var voiceEngine: VoiceIntercom? = null
    private var voiceMode = VOICE_MODE_HOLD // 0=按住 1=电话

    companion object {
        private const val VOICE_MODE_HOLD = 0
        private const val VOICE_MODE_CALL = 1
        private const val REQ_MIC = 1001
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
                val dev = device
                if (dev == null || dev.id <= 0) {
                    Toast.makeText(this, "请先在设备列表中添加此摄像头后再进行设置", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val i = SettingsActivity.intent(this, dev.id)
                val component = i.resolveActivity(packageManager)
                if (component != null) startActivity(i)
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
        binding.deviceName.text = dev.name
        binding.statusText.text = dev.profileLabel()
        applyVisibilityByDevice(dev)
        applyTransform()
        syncStateFromDevice(dev, caps)
        if (first) {
            lifecycleScope.launch {
                controller = CameraController(dev).also {
                    caps = it.refreshCapabilities()
                    runOnUiThread {
                        applyVisibilityByCaps(caps)
                        syncStateFromDevice(dev, caps)
                    }
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

    /**
     * 核心：UI 显示的功能完全按摄像头能力集 caps 决定——
     * 摄像头支持啥按钮显示啥，不支持就 GONE，不会让用户按到"不支持"的功能。
     * 参照 TP-LINK / 乐橙官方 APP 做法。
     */
    private fun applyVisibilityByCaps(c: CameraCapabilities?) {
        if (c == null) return
        // 云台 & 变焦（ptz + zoom）
        binding.ptzPanel.visibility = if (c.ptz) View.VISIBLE else View.GONE
        // 在 ptzPanel 内部，zoom 按钮独立控制显示
        binding.ptzZoomOut.visibility = if (c.zoom) View.VISIBLE else View.GONE
        binding.ptzZoomIn.visibility = if (c.zoom) View.VISIBLE else View.GONE
        // 对讲：设备要有语音能力
        binding.btnAudio.visibility = if (c.voiceIntercom) View.VISIBLE else View.GONE
        // 预设位 / 巡航：在"更多"对话框中按能力动态显示
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
            // 播放前做一次"端口可达性"预检，出问题给用户更直白的说明（不再只说"连不上"）
            val reachable = withContext(Dispatchers.IO) {
                com.cameramanager.app.net.NetworkScanner.testReachable(route.host, route.rtspPort, 1200)
            }
            if (!reachable) {
                val rtspHint = buildString {
                    append("无法连上 ${route.host}:${route.rtspPort}（RTSP端口）\n")
                    append("请检查：\n")
                    append("1) 摄像头是否在同 WiFi 在线，端口${route.rtspPort}是否真开放\n")
                    append("2) 管理端口填的是 80/ONVIF，但 RTSP 默认 554——本 App 已自动分开使用，可到「设备设置→网络路由」确认\n")
                    append("3) RTSP 路径/账号密码不对会出现 401，也一样会无画面")
                }
                runOnUiThread {
                    binding.statusText.text = "端口不可达"
                    Toast.makeText(this@PreviewActivity, rtspHint, Toast.LENGTH_LONG).show()
                    binding.reconnectOverlay.visibility = View.VISIBLE
                    binding.reconnectProgress.visibility = View.GONE
                    binding.reconnectHint.text = "RTSP 端口(${route.rtspPort})连不上\n请确认摄像头已开机且 554 或自定义 RTSP 端口开放"
                    binding.btnReconnect.visibility = View.VISIBLE
                }
            }
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
            binding.statusText.text = "超时: $count"
            binding.statusText.visibility = View.VISIBLE
        } else {
            binding.statusText.visibility = View.GONE
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
            // 强触感反馈：KEYBOARD_TAP 比 CONTEXT_CLICK 更明显
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
    }

    private fun pressHold(v: View, name: String, onDown: suspend (CameraController) -> Unit, onUp: suspend (CameraController) -> Unit) {
        v.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 强力触感反馈：LONG_PRESS 振动最明显，确保用户知道"按住了"
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    com.cameramanager.app.util.LogCollector.log("Module", "[云台:$name] 按下")
                    lifecycleScope.launch {
                        runCatching { controller?.let { onDown(it) } }
                            .onFailure { com.cameramanager.app.util.LogCollector.logError("Module", "[云台:$name] 按下异常", it) }
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // 松手时也给一个短触感，确认松手成功
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    com.cameramanager.app.util.LogCollector.log("Module", "[云台:$name] 松开")
                    lifecycleScope.launch {
                        runCatching { controller?.let { onUp(it) } }
                            .onFailure { com.cameramanager.app.util.LogCollector.logError("Module", "[云台:$name] 松开异常", it) }
                    }
                }
            }
            true
        }
    }

    private fun setupControls() {
        // Tapo 第一行：截图 | 录像 | 画质 | 日夜 | 全屏
        tap(binding.btnSnapshot) { captureSnapshot() }
        tap(binding.btnRecord) { toggleRecording() }
        tap(binding.btnProfile) { showProfilePicker() }
        tap(binding.btnNightMode) { toggleNightMode() }
        tap(binding.btnFullScreen) { toggleFullScreen() }

        // Tapo 第二行：音量 | 语音通话 | 对讲 | 云台 | 告警 | 回放
        tap(binding.btnMute) { toggleMute() }
        tap(binding.btnVoiceCall) { toggleAudioPanel() }
        tap(binding.btnAudio) { toggleAudioPanel() }
        tap(binding.btnPtz) { togglePtzPanel() }
        tap(binding.btnAlarm) { toggleAlarm() }
        tap(binding.btnPlayback) { openPlayback() }

        // 右上角齿轮 → 设备设置
        tap(binding.btnMore) { openDeviceSettings() }

        // 对讲面板交互（TP-LINK 风格，不跳新页）
        tap(binding.audioClose) { hideAudioPanel(true) }
        binding.audioModeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioHoldTalk -> {
                    voiceMode = VOICE_MODE_HOLD
                    binding.radioHoldTalk.text = "按住说话模式"
                    binding.btnHoldToTalk.visibility = View.VISIBLE
                    binding.fullDuplexBar.visibility = View.GONE
                    // 切模式先挂断
                    stopVoice()
                }
                R.id.radioFullDuplex -> {
                    voiceMode = VOICE_MODE_CALL
                    binding.radioFullDuplex.text = "电话对讲模式"
                    binding.btnHoldToTalk.visibility = View.GONE
                    binding.fullDuplexBar.visibility = View.VISIBLE
                    stopVoice()
                }
            }
        }
        // 按住说话：ACTION_DOWN 开始录音，ACTION_UP/ACTION_CANCEL 结束
        binding.btnHoldToTalk.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    binding.btnHoldToTalk.text = "松 手 结 束"
                    binding.btnHoldToTalk.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(0xFFFF5722.toInt())
                    startVoiceIfPermitted()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.btnHoldToTalk.text = "按住 说话"
                    binding.btnHoldToTalk.backgroundTintList =
                        androidx.core.content.ContextCompat.getColorStateList(
                            this@PreviewActivity, com.cameramanager.app.R.color.brand_primary
                        )
                    stopVoice()
                    true
                }
                else -> false
            }
        }
        tap(binding.btnCallAnswer) { startVoiceIfPermitted() }
        tap(binding.btnCallHangup) { stopVoice() }

        pressHold(binding.ptzUp, "上",
            { it.move(0f, PtzDirection.UP.tilt) }, { it.stop() })
        pressHold(binding.ptzDown, "下",
            { it.move(0f, PtzDirection.DOWN.tilt) }, { it.stop() })
        pressHold(binding.ptzLeft, "左",
            { it.move(PtzDirection.LEFT.pan, 0f) }, { it.stop() })
        pressHold(binding.ptzRight, "右",
            { it.move(PtzDirection.RIGHT.pan, 0f) }, { it.stop() })
        pressHold(binding.ptzZoomIn, "变焦+",
            { it.move(0f, 0f, PtzDirection.ZOOM_IN.zoom) }, { it.stop() })
        pressHold(binding.ptzZoomOut, "变焦-",
            { it.move(0f, 0f, PtzDirection.ZOOM_OUT.zoom) }, { it.stop() })
    }

    private fun toggleMute() {
        val nowMuted = player.toggleMute()
        com.cameramanager.app.util.LogCollector.log("Module", "[静音] -> ${if (nowMuted) "已静音" else "已恢复声音"}")
        runOnUiThread {
            binding.muteLabel.text = if (nowMuted) "静音" else "有声"
            binding.muteIcon.alpha = if (nowMuted) 0.5f else 1.0f
        }
        toast(if (nowMuted) "已静音" else "已恢复声音")
    }

    private fun toggleNightMode() {
        val labels = arrayOf("自动", "红外", "全彩")
        val cur = (featureStates["nightVision"] as? Int) ?: 0
        val next = (cur + 1) % 3
        exec("夜视切换:${labels[next]}") { it.setNightVision(next) }
        featureStates["nightVision"] = next
        toast("夜视: ${labels[next]}")
    }

    private fun toggleFullScreen() {
        val flags = window.decorView.systemUiVisibility
        if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            binding.topBar.visibility = View.GONE
            toast("全屏模式")
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            binding.topBar.visibility = View.VISIBLE
            toast("已退出全屏")
        }
    }

    private fun toggleAlarm() {
        val cur = (featureStates["alarmOn"] as? Boolean) ?: true
        val next = !cur
        exec("告警开关") { ctl -> ctl.setDetectionSwitch("motion", next) }
        featureStates["alarmOn"] = next
        runOnUiThread {
            binding.alarmLabel.text = if (next) "告警开" else "告警关"
            binding.alarmIcon.alpha = if (next) 1.0f else 0.5f
        }
        toast(if (next) "告警已开启" else "告警已关闭")
    }

    private fun togglePtzPanel() {
        val wasVisible = binding.ptzPanel.visibility == View.VISIBLE
        if (wasVisible) {
            binding.ptzPanel.visibility = View.GONE
        } else {
            binding.audioPanel.visibility = View.GONE
            binding.ptzPanel.visibility = View.VISIBLE
            toast("云台控制已展开")
        }
    }

    private fun openPlayback() {
        runCatching {
            val d = device ?: return
            startActivity(PlaybackActivity.intent(this, d.id))
            com.cameramanager.app.util.LogCollector.log("Module", "[回放] 打开成功")
        }.onFailure { t ->
            com.cameramanager.app.util.LogCollector.logError("Module", "[回放] 打开失败", t)
            toast("打开回放失败: ${t.message}")
        }
    }

    private fun openDeviceSettings() { showMoreToolsPanel() }

    private fun showProfilePicker() {
        val d = device ?: return
        val main = d.mainRtspPath?.take(18) ?: "同默认"
        val sub = d.subRtspPath?.take(18) ?: "同默认"
        val labels = arrayOf(
            "原画·最高分辨率 (主码流·$main)",
            "标清·推荐预览 (子码流·$sub)",
            "流畅·弱网使用 (子码流)"
        )
        val checkedIdx = d.streamProfile.coerceIn(0, 2)
        AlertDialog.Builder(this).setTitle("选择清晰度 (切换后自动重连)")
            .setSingleChoiceItems(labels, checkedIdx) { dl, which ->
                // 选完立刻改存储并重连画面，让用户看到不同码流的效果
                lifecycleScope.launch {
                    runCatching { viewModel.setStreamProfile(which) }
                    val refreshed = withContext(Dispatchers.IO) {
                        CameraApp.get().repository.getDevice(d.id)
                    } ?: d.copy(streamProfile = which)
                    device = refreshed
                    binding.statusText.text = refreshed.profileLabel()
                    runOnUiThread { startPlayback(refreshed) }
                }
                dl.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ========= 更多功能：带状态开关的面板（TP-LINK 风格） =========
    private val featureStates = hashMapOf<String, Any>()

    private fun syncStateFromDevice(d: Device, c: CameraCapabilities?) {
        featureStates["autoTrack"] = d.autoTrack
        featureStates["privacyMask"] = d.privacyMask
    }

    /** 「更多」面板：每个开关都标注开关状态，选中切，长按解释。
     *  和之前的纯"动作列表"不同，这版一眼看得清当前是开是关。
     */
    private fun showMoreToolsPanel() {
        val ctx = this
        val dev = device ?: return
        val c = caps
        val rows = mutableListOf<Pair<String, (Boolean) -> Unit>>()
        val initChecked = mutableListOf<Boolean>()
        val labels = mutableListOf<String>()

        fun addSwitch(name: String, key: String, action: suspend (CameraController, Boolean) -> CameraController.CameraCommandResult) {
            labels.add(name)
            val st = (featureStates.getOrPut(key) { false } as? Boolean) ?: false
            initChecked.add(st)
            rows.add(name to { newVal ->
                featureStates[key] = newVal
                exec(name) { action(it, newVal) }
            })
        }

        // note: 已按用户要求去掉 一键复位
        if (c?.autoTrack == true) {
            val on = (featureStates["autoTrack"] as? Boolean) ?: false
            addSwitch("AI人形追踪（当前:${if (on) "开" else "关"}）", "autoTrack") { ctl, v ->
                ctl.toggleAutoTrack().also {
                    if (it is CameraController.CameraCommandResult.OkWithMessage) featureStates["autoTrack"] = v
                    else if (it is CameraController.CameraCommandResult.Ok) featureStates["autoTrack"] = v
                }
            }
        }
        if (c?.nightVision == true) {
            labels.add("夜视模式（切换:智能/红外/全彩）")
            initChecked.add(false)
            rows.add("夜视" to { _ -> showNightVisionPicker() })
        }
        if (c?.privacyMask == true) {
            val pm = (featureStates["privacyMask"] as? Boolean) ?: false
            addSwitch("隐私遮蔽（当前:${if (pm) "开" else "关"}）", "privacyMask") { ctl, v ->
                val r = ctl.setPrivacyMask(v)
                if (r !is CameraController.CameraCommandResult.Unsupported &&
                    r !is CameraController.CameraCommandResult.Failed) {
                    lifecycleScope.launch { viewModel.updatePrivacy(v) }
                    featureStates["privacyMask"] = v
                }
                r
            }
        }
        if (c?.whiteLight == true) {
            val wl = (featureStates["whiteLight"] as? Boolean) ?: false
            addSwitch("白光灯（当前:${if (wl) "开" else "关"}）", "whiteLight") { ctl, v ->
                ctl.setWhiteLight(v).also {
                    if (it !is CameraController.CameraCommandResult.Unsupported &&
                        it !is CameraController.CameraCommandResult.Failed)
                        featureStates["whiteLight"] = v
                }
            }
        }
        if (c?.siren == true) {
            labels.add("声光威慑（一次性触发）")
            initChecked.add(false)
            rows.add("威慑" to { _ ->
                exec("声光威慑") { it.triggerSiren(true); it.triggerSiren(false) }
            })
        }

        // 画面预览静音/音量（所有摄像头都支持，因为是播放端静音，不是摄像头端）
        labels.add(
            if (player.isMuted()) "画面声音（当前: 静音，点击恢复）"
            else "画面声音（当前: 有声，点击静音）"
        )
        initChecked.add(player.isMuted().not())
        rows.add("预览声音" to { _ ->
            val nowMuted = player.toggleMute()
            runOnUiThread { toast(if (nowMuted) "已静音预览画面" else "已恢复预览画面声音") }
        })

        // 对讲音量（若摄像头能力可配置）
        if (c?.audioConfig == true) {
            labels.add("对讲扬声器音量（点击调整）")
            initChecked.add(false)
            rows.add("扬声音量" to { _ ->
                val items = (0..100 step 10).map { "$it %" }.toTypedArray()
                AlertDialog.Builder(ctx).setTitle("摄像机扬声音量（对讲时播放音量）")
                    .setItems(items) { dl, w ->
                        val vol = w * 10
                        lifecycleScope.launch {
                            exec("调节扬声器音量:$vol") { it.setSpeakerVolume(vol); CameraController.CameraCommandResult.Ok }
                            withContext(Dispatchers.Main) { toast("扬声器音量: $vol%") }
                        }
                        dl.dismiss()
                    }.setNegativeButton("取消", null).show()
            })
            labels.add("收音麦克风音量（点击调整）")
            initChecked.add(false)
            rows.add("收音音量" to { _ ->
                val items = (0..100 step 10).map { "$it %" }.toTypedArray()
                AlertDialog.Builder(ctx).setTitle("摄像机收音音量（环境声采集）")
                    .setItems(items) { dl, w ->
                        val vol = w * 10
                        lifecycleScope.launch {
                            exec("调节麦克风音量:$vol") { it.setMicVolume(vol); CameraController.CameraCommandResult.Ok }
                            withContext(Dispatchers.Main) { toast("收音音量: $vol%") }
                        }
                        dl.dismiss()
                    }.setNegativeButton("取消", null).show()
            })
        }

        labels.add("悬浮窗预览")
        initChecked.add(false)
        rows.add("悬浮窗" to { _ -> openFloatingWindow() })

        labels.add("画面旋转 90°")
        initChecked.add(false)
        rows.add("旋转" to { _ ->
            lifecycleScope.launch { viewModel.updateRotation((device?.rotation ?: 0) + 90) }
        })

        labels.add("镜像翻转")
        initChecked.add(false)
        rows.add("镜像" to { _ -> lifecycleScope.launch { viewModel.toggleMirror() } })

        // 带复选框对话框，点击切换条目状态（开关切换 或 纯操作）
        val checkedArr = initChecked.toBooleanArray()
        AlertDialog.Builder(ctx).setTitle("更多功能")
            .setMultiChoiceItems(labels.toTypedArray(), checkedArr) { _, which, isChecked ->
                checkedArr[which] = isChecked
                val (name, fn) = rows[which]
                runCatching { fn(isChecked) }
            }
            .setPositiveButton("完成", null)
            .show()
    }

    private fun showNightVisionPicker() {
        val labels = arrayOf("智能夜视", "红外夜视", "全彩夜视")
        AlertDialog.Builder(this).setTitle("夜视模式")
            .setItems(labels) { _, which -> exec("日夜模式切换:${which}") { it.setNightVision(which) } }.show()
    }

    private fun showPresetPicker() {
        val dev = device ?: return
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                CameraVendorApi.forDevice(dev).listPresets(dev)
            }
            val data = (list as? ApiResult.Success)?.data?.filter { it.enabled }.orEmpty()
            presets = data

            // 空状态：弹窗里先给"暂无"提示和"新建预设位"按钮，而不是 8 个假点位
            if (data.isEmpty()) {
                AlertDialog.Builder(this@PreviewActivity)
                    .setTitle("预置位")
                    .setMessage("还没有任何预置位。\n把摄像头转到想保存的角度，点击「新建预置位」即可收藏当前位置。")
                    .setPositiveButton("新建预置位") { _, _ -> savePresetDialog() }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val items = data.map { "${it.index}. ${it.name}" }.toTypedArray()
            val ops = arrayOf("重命名选中项", "删除选中项", "保存当前为新预置位")
            AlertDialog.Builder(this@PreviewActivity).setTitle("预置位（点前N项跳转；后3项为操作）")
                .setItems(items + ops) { _, which ->
                    when {
                        which < data.size -> exec("云台预置位跳转") { it.gotoPreset(data[which].index) }
                        which == data.size -> renamePresetDialog(data)
                        which == data.size + 1 -> showDeletePresetDialog(data)
                        else -> savePresetDialog()
                    }
                }.setNeutralButton("关闭", null)
                .show()
        }
    }

    private fun renamePresetDialog(data: List<Preset>) {
        val labels = data.map { "${it.index}. ${it.name}" }.toTypedArray()
        var chosen = -1
        AlertDialog.Builder(this)
            .setTitle("选要重命名的预置位")
            .setSingleChoiceItems(labels, -1) { _, w -> chosen = w }
            .setPositiveButton("下一步") { _, _ ->
                if (chosen !in data.indices) { toast("请先选一个预置位"); return@setPositiveButton }
                val p = data[chosen]
                val et = android.widget.EditText(this).apply {
                    setText(p.name); hint = "预置位名称"
                }
                AlertDialog.Builder(this).setTitle("重命名预置位").setView(et)
                    .setPositiveButton("保存") { _, _ ->
                        val newName = et.text.toString().ifEmpty { p.name }
                        // 相同 index 重新保存 = 覆盖名称 (ONVIF/Tapo 通用语义)
                        exec("保存预置位") { it.savePreset(p.index, newName) }
                    }.setNegativeButton("取消", null).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun showDeletePresetDialog(data: List<Preset>) {
        val labels = data.map { "${it.index}. ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this@PreviewActivity)
            .setTitle("删除预置位")
            .setItems(labels) { _, which ->
                if (which in data.indices) exec("删除预置位") { it.deletePreset(data[which].index) }
            }.show()
    }

    private fun savePresetDialog() {
        val input = android.widget.EditText(this).apply { hint = "预置位名称" }
        AlertDialog.Builder(this).setTitle("保存预置位").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().ifEmpty { "预置位" }
                val idx = (presets.maxOfOrNull { it.index } ?: 0) + 1
                exec("保存预置位") { it.savePreset(idx, name) }
            }.setNegativeButton("取消", null).show()
    }

    private fun captureSnapshot() {
        val dev = device ?: return
        lifecycleScope.launch {
            // 尝试 ONVIF GetSnapshotUri 真实抓拍（走 ONVIF 的摄像头支持此方式，画质最高）
            if (dev.onvifPort > 0) {
                val api = CameraVendorApi.forDevice(dev)
                val bytes = withContext(Dispatchers.IO) { api.getSnapshot(dev) }
                if (bytes.isNotEmpty()) {
                    val path = StorageHelper.saveSnapshotBytes(this@PreviewActivity, bytes, dev.name)
                    com.cameramanager.app.util.LogCollector.log("Module", "[截图] ONVIF 抓拍 ${if (path != null) "已保存: $path" else "保存失败"}")
                    if (path != null) {
                        CameraApp.get().repository.addRecording(
                            com.cameramanager.app.data.model.Recording(
                                deviceId = dev.id, startTime = System.currentTimeMillis(),
                                endTime = System.currentTimeMillis(), trigger = "snapshot", filePath = path
                            )
                        )
                    }
                    Toast.makeText(this@PreviewActivity, if (path != null) "已保存截图" else "保存失败", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                com.cameramanager.app.util.LogCollector.log("Module", "[截图] ONVIF 抓拍失败，回退到软截图")
            }
            // 回退：libVLC 软截图
            val bmp = player.captureFrame()
            if (bmp == null) {
                com.cameramanager.app.util.LogCollector.log("Module", "[截图] 失败：captureFrame 返回 null（libVLC 不支持此机型）")
                Toast.makeText(this@PreviewActivity, "截图失败（libVLC暂不支持此机型）", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val path = StorageHelper.saveScreenshot(this@PreviewActivity, bmp, dev.name)
            com.cameramanager.app.util.LogCollector.log("Module", "[截图] ${if (path != null) "已保存: $path" else "保存到相册失败"}")
            Toast.makeText(this@PreviewActivity, if (path != null) "已保存截图" else "保存失败", Toast.LENGTH_SHORT).show()
            if (path != null) {
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
            binding.recordLabel.text = "录像"
            binding.recordIcon.setImageResource(R.drawable.ic_record)
            binding.recordIndicator.visibility = View.GONE
            CameraStreamService.stop(this)
            addRecordingEntry()
            com.cameramanager.app.util.LogCollector.log("Module", "[录像] 已停止并保存")
            Toast.makeText(this, "已停止录像", Toast.LENGTH_SHORT).show()
        } else {
            recording = true
            recordingStart = System.currentTimeMillis()
            binding.recordLabel.text = "停止"
            binding.recordIcon.setImageResource(R.drawable.ic_record)
            binding.recordIndicator.visibility = View.VISIBLE
            CameraStreamService.start(this, recording = true)
            com.cameramanager.app.util.LogCollector.log("Module", "[录像] 开始本地录像")
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

    /** 统一协议操作入口：每个模块的每次操作（正常/失败/不支持/异常）都记录到日志，
     *  方便排查哪个模块哪个功能出问题，无需用户口述。 */
    private fun exec(actionName: String, action: suspend (CameraController) -> CameraController.CameraCommandResult) {
        val c = controller ?: run {
            com.cameramanager.app.util.LogCollector.log("Module", "[$actionName] 未连接设备，已跳过")
            Toast.makeText(this, "正在连接设备…", Toast.LENGTH_SHORT).show(); return
        }
        com.cameramanager.app.util.LogCollector.log("Module", "[$actionName] 开始执行")
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { action(c) }
            } catch (e: Exception) {
                com.cameramanager.app.util.LogCollector.logError("Module", "[$actionName] 抛异常", e)
                Toast.makeText(this@PreviewActivity, "$actionName 异常: ${e.message}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val msg = when (result) {
                is CameraController.CameraCommandResult.Ok -> null
                is CameraController.CameraCommandResult.OkWithMessage -> result.text
                is CameraController.CameraCommandResult.Unsupported -> result.message
                is CameraController.CameraCommandResult.Failed -> result.message
            }
            val tag = result::class.simpleName ?: "?"
            com.cameramanager.app.util.LogCollector.log("Module", "[$actionName] -> $tag${if (msg != null) " | $msg" else " | OK"}")
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

    // ========= 对讲内联面板 =========
    private fun toggleAudioPanel() {
        if (binding.audioPanel.visibility == View.VISIBLE) {
            hideAudioPanel(true)
        } else {
            binding.audioPanel.visibility = View.VISIBLE
            binding.audioModeGroup.check(
                if (voiceMode == VOICE_MODE_HOLD) R.id.radioHoldTalk else R.id.radioFullDuplex
            )
        }
    }

    private fun hideAudioPanel(stop: Boolean) {
        binding.audioPanel.visibility = View.GONE
        if (stop) stopVoice()
    }

    private fun startVoiceIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val dev = device ?: return
        runCatching {
            if (voiceEngine == null) {
                voiceEngine = VoiceIntercom(dev, VoiceIntercom.Transport.TCP)
            }
            val ok = voiceEngine?.start() == true
            if (!ok) {
                toast("对讲启动失败：摄像头可能未开放对讲TCP端口 (RTSP端口+2) 或不支持裸PCM通道")
            } else {
                if (voiceMode == VOICE_MODE_CALL) {
                    binding.btnCallAnswer.text = "通话中…"
                    binding.btnCallAnswer.isEnabled = false
                    binding.btnCallHangup.isEnabled = true
                }
            }
        }.onFailure { toast("对讲异常: ${it.message}") }
    }

    private fun stopVoice() {
        runCatching { voiceEngine?.stop() }
        voiceEngine = null
        binding.btnCallAnswer.text = "拨  通"
        binding.btnCallAnswer.isEnabled = true
        binding.btnCallHangup.isEnabled = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoiceIfPermitted()
        } else if (requestCode == REQ_MIC) {
            toast("需要麦克风权限才能对讲")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { stopVoice() }
        if (recording) runCatching { toggleRecording() }
        runCatching { player.release() }
    }
}
