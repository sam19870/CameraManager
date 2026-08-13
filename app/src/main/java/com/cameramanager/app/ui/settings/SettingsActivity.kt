package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivitySettingsBinding
import com.cameramanager.app.databinding.DialogRouteBinding
import com.cameramanager.app.databinding.ItemSettingRowBinding
import com.cameramanager.app.databinding.ItemSettingRowSwitchBinding
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
 * 设备设置界面 - 按设备能力动态分组显示（参考TP-Link/乐橙扁平卡片式设计）。
 *
 * 能力探测逻辑（NVR式）：
 *  1. 先从 Device.supportsPtz / supportsAudio 做基础显示；
 *  2. 后台调用 CameraVendorApi.queryCapabilities 获取完整能力集；
 *  3. 按能力刷新各分组与各条目可见性；
 *  4. 设备不支持的功能点击会弹 Toast "当前摄像头不支持此功能"。
 *
 * 防闪退策略：onCreate 全 try-catch，所有跳转用 safeStart
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: RuleAdapter

    private var device: Device? = null
    private var capabilities: CameraCapabilities = CameraCapabilities()
    private var capabilityQueried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "摄像头设置"

            val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)

            adapter = RuleAdapter(
                onClick = { safeStart(DetectionRuleActivity.intent(this, deviceId, it.id)) },
                onToggle = { rule, enabled -> viewModel.saveRule(rule.copy(enabled = enabled)) },
                onDelete = { viewModel.deleteRule(it) }
            )

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    device = withContext(Dispatchers.IO) { CameraApp.get().repository.getDevice(deviceId) }
                    device?.let {
                        bindDeviceInfo(it)
                        applyBasicVisibility(it)
                        queryCapabilities(it)
                        bindRowEvents(deviceId, it)
                    }
                    viewModel.rules(deviceId).collectLatest { adapter.submit(it) }
                }
            }
        }.onFailure { t ->
            Log.e("SettingsActivity", "onCreate failed: ${t.message}", t)
            toast("设置页初始化失败: ${t.message}")
            finish()
        }
    }

    // ---------------- 设备信息 ----------------
    private fun bindDeviceInfo(d: Device) {
        binding.tvDeviceName.text = d.name
        binding.tvDeviceAddr.text = "${d.host}:${d.port}"
        val vendorLabel = when (d.vendor) {
            "tapo" -> "TP-Link Tapo"
            "imou" -> "乐橙 Imou"
            else -> "通用 ONVIF"
        }
        binding.tvVendor.text = "$vendorLabel · RTSP :${d.port}/${d.rtspPath}"
    }

    // ---------------- 基础可见性（未探测能力前） ----------------
    private fun applyBasicVisibility(d: Device) {
        binding.groupPtz.visibility = if (d.supportsPtz) View.VISIBLE else View.GONE
        binding.groupAv.visibility = if (d.supportsAudio) View.VISIBLE else View.GONE
    }

    // ---------------- 能力探测 ----------------
    private fun queryCapabilities(d: Device) {
        binding.tvCapStatus.text = "能力探测中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CameraVendorApi.forDevice(d).queryCapabilities(d) }
                    .getOrDefault(ApiResult.Success(CameraCapabilities(
                        ptz = d.supportsPtz, voiceIntercom = d.supportsAudio
                    )))
            }
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
        // 云台
        binding.groupPtz.visibility = if (cap.ptz || cap.presets || cap.cruise || cap.autoTrack)
            View.VISIBLE else View.GONE
        setSwitchRowEnabled(binding.includeRowAutoTrack, cap.autoTrack)
        // 图像
        setRowEnabled(binding.includeRowNightVision, true)
        setSwitchRowEnabled(binding.includeRowPrivacyMask, true)
        // 音视频
        binding.groupAv.visibility = if (cap.voiceIntercom || cap.voiceMessage)
            View.VISIBLE else View.GONE
        setRowEnabled(binding.includeRowVoiceCall, cap.voiceIntercom)
        setRowEnabled(binding.includeRowVoiceMsg, cap.voiceMessage)
        // 灯光 & 威慑
        binding.groupLight.visibility = if (cap.whiteLight || cap.siren)
            View.VISIBLE else View.GONE
        setSwitchRowEnabled(binding.includeRowWhiteLight, cap.whiteLight)
        setSwitchRowEnabled(binding.includeRowSiren, cap.siren)
        // 存储
        binding.groupStorage.visibility = View.VISIBLE
        setRowEnabled(binding.includeRowTfCard, cap.tfStorage)
    }

    // ---------------- Row 辅助 ----------------
    private fun setRowIconTitle(row: ItemSettingRowBinding, iconRes: Int, title: String,
                                 subtitle: String? = null, value: String? = null) {
        row.ivIcon.setImageResource(iconRes)
        row.tvTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            row.tvSubtitle.visibility = View.GONE
        } else {
            row.tvSubtitle.visibility = View.VISIBLE
            row.tvSubtitle.text = subtitle
        }
        row.tvValue.text = value
    }

    private fun setSwitchRowIconTitle(row: ItemSettingRowSwitchBinding, iconRes: Int, title: String,
                                      subtitle: String? = null) {
        row.ivIcon.setImageResource(iconRes)
        row.tvTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            row.tvSubtitle.visibility = View.GONE
        } else {
            row.tvSubtitle.visibility = View.VISIBLE
            row.tvSubtitle.text = subtitle
        }
    }

    private fun setRowEnabled(row: ItemSettingRowBinding, supported: Boolean) {
        row.root.alpha = if (supported) 1f else 0.45f
    }

    private fun setSwitchRowEnabled(row: ItemSettingRowSwitchBinding, supported: Boolean) {
        row.root.alpha = if (supported) 1f else 0.45f
        row.switchRow.isEnabled = supported
    }

    private fun unsupportedToast() = Toast.makeText(this, R.string.unsupported_hint, Toast.LENGTH_SHORT).show()

    // ---------------- 事件绑定 ----------------
    private fun bindRowEvents(deviceId: Long, d: Device) {
        val cap = { capabilities }
        val supported = { capabilityQueried }

        // ---- 云台 ----
        setRowIconTitle(binding.includeRowPresets, R.drawable.ic_ptz, "预置位",
            subtitle = "常用视角一键跳转")
        binding.includeRowPresets.root.setOnClickListener {
            if (supported() && cap().presets)
                Toast.makeText(this, "预置位管理（开发中）", Toast.LENGTH_SHORT).show()
            else unsupportedToast()
        }

        setRowIconTitle(binding.includeRowCruise, R.drawable.ic_rotate, "自动巡航",
            subtitle = "8 个预置位轮巡")
        binding.includeRowCruise.root.setOnClickListener {
            if (supported() && cap().cruise)
                Toast.makeText(this, "巡航设置（开发中）", Toast.LENGTH_SHORT).show()
            else unsupportedToast()
        }

        setSwitchRowIconTitle(binding.includeRowAutoTrack, R.drawable.ic_detect, "AI 人形自动跟踪",
            subtitle = "有人出现时镜头自动跟随")
        binding.includeRowAutoTrack.switchRow.setOnCheckedChangeListener { btn, isChecked ->
            if (!supported() || !cap().autoTrack) {
                btn.isChecked = !isChecked; unsupportedToast(); return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    CameraVendorApi.forDevice(d).setAutoTrack(d, isChecked)
                }
                if (r is ApiResult.Error) Toast.makeText(this@SettingsActivity,
                    "设置失败：${r.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- 图像 ----
        val nvCurrent = when (d.nightVision) {
            1 -> "红外夜视"
            2 -> "全彩夜视"
            else -> "智能夜视"
        }
        setRowIconTitle(binding.includeRowNightVision, R.drawable.ic_night, "夜视模式", value = nvCurrent)
        binding.includeRowNightVision.root.setOnClickListener { showNightVisionDialog(d) }

        setSwitchRowIconTitle(binding.includeRowPrivacyMask, R.drawable.ic_lock, "隐私遮罩",
            subtitle = "敏感区域自动遮蔽")
        binding.includeRowPrivacyMask.switchRow.apply {
            isChecked = d.privacyMask
            setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        CameraApp.get().repository.updateDevice(d.copy(privacyMask = isChecked))
                    }
                }
            }
        }

        val rotText = if (d.mirrored) "镜像·${d.rotation}°" else "${d.rotation}°"
        setRowIconTitle(binding.includeRowRotate, R.drawable.ic_rotate, "画面旋转 / 镜像", value = rotText)
        binding.includeRowRotate.root.setOnClickListener { showRotateDialog(d) }

        setRowIconTitle(binding.includeRowResolution, R.drawable.ic_resolution, "码流清晰度",
            value = d.profileLabel())
        binding.includeRowResolution.root.setOnClickListener { showResolutionDialog(d) }

        // ---- 音视频 ----
        setRowIconTitle(binding.includeRowVoiceCall, R.drawable.ic_audio, "双向语音对讲",
            subtitle = "通过摄像头通话喊话")
        binding.includeRowVoiceCall.root.setOnClickListener {
            if (supported() && cap().voiceIntercom)
                safeStart(com.cameramanager.app.ui.voice.VoiceIntercomActivity.intent(this, deviceId))
            else unsupportedToast()
        }

        setRowIconTitle(binding.includeRowVoiceMsg, R.drawable.ic_mic, "语音留言",
            subtitle = "上传常用语音用于威慑")
        binding.includeRowVoiceMsg.root.setOnClickListener {
            if (supported() && cap().voiceMessage)
                Toast.makeText(this, "语音留言（开发中）", Toast.LENGTH_SHORT).show()
            else unsupportedToast()
        }

        // ---- 灯光 & 威慑 ----
        setSwitchRowIconTitle(binding.includeRowWhiteLight, R.drawable.ic_light, "白光灯")
        binding.includeRowWhiteLight.switchRow.setOnCheckedChangeListener { btn, isChecked ->
            if (!supported() || !cap().whiteLight) {
                btn.isChecked = !isChecked; unsupportedToast(); return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    CameraVendorApi.forDevice(d).setWhiteLight(d, isChecked)
                }
                if (r is ApiResult.Error) Toast.makeText(this@SettingsActivity,
                    "设置失败：${r.message}", Toast.LENGTH_SHORT).show()
            }
        }

        setSwitchRowIconTitle(binding.includeRowSiren, R.drawable.ic_siren, "警报声")
        binding.includeRowSiren.switchRow.setOnCheckedChangeListener { btn, isChecked ->
            if (!supported() || !cap().siren) {
                btn.isChecked = !isChecked; unsupportedToast(); return@setOnCheckedChangeListener
            }
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    CameraVendorApi.forDevice(d).triggerSiren(d, isChecked)
                }
                if (r is ApiResult.Error) Toast.makeText(this@SettingsActivity,
                    "设置失败：${r.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // ---- 智能侦测 ----
        setRowIconTitle(binding.includeRowRules, R.drawable.ic_detect, "侦测规则",
            subtitle = "人形/移动/人脸侦测与动作")
        binding.includeRowRules.root.setOnClickListener { showRulesBottomSheet(deviceId) }

        setRowIconTitle(binding.includeRowRegion, R.drawable.ic_grid, "自定义侦测区域",
            subtitle = "只在框选区域内触发告警")
        binding.includeRowRegion.root.setOnClickListener {
            safeStart(com.cameramanager.app.ui.detection.DetectionRegionActivity.intent(this, deviceId))
        }

        setRowIconTitle(binding.includeRowAlarmLog, R.drawable.ic_alarm, "告警记录",
            subtitle = "查看历史告警与截图")
        binding.includeRowAlarmLog.root.setOnClickListener {
            safeStart(AlarmLogActivity.intent(this, deviceId))
        }

        setSwitchRowIconTitle(binding.includeRowNotify, R.drawable.ic_alarm, "异常事件消息推送")
        binding.includeRowNotify.switchRow.isChecked = true

        // ---- 存储 & 录像 ----
        setRowIconTitle(binding.includeRowRecordMode, R.drawable.ic_record, "录像模式",
            value = "移动侦测触发")
        binding.includeRowRecordMode.root.setOnClickListener {
            val items = arrayOf("全天持续录像", "移动侦测触发", "定时录像")
            AlertDialog.Builder(this).setTitle("录像模式")
                .setItems(items) { _, _ ->
                    Toast.makeText(this, "录像模式已保存", Toast.LENGTH_SHORT).show()
                }.show()
        }

        setRowIconTitle(binding.includeRowTfCard, R.drawable.ic_sd, "TF 卡存储",
            subtitle = "查看容量与录像回放")
        binding.includeRowTfCard.root.setOnClickListener {
            if (supported() && cap().tfStorage)
                Toast.makeText(this, "TF 卡管理（开发中）", Toast.LENGTH_SHORT).show()
            else unsupportedToast()
        }

        // ---- 网络 ----
        setRowIconTitle(binding.includeRowRoute, R.drawable.ic_network, "内网 / 穿透路由",
            subtitle = "绑定 SSID 或穿透通道，内网/公网自动切换")
        binding.includeRowRoute.root.setOnClickListener { showRouteDialog(d) }

        // ---- 设备管理 ----
        setRowIconTitle(binding.includeRowFirmware, R.drawable.ic_firmware, "固件升级",
            subtitle = "检查摄像头最新固件")
        binding.includeRowFirmware.root.setOnClickListener {
            if (!supported() || !cap().firmwareUpgrade) {
                unsupportedToast(); return@setOnClickListener
            }
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).checkFirmware(d) }
                val msg = when (info) {
                    is ApiResult.Success -> {
                        val fi = info.data
                        "当前版本：${fi.current}\n最新版本：${fi.latest}\n" +
                                if (fi.upgradeAvailable) "检测到新版本，是否升级？" else "已是最新版本"
                    }
                    is ApiResult.Unsupported -> getString(R.string.unsupported_hint)
                    is ApiResult.Error -> "查询失败：${info.message}"
                }
                AlertDialog.Builder(this@SettingsActivity).setTitle("固件升级")
                    .setMessage(msg).setPositiveButton("关闭", null).show()
            }
        }

        setRowIconTitle(binding.includeRowReboot, R.drawable.ic_reboot, "重启摄像头")
        binding.includeRowReboot.root.setOnClickListener {
            if (!supported() || !cap().restart) {
                unsupportedToast(); return@setOnClickListener
            }
            AlertDialog.Builder(this).setTitle("重启摄像头")
                .setMessage("摄像头将重启，约 60 秒后恢复。确定？")
                .setPositiveButton("重启") { _, _ ->
                    lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).reboot(d) }
                        when (r) {
                            is ApiResult.Success -> Toast.makeText(this@SettingsActivity,
                                "重启指令已发送", Toast.LENGTH_SHORT).show()
                            is ApiResult.Error -> Toast.makeText(this@SettingsActivity,
                                "重启失败：${r.message}", Toast.LENGTH_SHORT).show()
                            else -> unsupportedToast()
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }

        setRowIconTitle(binding.includeRowSelfCheck, R.drawable.ic_info, "设备自检",
            subtitle = "网络/SD卡/温度等状态")
        binding.includeRowSelfCheck.root.setOnClickListener {
            if (!supported()) { unsupportedToast(); return@setOnClickListener }
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).selfCheck(d) }
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
                }
                AlertDialog.Builder(this@SettingsActivity).setTitle("设备自检报告")
                    .setMessage(msg).setPositiveButton("关闭", null).show()
            }
        }

        setRowIconTitle(binding.includeRowDeviceInfo, R.drawable.ic_info, "设备信息")
        binding.includeRowDeviceInfo.root.setOnClickListener {
            val info = buildString {
                append("名称：${d.name}\n")
                append("厂商：${when(d.vendor){"tapo"->"TP-Link Tapo" "imou"->"乐橙 Imou" else->"通用ONVIF"}}\n")
                append("内网：${d.host}:${d.port}\n")
                append("ONVIF 端口：${if (d.onvifPort>0) d.onvifPort.toString() else "不支持"}\n")
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

        setRowIconTitle(binding.includeRowDelete, R.drawable.ic_close, "删除设备")
        binding.includeRowDelete.tvTitle.setTextColor(getColor(R.color.alarm_red))
        binding.includeRowDelete.ivArrow.visibility = View.GONE
        binding.includeRowDelete.root.setOnClickListener {
            AlertDialog.Builder(this).setTitle("删除设备")
                .setMessage("删除后相关侦测规则、告警记录与录像信息将一并清除。确定删除「${d.name}」吗？")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        viewModel.deleteDevice(d.id)
                        finish()
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    // ---------------- 子对话框 ----------------
    private fun showNightVisionDialog(d: Device) {
        val items = arrayOf("智能夜视 (自动)", "红外夜视", "全彩夜视")
        AlertDialog.Builder(this).setTitle("夜视模式")
            .setSingleChoiceItems(items, d.nightVision.coerceIn(0, 2)) { dl, w ->
                lifecycleScope.launch {
                    val saved = d.copy(nightVision = w)
                    withContext(Dispatchers.IO) { CameraApp.get().repository.updateDevice(saved) }
                    binding.includeRowNightVision.tvValue.text = items[w]
                }
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showRotateDialog(d: Device) {
        val rotations = arrayOf(0, 90, 180, 270)
        val labels = rotations.map { "${it}°" }.toTypedArray()
        val currentIdx = rotations.indexOf(d.rotation).coerceAtLeast(0)
        val checked = booleanArrayOf(d.mirrored)
        AlertDialog.Builder(this).setTitle("画面旋转")
            .setSingleChoiceItems(labels, currentIdx, null)
            .setMultiChoiceItems(arrayOf("水平镜像"), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("保存") { dl, _ ->
                val lw = (dl as AlertDialog).listView
                val w = lw.checkedItemPosition.coerceAtLeast(0)
                lifecycleScope.launch {
                    val saved = d.copy(rotation = rotations[w], mirrored = checked[0])
                    withContext(Dispatchers.IO) { CameraApp.get().repository.updateDevice(saved) }
                    binding.includeRowRotate.tvValue.text =
                        if (saved.mirrored) "镜像·${saved.rotation}°" else "${saved.rotation}°"
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showResolutionDialog(d: Device) {
        val profiles = arrayOf("高清 (主码流)", "标清 (子码流)", "流畅")
        AlertDialog.Builder(this).setTitle("码流清晰度")
            .setSingleChoiceItems(profiles, d.streamProfile.coerceIn(0, 2)) { dl, w ->
                lifecycleScope.launch {
                    val saved = d.copy(streamProfile = w)
                    withContext(Dispatchers.IO) { CameraApp.get().repository.updateDevice(saved) }
                    binding.includeRowResolution.tvValue.text = profiles[w]
                }
                dl.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    private fun showRouteDialog(dev: Device) {
        val dlg = DialogRouteBinding.inflate(layoutInflater)
        dlg.editLanSsid.setText(dev.lanSsid.orEmpty())
        dlg.editPublicHost.setText(dev.publicHost.orEmpty())
        dlg.editPublicPort.setText(if (dev.publicPort > 0) dev.publicPort.toString() else "554")
        dlg.editPublicOnvifPort.setText(dev.publicOnvifPort.toString())

        lifecycleScope.launch {
            val tunnels = withContext(Dispatchers.IO) { CameraApp.get().repository.getTunnels() }
            val labels = ArrayList<String>().apply { add("不绑定") }
            tunnels.forEach { labels.add("${it.name}  (${it.host}:${it.port})") }
            dlg.spinnerTunnel.adapter = ArrayAdapter(
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
                        CameraApp.get().repository.updateDevice(dev.copy(
                            lanSsid = lanSsid, tunnelId = tunnelId,
                            publicHost = publicHost, publicPort = publicPort,
                            publicOnvifPort = publicOnvifPort
                        ))
                        Toast.makeText(this@SettingsActivity, "路由配置已更新", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun showRulesBottomSheet(deviceId: Long) {
        val rulesView = LayoutInflater.from(this).inflate(R.layout.dialog_rules, null, false)
        val recycler = rulesView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerRules)
        val btnAdd = rulesView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddRule)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("侦测规则")
            .setView(rulesView)
            .setNegativeButton("关闭", null).show()

        btnAdd.setOnClickListener {
            safeStart(DetectionRuleActivity.intent(this, deviceId, -1L))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rules(deviceId).collectLatest { adapter.submit(it) }
            }
        }
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { t -> toast("打开页面失败: ${t.message ?: "未知错误"}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
