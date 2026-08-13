package com.cameramanager.app.ui.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityAddDeviceBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.MainActivity
import com.cameramanager.app.ui.ScanViewModel
import com.cameramanager.app.vendor.DeviceAutoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手动添加摄像头：极简 NVR 风格。
 * 用户只填：设备名 / IP / 端口(默认80) / 用户名(admin) / 密码
 * 由 [DeviceAutoProbe] 自动探测 ONVIF/Tapo/乐橙协议、真实 RTSP 路径、PTZ 能力。
 */
class AddDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDeviceBinding
    private val viewModel: ScanViewModel by viewModels { DeviceViewModelFactory() }
    private var probing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        intent.getStringExtra(EXTRA_HOST)?.let { binding.editHost.setText(it) }
        intent.getIntExtra(EXTRA_PORT, 80).takeIf { it > 0 }?.let {
            binding.editPort.setText(it.toString())
        }

        binding.probeLog.movementMethod = ScrollingMovementMethod()

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { startProbe() }
    }

    private fun startProbe() {
        if (probing) return
        val name = binding.editName.text.toString().trim().ifEmpty { "我的摄像头" }
        val host = binding.editHost.text.toString().trim()
        val port = binding.editPort.text.toString().trim().toIntOrNull() ?: 80
        val user = binding.editUser.text.toString().trim().ifEmpty { "admin" }
        val pass = binding.editPass.text.toString().trim()

        if (host.isEmpty()) {
            Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
            return
        }

        probing = true
        binding.btnSave.isEnabled = false
        binding.btnSave.text = "探测中…"
        binding.cardProbe.visibility = View.VISIBLE
        binding.probeLog.text = ""
        val base = Device(
            name = name, host = host, port = port,
            username = user, password = pass.ifEmpty { null }
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DeviceAutoProbe.probe(base) { step ->
                    runOnUiThread {
                        binding.probeLog.append(step + "\n")
                        val layout = binding.probeLog.layout
                        if (layout != null) {
                            val y = layout.getLineBottom(binding.probeLog.lineCount - 1) -
                                binding.probeLog.height
                            binding.probeLog.scrollTo(0, y.coerceAtLeast(0))
                        }
                    }
                }
            }
            viewModel.add(result.device)
            Toast.makeText(
                this@AddDeviceActivity,
                "已添加「${result.device.name}」·${if (result.rtspVerified) "RTSP已验证" else "已保存"}",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this@AddDeviceActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        fun intent(context: Context, host: String, port: Int, onvif: Boolean): Intent =
            Intent(context, AddDeviceActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
    }
}
