package com.cameramanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.cameramanager.app.R
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityMainBinding
import com.cameramanager.app.service.AlarmNotifier
import com.cameramanager.app.service.MotionDetectionService
import com.cameramanager.app.ui.playback.PlaybackActivity
import com.cameramanager.app.ui.preview.PreviewActivity
import com.cameramanager.app.ui.scan.AddDeviceActivity
import com.cameramanager.app.ui.scan.DeviceScanActivity
import com.cameramanager.app.ui.settings.AlarmLogActivity
import com.cameramanager.app.ui.settings.AppSettingsActivity
import com.cameramanager.app.ui.settings.SettingsActivity
import com.cameramanager.app.ui.manage.DeviceManageActivity
import com.cameramanager.app.ui.voice.VoiceIntercomActivity
import com.cameramanager.app.util.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首页 - 底部导航：首页 / 回放 / 消息 / 设置
 * 顶部工具栏右侧（Material官方图标 + 文字，用户秒懂）：扫描 / 分屏 / 添加
 *
 * 三重防闪退：
 *  1) 全局 CrashGuard 兜底（任何异常不再闪退）
 *  2) safeStart 先 resolveActivity 再跳（解决 Unable to start activity）
 *  3) 刷新 6 秒硬超时 + flow 回调强制停 spinner
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DeviceListViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: DeviceAdapter

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                setDisplayShowHomeEnabled(false)
                setDisplayHomeAsUpEnabled(false)
                setTitle(R.string.app_name)
            }
            AlarmNotifier.createChannels(this)

            adapter = DeviceAdapter(
                onClick = { openPreview(it) },
                onLongClick = { showDeviceMenu(it) }
            )
            binding.recycler.layoutManager = GridLayoutManager(this, 2)
            binding.recycler.adapter = adapter

            binding.swipe.setOnRefreshListener { refreshOnlineStatus() }

            binding.bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> true
                    R.id.nav_playback -> {
                        val devices = viewModel.devices.value
                        if (devices.isNotEmpty()) safeStart(PlaybackActivity.intent(this, devices[0].id))
                        else toast("请先添加摄像头")
                        restoreHome()
                        true
                    }
                    R.id.nav_alarms -> {
                        safeStart(Intent(this, AlarmLogActivity::class.java))
                        restoreHome()
                        true
                    }
                    R.id.nav_settings -> {
                        safeStart(AppSettingsActivity.intent(this))
                        restoreHome()
                        true
                    }
                    else -> false
                }
            }

            lifecycleScope.launch {
                runCatching {
                    viewModel.devices.collectLatest {
                        adapter.submit(it)
                        binding.emptyState.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                        binding.swipe.isRefreshing = false
                    }
                }.onFailure { t ->
                    Log.w(TAG, "flow collect failed: ${t.message}", t)
                    binding.swipe.isRefreshing = false
                }
            }

            PermissionHelper.request(this, PermissionHelper.PREVIEW_PERMISSIONS, PermissionHelper.REQ_PREVIEW)
            MotionDetectionService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate fatal: ${t.message}", t)
            toast("启动异常: ${t.message}")
        }
    }

    private fun restoreHome() {
        binding.bottomNav.postDelayed({
            runCatching { binding.bottomNav.selectedItemId = R.id.nav_home }
        }, 400)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        runCatching { menuInflater.inflate(R.menu.main_toolbar_menu, menu) }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> { safeStart(Intent(this, DeviceScanActivity::class.java)); true }
            R.id.action_multiscreen -> { safeStart(Intent(this, MultiPreviewActivity::class.java)); true }
            R.id.action_add -> { safeStart(Intent(this, AddDeviceActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 跳转前先校验 Activity 存在，避免 Unable to start activity；任何异常都吞掉不崩。 */
    private fun safeStart(intent: Intent) {
        runCatching {
            val component = intent.resolveActivity(packageManager)
            if (component == null) {
                toast("功能未注册: ${intent.component?.className ?: intent.action}")
                return
            }
            startActivity(intent)
        }.onFailure { t ->
            Log.w(TAG, "safeStart failed: ${t.message}", t)
            toast("打开失败: ${simpleError(t)}")
        }
    }

    private fun simpleError(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return if (msg.length > 40) msg.substring(0, 40) + "…" else msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 下拉刷新：6 秒硬超时，任何情况 spinner 必停 */
    private fun refreshOnlineStatus() {
        lifecycleScope.launch {
            withTimeoutOrNull(6000) {
                runCatching {
                    viewModel.devices.value.forEach { d ->
                        val reachable = com.cameramanager.app.net.NetworkScanner.testReachable(d.host, d.port, 800)
                        viewModel.updateDevice(d.copy(online = reachable))
                    }
                }.onFailure { t -> Log.w(TAG, "refresh failed: ${t.message}", t) }
            }
            binding.swipe.isRefreshing = false
        }
    }

    private fun openPreview(device: Device) = safeStart(PreviewActivity.intent(this, device.id))

    private fun showDeviceMenu(device: Device) {
        runCatching {
            val items = arrayOf("实时预览", "历史回放", "设备设置", "设备管理", "双向语音对讲", "删除设备")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(device.name)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> openPreview(device)
                        1 -> safeStart(PlaybackActivity.intent(this, device.id))
                        2 -> safeStart(SettingsActivity.intent(this, device.id))
                        3 -> safeStart(DeviceManageActivity.intent(this, device.id))
                        4 -> safeStart(VoiceIntercomActivity.intent(this, device.id))
                        5 -> confirmDelete(device)
                    }
                }.show()
        }.onFailure { t ->
            Log.w(TAG, "showDeviceMenu failed: ${t.message}", t)
            openPreview(device)
        }
    }

    private fun confirmDelete(device: Device) {
        runCatching {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除设备")
                .setMessage("确定删除「${device.name}」吗？相关侦测规则与告警记录将一并清除。")
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        runCatching { viewModel.delete(device) }
                            .onFailure { t -> toast("删除失败: ${t.message}") }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
