package com.cameramanager.app.ui.scan

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.data.model.ScannedDevice
import com.cameramanager.app.databinding.ActivityDeviceScanBinding
import com.cameramanager.app.net.NetworkScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * LAN device scanning screen.
 *
 *  - Acquires a multicast lock so ONVIF WS-Discovery works on Wi-Fi.
 *  - Runs [NetworkScanner.scan] (ONVIF discovery + /24 port probe) with a progress
 *    bar, then lists discovered devices.
 *  - Tapping a device opens [AddDeviceActivity] pre-filled with its address.
 *  - A manual entry button allows adding by IP without scanning.
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceScanBinding
    private lateinit var adapter: ScannedDeviceAdapter
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("局域网设备扫描")

        adapter = ScannedDeviceAdapter { add(it) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnRescan.setOnClickListener { startScan() }
        binding.btnAddManual.setOnClickListener {
            startActivity(Intent(this, AddDeviceActivity::class.java))
        }

        startScan()
    }

    private fun startScan() {
        binding.progress.visibility = View.VISIBLE
        binding.btnRescan.isEnabled = false
        adapter.submit(emptyList())
        acquireMulticast()

        lifecycleScope.launch {
            val devices = withContext(Dispatchers.IO) {
                NetworkScanner.scan(this@DeviceScanActivity) { p ->
                    runOnUiThread { binding.progress.progress = p }
                }
            }
            binding.progress.visibility = View.GONE
            binding.btnRescan.isEnabled = true
            adapter.submit(devices)
            if (devices.isEmpty()) {
                Toast.makeText(this@DeviceScanActivity, "未发现设备，请确认手机与摄像头在同一 Wi-Fi", Toast.LENGTH_LONG).show()
            }
            releaseMulticast()
        }
    }

    private fun add(device: ScannedDevice) {
        startActivity(
            AddDeviceActivity.intent(this, device.host, device.port, device.onvif)
        )
    }

    private fun acquireMulticast() {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("onvif_discovery").apply { acquire() }
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        releaseMulticast()
    }
}
