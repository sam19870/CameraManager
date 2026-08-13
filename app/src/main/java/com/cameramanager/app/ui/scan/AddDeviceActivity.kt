package com.cameramanager.app.ui.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Tunnel
import com.cameramanager.app.databinding.ActivityAddDeviceBinding
import com.cameramanager.app.net.NetworkScanner
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.MainActivity
import com.cameramanager.app.ui.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add a camera manually. Tests reachability before saving, then returns to the
 * device list. Pre-fills host/port from the scan result.
 *
 * 厂商切换时会自动更新「填什么」提示卡片，并预填 Tapo/乐橙 默认用户名 admin，
 * 让小白用户不用纠结账号密码从哪来。
 *
 * 「路由 / 内网穿透」区可填写设备所在内网 SSID、绑定一条穿透通道，以及设备自身
 * 公网地址。三者均为可选，[com.cameramanager.app.net.NetworkRouter] 会据此在
 * 内网 / 公网 / 穿透之间自动选路。
 */
class AddDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDeviceBinding
    private val viewModel: ScanViewModel by viewModels { DeviceViewModelFactory() }
    /** spinner 用：第 0 项固定「不绑定」，其后为已配置的通道。 */
    private var tunnels: List<Tunnel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("添加摄像头")

        intent.getStringExtra(EXTRA_HOST)?.let {
            binding.editHost.setText(it)
            binding.editPort.setText(intent.getIntExtra(EXTRA_PORT, 554).toString())
            if (intent.getBooleanExtra(EXTRA_ONVIF, false)) {
                binding.switchPtz.isChecked = true
            }
        }

        // 厂商切换：更新提示文案 + 预填默认用户名
        binding.vendorGroup.setOnCheckedChangeListener { _, checkedId -> applyVendorHint(checkedId) }
        applyVendorHint(binding.vendorGroup.checkedRadioButtonId)

        loadTunnels()

        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }
    }

    /** 加载已配置的穿透通道到 spinner；没有通道时只显示「不绑定」。 */
    private fun loadTunnels() {
        lifecycleScope.launch {
            tunnels = withContext(Dispatchers.IO) { CameraApp.get().repository.getTunnels() }
            val labels = ArrayList<String>().apply { add("不绑定") }
            tunnels.forEach { labels.add("${it.name}  (${it.host}:${it.port})") }
            binding.spinnerTunnel.adapter = ArrayAdapter(
                this@AddDeviceActivity, android.R.layout.simple_spinner_dropdown_item, labels
            )
        }
    }

    /** 根据所选厂商更新「填什么」提示卡片，并为 Tapo/乐橙 预填 admin 用户名。 */
    private fun applyVendorHint(checkedId: Int) {
        when (checkedId) {
            binding.vendorTapo.id -> {
                binding.vendorHint.text = getString(R.string.vendor_hint_tapo)
                if (binding.editUser.text.isNullOrBlank()) binding.editUser.setText("admin")
            }
            binding.vendorImou.id -> {
                binding.vendorHint.text = getString(R.string.vendor_hint_imou)
                if (binding.editUser.text.isNullOrBlank()) binding.editUser.setText("admin")
            }
            else -> {
                binding.vendorHint.text = getString(R.string.vendor_hint_generic)
            }
        }
    }

    private fun test() {
        val host = binding.editHost.text.toString().trim()
        val port = binding.editPort.text.toString().trim().toIntOrNull() ?: 554
        if (host.isEmpty()) { Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show(); return }
        binding.btnTest.isEnabled = false
        binding.btnTest.text = "测试中…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { NetworkScanner.testReachable(host, port, 1500) }
            binding.btnTest.isEnabled = true
            binding.btnTest.text = "测试连接"
            Toast.makeText(this@AddDeviceActivity, if (ok) "连接成功" else "无法连接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val name = binding.editName.text.toString().trim().ifEmpty { "摄像头" }
        val host = binding.editHost.text.toString().trim()
        val port = binding.editPort.text.toString().trim().toIntOrNull() ?: 554
        val path = binding.editPath.text.toString().trim().ifEmpty { "stream0" }
        if (host.isEmpty()) { Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show(); return }

        val device = Device(
            name = name,
            host = host,
            port = port,
            rtspPath = path,
            username = binding.editUser.text.toString().trim().ifEmpty { null },
            password = binding.editPass.text.toString().trim().ifEmpty { null },
            onvifPort = if (binding.switchPtz.isChecked) binding.editOnvifPort.text.toString().trim().toIntOrNull() ?: 80 else 0,
            supportsPtz = binding.switchPtz.isChecked,
            supportsAudio = binding.switchAudio.isChecked,
            vendor = when (binding.vendorGroup.checkedRadioButtonId) {
                binding.vendorTapo.id -> "tapo"
                binding.vendorImou.id -> "imou"
                else -> "generic"
            },
            // 路由 / 内网穿透（均可选）
            lanSsid = binding.editLanSsid.text.toString().trim().ifEmpty { null },
            tunnelId = tunnels.getOrNull(binding.spinnerTunnel.selectedItemPosition - 1)?.id ?: 0L,
            publicHost = binding.editPublicHost.text.toString().trim().ifEmpty { null },
            publicPort = binding.editPublicPort.text.toString().trim().toIntOrNull() ?: 0,
            publicOnvifPort = binding.editPublicOnvifPort.text.toString().trim().toIntOrNull() ?: 0
        )
        lifecycleScope.launch {
            viewModel.add(device)
            Toast.makeText(this@AddDeviceActivity, "已添加 $name", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@AddDeviceActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_ONVIF = "onvif"
        fun intent(context: Context, host: String, port: Int, onvif: Boolean): Intent =
            Intent(context, AddDeviceActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_ONVIF, onvif)
    }
}
