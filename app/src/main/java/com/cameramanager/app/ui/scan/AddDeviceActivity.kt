package com.cameramanager.app.ui.scan

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
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
 *
 * 防闪退策略：onCreate 全 try-catch，所有跳转用 safeStart
 */
class AddDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDeviceBinding
    private val viewModel: ScanViewModel by viewModels { DeviceViewModelFactory() }
    private var probing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityAddDeviceBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "添加摄像头"

            intent.getStringExtra(EXTRA_HOST)?.let { binding.editHost.setText(it) }
            intent.getIntExtra(EXTRA_PORT, 80).takeIf { it > 0 }?.let {
                binding.editPort.setText(it.toString())
            }

            // 关键点：
            //   1. XML inputType=textUri + digits 限定合法字符集合（防止中文）
            //   2. 代码里 setRawInputType(TYPE_CLASS_NUMBER | TYPE_NUMBER_FLAG_DECIMAL)
            //      → 让系统默认弹出数字/符号键盘，但 digits 不拦截字母和冒号
            //   3. 再叠加 HostInputFilter，确保不会出现非法字符
            val hostFilters = arrayOf<InputFilter>(
                HostInputFilter(),
                InputFilter.LengthFilter(128)
            )
            binding.editHost.filters = hostFilters
            // 强制弹数字键盘（用户要求自动锁定到数字键盘）
            binding.editHost.setRawInputType(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
            binding.editHost.imeOptions = EditorInfo.IME_ACTION_NEXT

            binding.editPort.setRawInputType(InputType.TYPE_CLASS_NUMBER)
            binding.editPort.filters = arrayOf<InputFilter>(
                InputFilter.LengthFilter(5),
                PortInputFilter()
            )

            binding.probeLog.movementMethod = ScrollingMovementMethod()

            binding.btnCancel.setOnClickListener { finish() }
            binding.btnSave.setOnClickListener { startProbe() }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("添加页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun startProbe() {
        if (probing) return
        val name = binding.editName.text.toString().trim().ifEmpty { "我的摄像头" }
        val host = binding.editHost.text.toString().trim()
        val portStr = binding.editPort.text.toString().trim()
        val port = portStr.toIntOrNull() ?: 80
        val user = binding.editUser.text.toString().trim().ifEmpty { "admin" }
        val pass = binding.editPass.text.toString().trim()

        // ========== 格式校验（用户体验：输入不对立刻指出来，不要等到探测超时） ==========
        var ok = true
        val hostLayout = binding.editHost.parent.parent as? com.google.android.material.textfield.TextInputLayout
        val portLayout = binding.editPort.parent.parent as? com.google.android.material.textfield.TextInputLayout
        hostLayout?.error = null
        portLayout?.error = null

        if (host.isEmpty()) {
            hostLayout?.error = "请填写 IP 或域名"
            ok = false
        } else if (!isValidHost(host)) {
            hostLayout?.error = "IP 格式不正确，例：192.168.1.100"
            ok = false
        }
        if (portStr.isEmpty() || port < 1 || port > 65535) {
            portLayout?.error = "端口须在 1~65535（默认 80）"
            ok = false
        }
        if (!ok) return

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
            val result = runCatching {
                withContext(Dispatchers.IO) {
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
            }
            result.onSuccess { r ->
                // 把探测得到的主/子码流路径一起写进 Device 入库，预览/回放就能按场景选码流
                val saved = r.device.copy(
                    mainRtspPath = r.mainRtspPath ?: r.device.mainRtspPath,
                    subRtspPath = r.subRtspPath ?: r.device.subRtspPath
                )
                viewModel.add(saved)
                val hint = buildString {
                    append("已添加「${saved.name}」")
                    if (r.rtspVerified) append("·码流已验证")
                    if (!saved.mainRtspPath.isNullOrBlank()) append("·原画=${saved.mainRtspPath}")
                }
                toast(hint.take(44))
                safeStart(
                    Intent(this@AddDeviceActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }.onFailure { t ->
                Log.e(TAG, "probe failed: ${t.message}", t)
                toast("探测失败: ${t.message ?: "未知错误"}")
                probing = false
                binding.btnSave.isEnabled = true
                binding.btnSave.text = "保存并探测"
            }
        }
    }

    private fun safeStart(intent: Intent) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }.onFailure { t -> toast("跳转失败: ${t.message ?: "未知错误"}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /**
     * IP / 域名合法性校验（不要让用户乱输入导致探测白等几十秒）。
     * 合法：
     *   - IPv4: 1.2.3.4 四段每段 0~255
     *   - IPv6 或 host:port 片段（不含 "/"）
     *   - 域名：字母数字开头，含至少一个点
     */
    private fun isValidHost(host: String): Boolean {
        if (host.isBlank() || host.length > 128) return false
        // 含非法字符就直接打回
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) return false
        // IPv4 校验
        val ipv4Parts = host.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() } }) {
            if (ipv4Parts.any { it.toInt() > 255 }) return false
            return true
        }
        // 域名或带端口：至少 2 段，每段开头结尾不能是 '-'
        if (host.startsWith('.') || host.startsWith('-') || host.endsWith('.') || host.endsWith('-')) return false
        return host.contains('.') || host.contains(':')
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "AddDevice"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        fun intent(context: Context, host: String, port: Int, onvif: Boolean): Intent =
            Intent(context, AddDeviceActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
    }
}

/**
 * 允许在 IP 输入框里输入：数字 . - : 字母（兼容域名）。
 * 保证默认弹数字键盘（用户说的"自动锁定到数字键盘"）。
 * 注意：与 XML android:digits= 叠加形成双保险。
 */
private class HostInputFilter : InputFilter {
    override fun filter(
        source: CharSequence, start: Int, end: Int,
        dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        for (i in start until end) {
            val c = source[i]
            val ok = c in '0'..'9' || c == '.' || c == '-' || c == ':' ||
                    c in 'a'..'z' || c in 'A'..'Z'
            if (!ok) return ""
        }
        return null
    }
}

/** 端口 0~65535 */
private class PortInputFilter : InputFilter {
    override fun filter(
        source: CharSequence, start: Int, end: Int,
        dest: Spanned, dstart: Int, dend: Int
    ): CharSequence? {
        val future = buildString {
            append(dest.subSequence(0, dstart))
            append(source.subSequence(start, end))
            append(dest.subSequence(dend, dest.length))
        }
        if (future.isEmpty()) return null
        val n = future.toIntOrNull() ?: return ""
        if (n > 65535) return ""
        return null
    }
}
