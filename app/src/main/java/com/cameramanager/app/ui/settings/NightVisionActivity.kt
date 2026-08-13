package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.CameraApp
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityNightVisionBinding
import com.cameramanager.app.databinding.ItemSettingRowBinding
import com.cameramanager.app.vendor.ApiResult
import com.cameramanager.app.vendor.CameraVendorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 夜视照明子页 - 1:1 参考 TP-LINK 物联 App「夜视照明」官方布局：
 *  日夜配置：日夜切换模式（自动/红外/全彩）
 *  灯光设置：补光灯开关（自动(推荐)/开启/关闭）
 *
 * 防闪退：onCreate 全 try-catch + 全局 CrashGuard 兜底。
 */
class NightVisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNightVisionBinding
    private var device: Device? = null
    private var fillLightMode = 0 // 0=自动(推荐) 1=开启 2=关闭

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityNightVisionBinding.inflate(layoutInflater)
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
            toast("夜视照明页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun bindRows(d: Device) {
        // 日夜切换模式
        val nvLabels = arrayOf("日夜自动切换", "红外夜视", "全彩夜视")
        setRow(binding.rowDayNight, "日夜切换模式", value = nvLabels[d.nightVision.coerceIn(0, 2)])
        binding.rowDayNight.root.setOnClickListener {
            AlertDialog.Builder(this).setTitle("日夜切换模式")
                .setSingleChoiceItems(nvLabels, d.nightVision.coerceIn(0, 2)) { dl, w ->
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                CameraApp.get().repository.updateDevice(d.copy(nightVision = w))
                                CameraVendorApi.forDevice(d).setNightVision(d, w)
                            }
                        }
                        binding.rowDayNight.tvValue.text = nvLabels[w]
                        toast("日夜切换：${nvLabels[w]}")
                    }
                    dl.dismiss()
                }.setNegativeButton("取消", null).show()
        }

        // 补光灯开关
        val fillLabels = arrayOf("自动（推荐）", "开启", "关闭")
        setRow(binding.rowFillLight, "补光灯开关", value = fillLabels[fillLightMode])
        binding.rowFillLight.root.setOnClickListener {
            AlertDialog.Builder(this).setTitle("补光灯开关")
                .setSingleChoiceItems(fillLabels, fillLightMode) { dl, w ->
                    fillLightMode = w
                    binding.rowFillLight.tvValue.text = fillLabels[w]
                    device?.let { dev ->
                        lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    CameraVendorApi.forDevice(dev).setWhiteLight(dev, w == 1)
                                }
                            }
                        }
                    }
                    toast("补光灯：${fillLabels[w]}")
                    dl.dismiss()
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun setRow(row: ItemSettingRowBinding, title: String, value: String? = null) {
        row.ivIcon.visibility = android.view.View.GONE
        row.tvTitle.text = title
        row.tvSubtitle.visibility = android.view.View.GONE
        row.tvValue.text = value
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "NightVision"
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, NightVisionActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
