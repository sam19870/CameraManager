package com.cameramanager.app.ui.detection

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.databinding.ActivityDetectionRegionBinding
import com.cameramanager.app.vendor.CameraController
import com.cameramanager.app.vendor.CameraVendorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draw custom detection regions on top of the preview and push them to the
 * device. Detect types: human / motion / intrusion.
 */
class DetectionRegionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionRegionBinding
    private var controller: CameraController? = null
    private var regions: List<List<CameraVendorApi.Rect>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionRegionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("自定义侦测区域")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "human"

        lifecycleScope.launch {
            val device = com.cameramanager.app.CameraApp.get().repository.getDevice(deviceId) ?: return@launch
            controller = CameraController(device).also { it.refreshCapabilities() }
        }

        binding.drawingView.onRegionsChanged = { regions = it }
        binding.btnClear.setOnClickListener { binding.drawingView.reset(); regions = emptyList() }
        binding.btnFinish.setOnClickListener { binding.drawingView.finishPolygon() }
        binding.btnSave.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            val flat = regions.flatten()
            if (flat.isEmpty()) {
                Toast.makeText(this, "请先绘制至少一个区域", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { c.setDetectionRegion(type, flat) }
                val msg = when (result) {
                    is CameraController.CameraCommandResult.Ok -> "已保存侦测区域"
                    is CameraController.CameraCommandResult.OkWithMessage -> result.text
                    is CameraController.CameraCommandResult.Unsupported -> result.message
                    is CameraController.CameraCommandResult.Failed -> result.message
                }
                Toast.makeText(this@DetectionRegionActivity, msg, Toast.LENGTH_LONG).show()
                if (result is CameraController.CameraCommandResult.Ok) finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_TYPE = "type"
        fun intent(context: Context, deviceId: Long, type: String = "human"): Intent =
            Intent(context, DetectionRegionActivity::class.java)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_TYPE, type)
    }
}
