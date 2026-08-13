package com.cameramanager.app.ui.manage

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityDeviceManageBinding
import com.cameramanager.app.vendor.CameraController
import com.cameramanager.app.vendor.CameraVendorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device remote management: reboot, firmware check/upgrade, status self-check,
 * and recording-mode switches. Uses the unified [CameraController] which routes
 * to the appropriate vendor API and surfaces "not supported" prompts.
 */
class DeviceManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceManageBinding
    private var controller: CameraController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("设备管理")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        lifecycleScope.launch {
            val device = com.cameramanager.app.CameraApp.get().repository.getDevice(deviceId) ?: return@launch
            controller = CameraController(device).also { it.refreshCapabilities() }
            bindDevice(device)
        }

        binding.btnReboot.setOnClickListener { confirmReboot() }
        binding.btnFirmwareCheck.setOnClickListener { exec { it.checkFirmware() } }
        binding.btnFirmwareUpgrade.setOnClickListener { exec { it.upgradeFirmware() } }
        binding.btnSelfCheck.setOnClickListener { exec { it.selfCheck() } }

        binding.recordContinuous.setOnClickListener {
            exec { it.setRecordingMode("continuous") }
            binding.recordContinuous.isChecked = true
            binding.recordMotion.isChecked = false
        }
        binding.recordMotion.setOnClickListener {
            exec { it.setRecordingMode("motion") }
            binding.recordMotion.isChecked = true
            binding.recordContinuous.isChecked = false
        }
    }

    private fun bindDevice(device: Device) {
        binding.deviceLabel.text = "${device.name}\n${device.host}:${device.port}"
        val caps = controller?.caps()
        binding.capsList.text = capabilitiesText(caps)
        // Hide unsupported switches
        binding.groupFirmware.visibility =
            if (caps?.firmwareUpgrade == true) View.VISIBLE else View.GONE
        binding.groupReboot.visibility =
            if (caps?.restart == true) View.VISIBLE else View.GONE
        binding.groupRecord.visibility =
            if (caps?.tfStorage == true) View.VISIBLE else View.GONE
    }

    private fun capabilitiesText(caps: CameraVendorApi.CameraCapabilities?): String {
        caps ?: return "正在查询设备能力…"
        val lines = mutableListOf<String>()
        if (caps.ptz) lines += "云台控制"
        if (caps.zoom) lines += "变焦"
        if (caps.presets) lines += "预置位"
        if (caps.cruise) lines += "自动巡航"
        if (caps.autoTrack) lines += "AI人形追踪"
        if (caps.nightVision) lines += "夜视模式"
        if (caps.privacyMask) lines += "隐私遮蔽"
        if (caps.whiteLight) lines += "白光补光"
        if (caps.siren) lines += "警笛"
        if (caps.voiceIntercom) lines += "语音对讲"
        if (caps.voiceMessage) lines += "语音留言"
        if (caps.firmwareUpgrade) lines += "固件升级"
        if (caps.restart) lines += "远程重启"
        if (caps.detectionRegion) lines += "自定义侦测区域"
        if (caps.tfStorage) lines += "TF卡存储"
        return if (lines.isEmpty()) "无可用高级功能" else "支持功能:\n• " + lines.joinToString("\n• ")
    }

    private fun confirmReboot() {
        AlertDialog.Builder(this)
            .setTitle("远程重启")
            .setMessage("确定远程重启设备吗？重启期间画面会中断约 30 秒。")
            .setPositiveButton("重启") { _, _ -> exec { it.reboot() } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exec(action: suspend (CameraController) -> CameraController.CameraCommandResult) {
        val c = controller ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action(c) }
            val msg = when (result) {
                is CameraController.CameraCommandResult.Ok -> "操作成功"
                is CameraController.CameraCommandResult.OkWithMessage -> result.text
                is CameraController.CameraCommandResult.Unsupported -> result.message
                is CameraController.CameraCommandResult.Failed -> result.message
            }
            AlertDialog.Builder(this@DeviceManageActivity)
                .setMessage(msg)
                .setPositiveButton("好的", null)
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, DeviceManageActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
