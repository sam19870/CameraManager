package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.databinding.ActivityAlarmLogBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.SettingsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Alarm / event log. Shows recent events for a specific device, or all devices
 * when launched without a device id.
 */
class AlarmLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmLogBinding
    private val viewModel: SettingsViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: AlarmAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlarmLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("告警记录")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
        adapter = AlarmAdapter(onAck = { viewModel.acknowledgeAlarm(it) })
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (deviceId >= 0) viewModel.alarms(deviceId) else viewModel.recentAlarms()
                flow.collectLatest { adapter.submit(it) }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long = -1L): Intent =
            Intent(context, AlarmLogActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
