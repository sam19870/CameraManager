package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityDeviceControlBinding
import com.cameramanager.app.databinding.ItemSettingRowBinding
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraVendorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设备控制子页 - 1:1 参考 TP-LINK 物联 App「设备控制」官方布局：
 *  状态指示灯(开关) / 画面翻转 / 宽动态 / 视频参数 / OSD设置 / 通话模式 /
 *  摄像机收音音量(滑杆) / 摄像机扬声音量(滑杆) / 夜视照明(跳转子页)
 *
 * 防闪退：onCreate 全 try-catch + 全局 CrashGuard 兜底。
 */
class DeviceControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceControlBinding
    private var device: Device? = null

    private var micVolume = 0
    private var spkVolume = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityDeviceControlBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            binding.toolbar.setNavigationOnClickListener { finish() }

            val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
            lifecycleScope.launch {
                runCatching {
                    device = withContext(Dispatchers.IO) { CameraApp.get().repository.getDevice(deviceId) }
                    device?.let { bindRows(it) } ?: toast("设备不存在")
                }.onFailure { t ->
                    Log.w(TAG, "load device failed: ${t.message}", t)
                    toast("加载设备失败")
                }
            }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("设备控制页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun bindRows(d: Device) {
        // 状态指示灯（Switch 行）
        binding.rowStatusLed.tvTitle.text = "状态指示灯"
        binding.rowStatusLed.switchRow.isChecked = true
        binding.rowStatusLed.switchRow.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch {
                val r = runCatching {
                    withContext(Dispatchers.IO) { CameraVendorApi.forDevice(d).setStatusLed(d, on) }
                }.getOrNull()
                when (r) {
                    is ApiResult.Success -> toast(if (on) "状态指示灯已开启" else "状态指示灯已关闭")
                    is ApiResult.Error -> toast("设置失败：${r.message}")
                    else -> unsupportedToast()
                }
            }
        }

        // 画面翻转
        setRow(binding.rowFlip, "画面翻转", "控制画面翻转以适应不同的安装场景")
        binding.rowFlip.root.setOnClickListener {
            val rotations = arrayOf(0, 90, 180, 270)
            val labels = rotations.map { "${it}°" }.toTypedArray()
            val currentIdx = rotations.indexOf(d.rotation).coerceAtLeast(0)
            val checked = booleanArrayOf(d.mirrored)
            AlertDialog.Builder(this).setTitle("画面翻转")
                .setSingleChoiceItems(labels, currentIdx, null)
                .setMultiChoiceItems(arrayOf("水平镜像"), checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("保存") { dl, _ ->
                    val w = (dl as AlertDialog).listView.checkedItemPosition.coerceAtLeast(0)
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                CameraApp.get().repository.updateDevice(
                                    d.copy(rotation = rotations[w], mirrored = checked[0])
                                )
                            }
                        }
                        binding.rowFlip.tvValue.text =
                            if (checked[0]) "镜像·${rotations[w]}°" else "${rotations[w]}°"
                        toast("画面翻转已保存")
                    }
                }.setNegativeButton("取消", null).show()
        }

        // 宽动态
        setRow(binding.rowWdr, "宽动态", "调节视频画面光线，让画面更清晰", value = "自动")
        binding.rowWdr.root.setOnClickListener {
            val items = arrayOf("自动", "开启", "关闭")
            AlertDialog.Builder(this).setTitle("宽动态")
                .setItems(items) { _, w ->
                    binding.rowWdr.tvValue.text = items[w]
                    toast("宽动态：${items[w]}")
                }.setNegativeButton("取消", null).show()
        }

        // 视频参数（码流清晰度）
        setRow(binding.rowVideoParam, "视频参数", null, value = d.profileLabel())
        binding.rowVideoParam.root.setOnClickListener {
            val profiles = arrayOf("高清 (主码流)", "标清 (子码流)", "流畅")
            AlertDialog.Builder(this).setTitle("视频参数")
                .setSingleChoiceItems(profiles, d.streamProfile.coerceIn(0, 2)) { dl, w ->
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                CameraApp.get().repository.updateDevice(d.copy(streamProfile = w))
                            }
                        }
                        binding.rowVideoParam.tvValue.text = profiles[w]
                        toast("视频参数：${profiles[w]}")
                    }
                    dl.dismiss()
                }.setNegativeButton("取消", null).show()
        }

        // OSD 设置
        setRow(binding.rowOsd, "OSD设置", "画面时间/名称水印")
        binding.rowOsd.root.setOnClickListener { unsupportedToast() }

        // 通话模式
        setRow(binding.rowTalkMode, "通话模式", "语音对讲、电话模式", value = "语音对讲")
        binding.rowTalkMode.root.setOnClickListener {
            val items = arrayOf("语音对讲", "电话模式")
            AlertDialog.Builder(this).setTitle("通话模式")
                .setItems(items) { _, w ->
                    binding.rowTalkMode.tvValue.text = items[w]
                    toast("通话模式：${items[w]}")
                }.setNegativeButton("取消", null).show()
        }

        // 收音音量
        binding.rowMicVolume.tvTitle.text = "摄像机收音音量"
        binding.rowMicVolume.tvValue.text = "$micVolume%"
        binding.rowMicVolume.seekRow.progress = micVolume
        binding.rowMicVolume.seekRow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    micVolume = p
                    binding.rowMicVolume.tvValue.text = "$p%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { toast("收音音量 $micVolume%") }
        })

        // 扬声音量
        binding.rowSpkVolume.tvTitle.text = "摄像机扬声音量"
        binding.rowSpkVolume.tvValue.text = "$spkVolume%"
        binding.rowSpkVolume.seekRow.progress = spkVolume
        binding.rowSpkVolume.seekRow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    spkVolume = p
                    binding.rowSpkVolume.tvValue.text = "$p%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { toast("扬声音量 $spkVolume%") }
        })

        // 夜视照明 -> 子页
        setRow(binding.rowNightLight, "夜视照明", null)
        binding.rowNightLight.root.setOnClickListener {
            safeStart(NightVisionActivity.intent(this, d.id))
        }
    }

    private fun setRow(row: ItemSettingRowBinding, title: String, subtitle: String?, value: String? = null) {
        row.ivIcon.visibility = android.view.View.GONE
        row.tvTitle.text = title
        if (subtitle.isNullOrEmpty()) {
            row.tvSubtitle.visibility = android.view.View.GONE
        } else {
            row.tvSubtitle.visibility = android.view.View.VISIBLE
            row.tvSubtitle.text = subtitle
        }
        row.tvValue.text = value
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { t -> toast("打开页面失败: ${t.message ?: "未知错误"}") }
    }

    private fun unsupportedToast() = Toast.makeText(this, R.string.unsupported_hint, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "DeviceControl"
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, DeviceControlActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
