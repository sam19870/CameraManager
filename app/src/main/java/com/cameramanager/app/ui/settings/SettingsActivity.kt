package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Tunnel
import com.cameramanager.app.databinding.ActivitySettingsBinding
import com.cameramanager.app.databinding.DialogRouteBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device settings screen. Hosts the list of detection rules and entry points to
 * alarm logs, storage management and device info editing.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: RuleAdapter
    private var device: Device? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("设备设置")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        com.cameramanager.app.CameraApp.get().repository.let { repo ->
            lifecycleScope.launch {
                device = repo.getDevice(deviceId)
                device?.let {
                    binding.deviceInfo.text = "${it.name}\n${it.host}:${it.port}"
                    binding.switchNotify.isChecked = true
                }
            }
        }

        adapter = RuleAdapter(
            onClick = { DetectionRuleActivity.intent(this, deviceId, it.id).let(::startActivity) },
            onToggle = { rule, enabled ->
                viewModel.saveRule(rule.copy(enabled = enabled))
            },
            onDelete = { viewModel.deleteRule(it) }
        )
        binding.recyclerRules.layoutManager = LinearLayoutManager(this)
        binding.recyclerRules.adapter = adapter

        binding.btnAddRule.setOnClickListener {
            startActivity(DetectionRuleActivity.intent(this, deviceId))
        }
        binding.btnAlarmLog.setOnClickListener {
            startActivity(AlarmLogActivity.intent(this, deviceId))
        }
        binding.btnDetectionRegion.setOnClickListener {
            startActivity(com.cameramanager.app.ui.detection.DetectionRegionActivity.intent(this, deviceId))
        }
        binding.btnRoute.setOnClickListener {
            device?.let { showRouteDialog(it) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rules(deviceId).collectLatest { adapter.submit(it) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    /** 编辑设备路由配置：内网 SSID / 绑定穿透通道 / 公网地址。 */
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
            // 选中当前绑定项
            val idx = tunnels.indexOfFirst { it.id == dev.tunnelId }
            dlg.spinnerTunnel.setSelection(if (idx >= 0) idx + 1 else 0)

            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.route_setting)
                .setView(dlg.root)
                .setPositiveButton("保存") { _, _ ->
                    val lanSsid = dlg.editLanSsid.text.toString().trim().ifEmpty { null }
                    val tunnelId = tunnels.getOrNull(dlg.spinnerTunnel.selectedPosition - 1)?.id ?: 0L
                    val publicHost = dlg.editPublicHost.text.toString().trim().ifEmpty { null }
                    val publicPort = dlg.editPublicPort.text.toString().trim().toIntOrNull() ?: 0
                    val publicOnvifPort = dlg.editPublicOnvifPort.text.toString().trim().toIntOrNull() ?: 0
                    lifecycleScope.launch {
                        CameraApp.get().repository.updateDevice(dev.copy(
                            lanSsid = lanSsid,
                            tunnelId = tunnelId,
                            publicHost = publicHost,
                            publicPort = publicPort,
                            publicOnvifPort = publicOnvifPort
                        ))
                        Toast.makeText(this@SettingsActivity, "已更新路由配置", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
