package com.cameramanager.app.ui.scan

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.ScannedDevice
import com.cameramanager.app.databinding.ActivityDeviceScanBinding
import com.cameramanager.app.databinding.DialogAuthBinding
import com.cameramanager.app.net.NetworkScanner
import com.cameramanager.app.ui.preview.PreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 局域网设备扫描页（乐橙同款局域网探测工具）。
 *
 *  - 扫描发现 ONVIF + /24 端口探测到的摄像头
 *  - 点击设备 → 弹账号密码对话框（免添加即可预览）
 *  - 若用户填了账号密码（或空），直接构造临时 Device 跳 PreviewActivity 预览
 *  - 右上角「手动添加」跳到 AddDeviceActivity 正式入库
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceScanBinding
    private lateinit var adapter: ScannedDeviceAdapter
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityDeviceScanBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setTitle("局域网摄像头")

            adapter = ScannedDeviceAdapter { askAuthAndPreview(it) }
            binding.recycler.layoutManager = LinearLayoutManager(this)
            binding.recycler.adapter = adapter

            binding.btnRescan.setOnClickListener { startScan() }
            binding.btnAddManual.setOnClickListener {
                runCatching { startActivity(Intent(this, AddDeviceActivity::class.java)) }
                    .onFailure { t -> toast("打开失败: ${t.message}") }
            }

            // 不要进页面立刻启动扫描（防止用户一进来就卡 Progress）
            // 显示个空状态说明+手动点按钮扫
            adapter.submit(emptyList())
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("扫描页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun startScan() {
        binding.progress.visibility = View.VISIBLE
        binding.btnRescan.isEnabled = false
        adapter.submit(emptyList())
        runCatching { acquireMulticast() }

        lifecycleScope.launch {
            val devices = runCatching {
                withContext(Dispatchers.IO) {
                    NetworkScanner.scan(this@DeviceScanActivity) { p ->
                        runOnUiThread { binding.progress.progress = p }
                    }
                }
            }.getOrDefault(emptyList())
            binding.progress.visibility = View.GONE
            binding.btnRescan.isEnabled = true
            adapter.submit(devices)
            if (devices.isEmpty()) {
                toast("未发现设备，请确认手机与摄像头在同一 Wi-Fi")
            }
            runCatching { releaseMulticast() }
        }
    }

    /** 点击发现的摄像头：弹账号密码认证对话框，填完直接预览（不入库） */
    private fun askAuthAndPreview(scan: ScannedDevice) {
        val dlgBinding = DialogAuthBinding.inflate(LayoutInflater.from(this))
        dlgBinding.editHint.text = "设备「${scan.host}:${scan.port}」需要认证，默认 admin / admin / 空"
        AlertDialog.Builder(this)
            .setTitle("认证并预览")
            .setView(dlgBinding.root)
            .setPositiveButton("直接预览") { _, _ ->
                val user = dlgBinding.editUsername.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "admin"
                val pwd = dlgBinding.editPassword.text?.toString()?.trim().orEmpty()
                val name = scan.manufacturer.let { if (it == "Unknown") scan.host else it } +
                        "·" + scan.model.let { if (it == "Unknown") "" else it }
                val onvifPort = if (scan.onvif) scan.port else 0
                val rtspPort = if (scan.port in setOf(80, 8080, 8000, 443)) 554 else scan.port
                val temp = Device(
                    id = 0L, name = name.ifEmpty { scan.host },
                    host = scan.host, port = rtspPort,
                    rtspPath = when (scan.manufacturer.lowercase()) {
                        "dahua", "海康", "hikvision" -> "cam/realmonitor?channel=1&subtype=1"
                        "tplink", "tapo" -> "stream1"
                        "imou", "乐橙", "dahua imou" -> "cam/realmonitor?channel=1&subtype=1"
                        "xmeye", "xiongmai" -> "ch01/01"
                        else -> "stream0"
                    },
                    username = user, password = pwd,
                    onvifPort = onvifPort,
                    supportsPtz = scan.onvif,
                    supportsAudio = true,
                    vendor = when (scan.manufacturer.lowercase()) {
                        "tplink", "tapo" -> "tapo"
                        "imou", "乐橙", "dahua imou" -> "imou"
                        else -> "generic"
                    }
                )
                runCatching { startActivity(PreviewActivity.intentTemp(this, temp)) }
                    .onFailure { t -> toast("打开预览失败: ${t.message}") }
            }
            .setNeutralButton("去正式添加") { _, _ ->
                runCatching {
                    startActivity(AddDeviceActivity.intent(this, scan.host, scan.port, scan.onvif))
                }.onFailure { t -> toast("打开添加页失败: ${t.message}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun acquireMulticast() {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("onvif_discovery").apply { acquire() }
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        releaseMulticast()
    }

    companion object {
        private const val TAG = "DeviceScan"
    }
}
