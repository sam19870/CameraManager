package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivitySettingsBinding
import com.cameramanager.app.databinding.DialogRouteBinding
import com.cameramanager.app.databinding.ItemSettingRowBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.SettingsViewModel
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraCapabilities
import com.cameramanager.app.vendor.CameraVendorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 摄像头设置页 - 1:1 参考 TP-LINK 物联 App 官方布局：
 *
 *  消息与报警：消息提醒 / 摄像机报警
 *  智能侦测  ：人形侦测 / 移动侦测 / 视频遮挡 / 越界侦测 / 区域入侵（彩色圆标宫格）
 *  设备控制  ：跳转 [DeviceControlActivity]（状态指示灯/画面翻转/宽动态/视频参数/OSD/通话模式/音量/夜视照明）
 *  存储      ：云存储 / SD卡录像
 *  智能工具  ：智能追踪 / 个性语音提示 / 掉线提醒
 *  侦测与网络：侦测规则 / 自定义区域 / 告警记录 / 内网穿透路由
 *  设备管理  ：固件升级 / 重启 / 自检 / 设备信息 / 删除
 *
 * 能力探测（NVR式）：后台 queryCapabilities，不支持的功能灰显并提示。
 * 防闪退：onCreate 全 try-catch + 所有跳转 safeStart + 全局 CrashGuard 兜底。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: RuleAdapter

    private var device: Device? = null
    private var capabilities = CameraCapabilities()
    private var capabilityQueried = false

    private val detectState = mutableMapOf<String, Boolean>()
    private val detectViews = mutableMapOf<String, View>()
    private var notifyOn = true
    private var alarmOn = true
    private var voiceTipOn = false
    private var offlineTipOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            binding.toolbar.setNavigationOnClickListener { finish() }

            val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)

            adapter = RuleAdapter(
                onClick = { safeStart(DetectionRuleActivity.intent(this, deviceId, it.id)) },
                onToggle = { rule, enabled -> viewModel.saveRule(rule.copy(enabled = enabled)) },
                onDelete = { viewModel.deleteRule(it) }
            )

            buildDetectGrid()
            bindCommonRows(deviceId)

            lifecycleScope.launch {
                runCatching {
                    device = withContext(Dispatchers.IO) { CameraApp.get().repository.getDevice(deviceId) }
                    device?.let { d ->
                        bindDeviceInfo(d)
                        bindDeviceRows(deviceId, d)
                        queryCapabilities(d)
                    }
                }.onFailure { t -> Log.w(TAG, "load device failed: ${t.message}", t) }
                viewModel.rules(deviceId).collectLatest { adapter.submit(it) }
            }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("设置页初始化失败: ${t.message}")
            finish()
        }
    }

    // ================= 设备头部 =================
    private fun bindDeviceInfo(d: Device) {
        binding.tvDeviceName.text = d.name
        binding.tvDeviceAddr.text = "(${d.host}:${d.port})"
        binding.ivThumb.setImageResource(R.drawable.ic_camera)
        binding.ivThumb.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
    }

    // ================= 智能侦测宫格（官方彩色圆标） =================
    private fun buildDetectGrid() {
        addDetectItem(android.R.drawable.ic_menu_myplaces, R.color.icon_orange, "人形侦测", "human", true)
        addDetectItem(android.R.drawable.ic_menu_rotate, R.color.icon_teal, "移动侦测", "motion", true)
        addDetectItem(android.R.drawable.ic_menu_close_clear_cancel, R.color.icon_gray, "视频遮挡", "block", false)
        addDetectItem(android.R.drawable.ic_menu_sort_by_size, R.color.icon_gray, "越界侦测", "crossing", false)
        addDetectItem(android.R.drawable.ic_menu_edit, R.color.icon_gray, "区域入侵", "intrusion", false)
    }

    private fun addDetectItem(iconRes: Int, colorRes: Int, label: String, type: String, enabled: Boolean) {
        runCatching {
            val v = LayoutInflater.from(this).inflate(R.layout.item_detect_grid, binding.gridDetect, false)
            val iv = v.findViewById<ImageView>(R.id.ivIcon)
            v.findViewById<TextView>(R.id.tvLabel).text = label
            iv.setImageResource(iconRes)
            val showColor = if (enabled) colorRes else R.color.icon_gray
            iv.backgroundTintList = ColorStateList.valueOf(getColor(showColor))
            detectState[type] = enabled
            detectViews[type] = v
            v.setOnClickListener { onDetectItemClick(type) }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            params.width = 0
            v.layoutParams = params
            binding.gridDetect.addView(v)
        }
    }

    private fun onDetectItemClick(type: String) {
        if (type == "block" || type == "crossing") {
            unsupportedToast()
            return
        }
        val next = detectState[type] != true
        detectState[type] = next
        val iv = detectViews[type]?.findViewById<ImageView>(R.id.ivIcon) ?: return
        val colorRes = when (type) {
            "human" -> R.color.icon_orange
            "motion" -> R.color.icon_teal
            else -> R.color.icon_purple
        }
        iv.backgroundTintList = ColorStateList.valueOf(getColor(if (next) colorRes else R.color.icon_gray))
        device?.let { d ->
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        CameraVendorApi.forDevice(d).setDetectionSwitch(d, type, next)
                    }
                }
            }
        }
        toast(if (next) "已开启" else "已关闭")
    }

    // ================= 通用行（不依赖设备对象） =================
    private fun bindCommonRows(deviceId: Long) {
        // 消息与报警
        setRow(binding.rowNotify, android.R.drawable.ic_dialog_alert, R.color.icon_blue, "消息提醒", value = "已开启")
        binding.rowNotify.root.setOnClickListener {
            notifyOn = !notifyOn
            binding.rowNotify.tvValue.text = if (notifyOn) "已开启" else "已关闭"
            toast(if (notifyOn) "消息提醒已开启" else "消息提醒已关闭")
        }
        setRow(binding.rowAlarm, android.R.drawable.ic_lock_silent_mode_off, R.color.icon_orange, "摄像机报警", value = "已开启")
        binding.rowAlarm.root.setOnClickListener {
            alarmOn = !alarmOn
            binding.rowAlarm.tvValue.text = if (alarmOn) "已开启" else "已关闭"
            toast(if (alarmOn) "摄像机报警已开启" else "摄像机报警已关闭")
        }

        // 侦测与网络
        setRow(binding.rowRules, android.R.drawable.ic_menu_recent_history, R.color.icon_teal, "侦测规则",
            subtitle = "人形/移动/人脸侦测与触发动作")
        binding.rowRules.root.setOnClickListener { showRulesBottomSheet(deviceId) }

        setRow(binding.rowRegion, android.R.drawable.ic_menu_crop, R.color.icon_purple, "自定义侦测区域",
            subtitle = "只在框选区域内触发告警")
        binding.rowRegion.root.setOnClickListener {
            safeStart(com.cameramanager.app.ui.detection.DetectionRegionActivity.intent(this, deviceId))
        }

        setRow(binding.rowAlarmLog, android.R.drawable.ic_menu_agenda, R.color.icon_orange, "告警记录",
            subtitle = "查看历史告警与截图")
        binding.rowAlarmLog.root.setOnClickListener { safeStart(AlarmLogActivity.intent(this, deviceId)) }

        setRow(binding.rowRoute, android.R.drawable.ic_menu_info_details, R.color.icon_blue, "内网 / 穿透路由",
            subtitle = "绑定 SSID 或穿透通道，内网/公网自动切换")
        binding.rowRoute.root.setOnClickListener { device?.let { showRouteDialog(it) } }
    }

    // ================= 依赖设备的行 =================
    private fun bindDeviceRows(deviceId: Long, d: Device) {
        // 设备控制 -> 子页
        setRow(binding.rowDeviceControl, android.R.drawable.ic_menu_preferences, R.color.icon_blue, "设备控制")
        binding.rowDeviceControl.root.setOnClickListener {
            safeStart(DeviceControlActivity.intent(this, deviceId))
        }

        // 存储
        setRow(binding.rowCloud, android.R.drawable.ic_menu_upload, R.color.icon_blue, "云存储", value = "未开启")
        binding.rowCloud.root.setOnClickListener { unsupportedToast() }

        setRow(binding.rowSd, android.R.drawable.ic_menu_save, R.color.icon_teal, "SD卡录像", value = "已开启")
        binding.rowSd.root.setOnClickListener {
            val items = arrayOf("全天持续录像", "移动侦测触发", "定时录像")
            AlertDialog.Builder(this).setTitle("SD卡录像模式")
                .setItems(items) { _, w ->
                    binding.rowSd.tvValue.text = "已开启"
                    toast("录像模式：${items[w]}")
                    device?.let { dev ->
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    CameraVendorApi.forDevice(dev)
                                        .setRecordingMode(dev, if (w == 0) "continuous" else "motion")
                                }
                            }
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }

        // 智能工具
        setRow(binding.rowTrack, android.R.drawable.ic_menu_compass, R.color.icon_teal, "智能追踪",
            subtitle = "检测到移动物体时，摄像机自动转动镜头跟踪定位",
            value = if (d.autoTrack) "已开启" else "未开启")
        binding.rowTrack.root.setOnClickListener {
            val next = binding.rowTrack.tvValue.text != "已开启"
            binding.rowTrack.tvValue.text = if (next) "已开启" else "未开启"
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        CameraApp.get().repository.updateDevice(d.copy(autoTrack = next))
                        CameraVendorApi.forDevice(d).setAutoTrack(d, next)
                    }
                }
                toast(if (next) "智能追踪已开启" else "智能追踪已关闭")
            }
        }

        setRow(binding.rowVoiceTip, android.R.drawable.ic_btn_speak_now, R.color.icon_purple, "个性语音提示",
            subtitle = "物体进出参考线时播放语音", value = if (voiceTipOn) "已开启" else "已关闭")
        binding.rowVoiceTip.root.setOnClickListener {
            voiceTipOn = !voiceTipOn
            binding.rowVoiceTip.tvValue.text = if (voiceTipOn) "已开启" else "已关闭"
            toast(if (voiceTipOn) "个性语音提示已开启" else "个性语音提示已关闭")
        }

        setRow(binding.rowOffline, android.R.drawable.ic_dialog_email, R.color.icon_orange, "掉线提醒",
            subtitle = "侦测到设备掉线时，手机会收到消息提醒", value = if (offlineTipOn) "已开启" else "已关闭")
        binding.rowOffline.root.setOnClickListener {
            offlineTipOn = !offlineTipOn
            binding.rowOffline.tvValue.text = if (offlineTipOn) "已开启" else "已关闭"
            toast(if (offlineTipOn) "掉线提醒已开启" else "掉线提醒已关闭")
        }

        // 设备管理
        setRow(binding.rowFirmware, android.R.drawable.ic_menu_upload_you_tube, R.color.icon_blue, "固件升级",
            subtitle = "检查摄像头最新固件")
        binding.rowFirmware.root.setOnClickListener {
            if (capabilityQueried && !capabilities.firmwareUpgrade) { unsupportedToast(); return@setOnClickListener }
            lifecycleScope.launch {
                val info = runCatching {
                    withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).checkFirmware(d) }
                }.getOrNull()
                val msg = when (info) {
                    is ApiResult.Success -> {
                        val fi = info.data
                        "当前版本：${fi.current}\n最新版本：${fi.latest}\n" +
                                if (fi.upgradeAvailable) "检测到新版本，是否升级？" else "已是最新版本"
                    }
                    is ApiResult.Unsupported -> getString(R.string.unsupported_hint)
                    is ApiResult.Error -> "查询失败：${info.message}"
                    null -> "查询超时，请稍后再试"
                }
                AlertDialog.Builder(this@SettingsActivity).setTitle("固件升级")
                    .setMessage(msg).setPositiveButton("关闭", null).show()
            }
        }

        setRow(binding.rowReboot, android.R.drawable.ic_menu_today, R.color.icon_orange, "重启摄像头")
        binding.rowReboot.root.setOnClickListener {
            AlertDialog.Builder(this).setTitle("重启摄像头")
                .setMessage("摄像头将重启，约 60 秒后恢复。确定？")
                .setPositiveButton("重启") { _, _ ->
                    lifecycleScope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).reboot(d) }
                        }.getOrNull()
                        when (r) {
                            is ApiResult.Success -> toast("重启指令已发送")
                            is ApiResult.Error -> toast("重启失败：${r.message}")
                            else -> unsupportedToast()
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }

        setRow(binding.rowSelfCheck, android.R.drawable.ic_menu_search, R.color.icon_teal, "设备自检",
            subtitle = "网络/SD卡/温度等状态")
        binding.rowSelfCheck.root.setOnClickListener {
            lifecycleScope.launch {
                val r = runCatching {
                    withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).selfCheck(d) }
                }.getOrNull()
                val msg = when (r) {
                    is ApiResult.Success -> buildString {
                        val s = r.data
                        append("在线：${if (s.online) "正常" else "离线"}\n")
                        append("SD 卡：${if (s.sdCardOk) "正常" else "异常"}\n")
                        append("网络强度：${s.networkRssi} dBm\n")
                        append("温度：${s.temperatureC} ℃\n")
                        if (s.issues.isNotEmpty()) {
                            append("\n发现问题：\n")
                            s.issues.forEach { append("• $it\n") }
                        }
                    }
                    is ApiResult.Unsupported -> getString(R.string.unsupported_hint)
                    is ApiResult.Error -> "自检失败：${r.message}"
                    null -> "自检超时，请稍后再试"
                }
                AlertDialog.Builder(this@SettingsActivity).setTitle("设备自检报告")
                    .setMessage(msg).setPositiveButton("关闭", null).show()
            }
        }

        setRow(binding.rowDeviceInfo, android.R.drawable.ic_menu_more, R.color.icon_gray, "设备信息")
        binding.rowDeviceInfo.root.setOnClickListener {
            val info = buildString {
                append("名称：${d.name}\n")
                append("厂商：${when (d.vendor) { "tapo" -> "TP-Link Tapo"; "imou" -> "乐橙 Imou"; else -> "通用ONVIF" }}\n")
                append("内网：${d.host}:${d.port}\n")
                append("ONVIF 端口：${if (d.onvifPort > 0) d.onvifPort.toString() else "不支持"}\n")
                append("RTSP 路径：${d.rtspPath}\n")
                append("云台：${if (d.supportsPtz) "支持" else "不支持"}\n")
                append("音频：${if (d.supportsAudio) "支持" else "不支持"}\n")
                if (!d.lanSsid.isNullOrEmpty()) append("内网 SSID：${d.lanSsid}\n")
                if (d.tunnelId > 0) append("绑定穿透：ID=${d.tunnelId}\n")
                if (!d.publicHost.isNullOrEmpty()) append("公网：${d.publicHost}:${d.publicPort}\n")
            }
            AlertDialog.Builder(this).setTitle("设备信息")
                .setMessage(info).setPositiveButton("关闭", null).show()
        }

        setRow(binding.rowDelete, android.R.drawable.ic_menu_delete, R.color.error_red, "删除设备")
        binding.rowDelete.tvTitle.setTextColor(getColor(R.color.alarm_red))
        binding.rowDelete.ivArrow.visibility = View.GONE
        binding.rowDelete.root.setOnClickListener {
            AlertDialog.Builder(this).setTitle("删除设备")
                .setMessage("删除后相关侦测规则、告警记录与录像信息将一并清除。确定删除「${d.name}」吗？")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        runCatching { viewModel.deleteDevice(d.id) }
                        finish()
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    // ================= 能力探测 =================
    private fun queryCapabilities(d: Device) {
        binding.tvCapStatus.text = "能力探测中…"
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).queryCapabilities(d) }
            }.getOrDefault(ApiResult.Success(CameraCapabilities(
                ptz = d.supportsPtz, voiceIntercom = d.supportsAudio
            )))
            when (result) {
                is ApiResult.Success -> {
                    capabilities = result.data
                    capabilityQueried = true
                    binding.tvCapStatus.text = "能力已就绪"
                    binding.tvCapStatus.setTextColor(getColor(R.color.online_green))
                    applyCapabilityVisibility(capabilities)
                }
                is ApiResult.Unsupported -> {
                    binding.tvCapStatus.text = "通用模式"
                    binding.tvCapStatus.setTextColor(getColor(R.color.text_secondary))
                }
                is ApiResult.Error -> {
                    binding.tvCapStatus.text = "探测离线"
                    binding.tvCapStatus.setTextColor(getColor(R.color.text_tertiary))
                }
            }
        }
    }

    private fun applyCapabilityVisibility(cap: CameraCapabilities) {
        binding.rowTrack.root.alpha = if (cap.autoTrack) 1f else 0.45f
        binding.rowSd.root.alpha = if (cap.tfStorage) 1f else 0.45f
    }

    // ================= 行辅助 =================
    private fun setRow(row: ItemSettingRowBinding, iconRes: Int, tintRes: Int, title: String,
                       subtitle: String? = null, value: String? = null) {
        row.ivIcon.setImageResource(iconRes)
        row.ivIcon.imageTintList = ColorStateList.valueOf(getColor(tintRes))
        row.tvTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            row.tvSubtitle.visibility = View.GONE
        } else {
            row.tvSubtitle.visibility = View.VISIBLE
            row.tvSubtitle.text = subtitle
        }
        row.tvValue.text = value
    }

    private fun unsupportedToast() = Toast.makeText(this, R.string.unsupported_hint, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun safeStart(intent: Intent) {
        runCatching {
            val component = intent.resolveActivity(packageManager)
            if (component == null) {
                toast("功能未注册: ${intent.component?.className ?: intent.action}")
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }.onFailure { t -> toast("打开失败: ${t.message ?: t.javaClass.simpleName}".take(44)) }
    }

    // ================= 子对话框 =================
    private fun showRouteDialog(dev: Device) {
        runCatching {
            val dlg = DialogRouteBinding.inflate(layoutInflater)
            dlg.editLanSsid.setText(dev.lanSsid.orEmpty())
            dlg.editPublicHost.setText(dev.publicHost.orEmpty())
            dlg.editPublicPort.setText(if (dev.publicPort > 0) dev.publicPort.toString() else "554")
            dlg.editPublicOnvifPort.setText(dev.publicOnvifPort.toString())

            lifecycleScope.launch {
                val tunnels = runCatching {
                    withContext(Dispatchers.IO) { CameraApp.get().repository.getTunnels() }
                }.getOrDefault(emptyList())
                val labels = ArrayList<String>().apply { add("不绑定") }
                tunnels.forEach { labels.add("${it.name}  (${it.host}:${it.port})") }
                dlg.spinnerTunnel.adapter = android.widget.ArrayAdapter(
                    this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels
                )
                val idx = tunnels.indexOfFirst { it.id == dev.tunnelId }
                dlg.spinnerTunnel.setSelection(if (idx >= 0) idx + 1 else 0)

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.route_setting)
                    .setView(dlg.root)
                    .setPositiveButton("保存") { _, _ ->
                        val lanSsid = dlg.editLanSsid.text.toString().trim().ifEmpty { null }
                        val tunnelId = tunnels.getOrNull(dlg.spinnerTunnel.selectedItemPosition - 1)?.id ?: 0L
                        val publicHost = dlg.editPublicHost.text.toString().trim().ifEmpty { null }
                        val publicPort = dlg.editPublicPort.text.toString().trim().toIntOrNull() ?: 0
                        val publicOnvifPort = dlg.editPublicOnvifPort.text.toString().trim().toIntOrNull() ?: 0
                        lifecycleScope.launch {
                            runCatching {
                                CameraApp.get().repository.updateDevice(dev.copy(
                                    lanSsid = lanSsid, tunnelId = tunnelId,
                                    publicHost = publicHost, publicPort = publicPort,
                                    publicOnvifPort = publicOnvifPort
                                ))
                            }
                            toast("路由配置已更新")
                        }
                    }
                    .setNegativeButton("取消", null).show()
            }
        }
    }

    private fun showRulesBottomSheet(deviceId: Long) {
        runCatching {
            val rulesView = LayoutInflater.from(this).inflate(R.layout.dialog_rules, null, false)
            val recycler = rulesView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerRules)
            val btnAdd = rulesView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddRule)
            recycler.layoutManager = LinearLayoutManager(this)
            recycler.adapter = adapter

            AlertDialog.Builder(this, R.style.AlertDialogLight)
                .setTitle("侦测规则")
                .setView(rulesView)
                .setNegativeButton("关闭", null).show()

            btnAdd.setOnClickListener {
                safeStart(DetectionRuleActivity.intent(this, deviceId, -1L))
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
