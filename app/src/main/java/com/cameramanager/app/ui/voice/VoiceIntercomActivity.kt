package com.cameramanager.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.audio.VoiceIntercom
import com.cameramanager.app.databinding.ActivityVoiceIntercomBinding
import kotlinx.coroutines.launch

/**
 * Two-way voice intercom screen. Press and hold to talk (push-to-talk) or toggle
 * hands-free. Shows the live mic level meter.
 */
class VoiceIntercomActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceIntercomBinding
    private var intercom: VoiceIntercom? = null
    private var active = false

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "需要麦克风权限才能对讲", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceIntercomBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("双向语音对讲")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        if (deviceId < 0) { finish(); return }

        lifecycleScope.launch {
            val device = com.cameramanager.app.CameraApp.get().repository.getDevice(deviceId)
            if (device == null) { finish(); return@launch }
            binding.deviceName.text = device.name
            intercom = VoiceIntercom(device) { level ->
                runOnUiThread { binding.levelMeter.level = level }
            }
        }

        binding.btnToggle.setOnClickListener { toggle() }
    }

    private fun toggle() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (active) {
            intercom?.stop()
            active = false
            binding.btnToggle.text = "开始对讲"
            binding.statusText.text = "已结束"
            binding.levelMeter.level = 0
        } else {
            val ok = intercom?.start() ?: false
            if (ok) {
                active = true
                binding.btnToggle.text = "结束对讲"
                binding.statusText.text = "对讲中…"
            } else {
                Toast.makeText(this, "无法建立对讲通道，请检查设备是否支持", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        intercom?.stop()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, VoiceIntercomActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
