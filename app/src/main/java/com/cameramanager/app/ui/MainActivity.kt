package com.cameramanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.cameramanager.app.ui.settings.AlarmLogActivity
import com.cameramanager.app.ui.settings.AppSettingsActivity
import com.cameramanager.app.ui.settings.SettingsActivity
import com.cameramanager.app.util.PermissionHelper
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 首页 - 底部导航：首页/回放/消息/设置
 * 右下角蓝色 FAB：点击进入添加摄像头页（IP+账号+密码+端口80，自动探测协议）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DeviceListViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        AlarmNotifier.createChannels(this)

        adapter = DeviceAdapter(
            onClick = { openPreview(it) },
            onLongClick = { showDeviceMenu(it) }
        )
        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        // 右下角 FAB → 直接进添加设备页（小白友好：只用填IP/账号/密码/端口80）
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java))
        }
        binding.swipe.setOnRefreshListener {
            refreshOnlineStatus()
        }

        // 底部导航
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_playback -> {
                    val devices = viewModel.devices.value
                    if (devices.isNotEmpty()) {
                        startActivity(PlaybackActivity.intent(this, devices[0].id))
                    } else {
                        android.widget.Toast.makeText(this,
                            "请先添加摄像头", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    binding.bottomNav.selectedItemId = R.id.nav_home
                    true
                }
                R.id.nav_alarms -> {
                    startActivity(Intent(this, AlarmLogActivity::class.java))
                    binding.bottomNav.selectedItemId = R.id.nav_home
                    true
                }
                R.id.nav_settings -> {
                    startActivity(AppSettingsActivity.intent(this))
                    binding.bottomNav.selectedItemId = R.id.nav_home
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
    }

    private fun refreshOnlineStatus() {
        lifecycleScope.launch {
            viewModel.devices.value.forEach { d ->
                val reachable = com.cameramanager.app.net.NetworkScanner.testReachable(d.host, d.port, 800)
                viewModel.updateDevice(d.copy(online = reachable))
            }
        }
    }

    private fun openPreview(device: Device) {
        startActivity(PreviewActivity.intent(this, device.id))
    }

    private fun showDeviceMenu(device: Device) {
        val items = arrayOf("实时预览", "历史回放", "设备设置", "设备管理", "双向语音对讲", "删除设备")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(device.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openPreview(device)
                    1 -> startActivity(PlaybackActivity.intent(this, device.id))
                    2 -> startActivity(SettingsActivity.intent(this, device.id))
                    3 -> startActivity(com.cameramanager.app.ui.manage.DeviceManageActivity.intent(this, device.id))
                    4 -> startActivity(com.cameramanager.app.ui.voice.VoiceIntercomActivity.intent(this, device.id))
                    5 -> confirmDelete(device)
                }
            }.show()
    }

    private fun confirmDelete(device: Device) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除设备")
            .setMessage("确定删除「${device.name}」吗？相关侦测规则与告警记录将一并清除。")
            .setPositiveButton("删除") { _, _ -> viewModel.delete(device) }
            .setNegativeButton("取消", null)
            .show()
    }
}
