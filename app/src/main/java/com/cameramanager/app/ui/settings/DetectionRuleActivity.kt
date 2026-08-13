package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.CameraApp
import kotlinx.coroutines.launch
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.databinding.ActivityDetectionRuleBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.SettingsViewModel

/**
 * Create / edit a custom detection rule: type, sensitivity, schedule (always),
 * trigger actions (record / notify / sound / light) and auto-tracking toggle.
 */
class DetectionRuleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionRuleBinding
    private val viewModel: SettingsViewModel by viewModels { DeviceViewModelFactory() }
    private var rule: DetectionRule? = null
    private var deviceId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionRuleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("侦测规则")

        deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        val ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1)

        loadRule(ruleId)

        binding.btnSave.setOnClickListener { save() }
    }

    private fun loadRule(ruleId: Long) {
        if (ruleId < 0) {
            rule = DetectionRule(deviceId = deviceId)
            return
        }
        lifecycleScope.launch {
            rule = CameraApp.get().repository.getRule(ruleId)
            rule?.let { populate(it) }
        }
    }

    private fun populate(r: DetectionRule) {
        binding.typeGroup.check(
            when (r.type) {
                "human" -> binding.typeHuman.id
                "motion" -> binding.typeMotion.id
                "track" -> binding.typeTrack.id
                else -> binding.typeHuman.id
            }
        )
        binding.sensitivity.progress = r.sensitivity - 1
        binding.actionRecord.isChecked = r.actions and 1 != 0
        binding.actionNotify.isChecked = r.actions and 2 != 0
        binding.actionSound.isChecked = r.actions and 4 != 0
        binding.actionLight.isChecked = r.actions and 8 != 0
        binding.switchAutoTrack.isChecked = r.autoTrack
    }

    private fun save() {
        val r = rule ?: DetectionRule(deviceId = deviceId)
        val type = when (binding.typeGroup.checkedRadioButtonId) {
            binding.typeMotion.id -> "motion"
            binding.typeTrack.id -> "track"
            else -> "human"
        }
        var actions = 0
        if (binding.actionRecord.isChecked) actions = actions or 1
        if (binding.actionNotify.isChecked) actions = actions or 2
        if (binding.actionSound.isChecked) actions = actions or 4
        if (binding.actionLight.isChecked) actions = actions or 8
        val updated = r.copy(
            type = type,
            sensitivity = binding.sensitivity.progress + 1,
            actions = actions,
            autoTrack = binding.switchAutoTrack.isChecked
        )
        viewModel.saveRule(updated)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_RULE_ID = "rule_id"
        fun intent(context: Context, deviceId: Long, ruleId: Long): Intent =
            Intent(context, DetectionRuleActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_RULE_ID, ruleId)
    }
}
