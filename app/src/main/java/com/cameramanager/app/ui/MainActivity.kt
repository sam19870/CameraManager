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

/**
 * 首页 - 底部导航：首页/回放/消息/设置
 * 顶部工具栏：右侧「局域网扫描」入口 + 「分屏」入口
 * 右下角蓝色 FAB：点击进入添加摄像头页（IP+账号+密码+端口80，自动探测协议）
 *
 * =======================================
 *  防闪退策略（所有 startActivity 一律 try-catch）：
 * =======================================
 *  1. 所有跳转都包 [safeStart] ，失败 Toast 提示，永不崩
 *  2. Toolbar 先关 HomeAsUp ，避免主题未配置崩溃
 *  3. 底部导航跳转后强制 selectedItemId 回首页，不依赖返回栈
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
            // 显式配置 Toolbar，避免主题 HomeAsUp 空引用
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

            binding.fabAdd.setOnClickListener {
                safeStart(Intent(this, AddDeviceActivity::class.java))
            }
            binding.swipe.setOnRefreshListener { refreshOnlineStatus() }

            binding.bottomNav.setOnItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.nav_home -> true
                    R.id.nav_playback -> {
                        val devices = viewModel.devices.value
                        if (devices.isNotEmpty()) {
                            safeStart(PlaybackActivity.intent(this, devices[0].id))
                        } else {
                            toast("请先添加摄像头")
                        }
                        binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.nav_home }
                        true
                    }
                    R.id.nav_alarms -> {
                        safeStart(Intent(this, AlarmLogActivity::class.java))
                        binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.nav_home }
                        true
                    }
                    R.id.nav_settings -> {
                        safeStart(AppSettingsActivity.intent(this))
                        binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.nav_home }
                        true
                    }
                    else -> false
                }
            }

            lifecycleScope.launch {
                viewModel.devices.collectLatest {
                    adapter.submit(it)
                    binding.swipe.isRefreshing = false
                    binding.emptyState.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }

            PermissionHelper.request(this, PermissionHelper.PREVIEW_PERMISSIONS, PermissionHelper.REQ_PREVIEW)
            MotionDetectionService.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate fatal: ${t.message}", t)
            toast("启动异常: ${t.message}")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_scan -> { safeStart(Intent(this, DeviceScanActivity::class.java)); true }
            R.id.action_multiscreen -> { safeStart(Intent(this, MultiPreviewActivity::class.java)); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 安全跳转护栏：找不到 Activity / 任何异常时 Toast 提示，不崩 App */
    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { t ->
                Log.w(TAG, "safeStart failed: ${t.message}", t)
                toast("打开页面失败: ${t.message ?: "未知错误"}")
            }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshOnlineStatus() {
        lifecycleScope.launch {
            runCatching {
                viewModel.devices.value.forEach { d ->
                    val reachable = com.cameramanager.app.net.NetworkScanner.testReachable(d.host, d.port, 800)
                    viewModel.updateDevice(d.copy(online = reachable))
                }
            }.onFailure { t -> Log.w(TAG, "refreshOnlineStatus failed: ${t.message}", t) }
            binding.swipe.isRefreshing = false
        }
    }

    private fun openPreview(device: Device) {
        safeStart(PreviewActivity.intent(this, device.id))
    }

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
