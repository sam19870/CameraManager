package com.cameramanager.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import com.cameramanager.app.ui.scan.DeviceScanActivity
import com.cameramanager.app.ui.settings.AlarmLogActivity
import com.cameramanager.app.ui.settings.SettingsActivity
import com.cameramanager.app.util.PermissionHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Home screen: multi-device centralized management list. Tap a device to open the
 * real-time preview; long-press for management actions (settings, playback, delete).
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

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, DeviceScanActivity::class.java))
        }
        binding.fabMulti.setOnClickListener {
            startActivity(Intent(this, MultiPreviewActivity::class.java))
        }
        binding.swipe.setOnRefreshListener {
            refreshOnlineStatus()
        }

        lifecycleScope.launch {
            viewModel.devices.collectLatest {
                adapter.submit(it)
                binding.swipe.isRefreshing = false
                binding.emptyState.visibility = if (it.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        PermissionHelper.request(this, PermissionHelper.PREVIEW_PERMISSIONS, PermissionHelper.REQ_PREVIEW)
        // start background detection so alarms are pushed even when app is closed
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_alarms -> startActivity(Intent(this, AlarmLogActivity::class.java))
            R.id.action_multi -> startActivity(Intent(this, MultiPreviewActivity::class.java))
            R.id.action_tunnel -> startActivity(com.cameramanager.app.ui.tunnel.TunnelManageActivity.intent(this))
        }
        return true
    }
}
