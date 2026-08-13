package com.cameramanager.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.cameramanager.app.databinding.ActivityMultiPreviewBinding
import com.cameramanager.app.ui.preview.PreviewActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Multi-device centralized view: shows all devices in a grid with live preview
 * tiles. Tap a tile to open the full-screen preview.
 */
class MultiPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiPreviewBinding
    private val viewModel: DeviceListViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: MultiPreviewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = MultiPreviewAdapter(
            onClick = { PreviewActivity.intent(this, it.id).let(::startActivity) }
        )
        binding.recycler.layoutManager = GridLayoutManager(this, 2)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            viewModel.devices.collectLatest { devices ->
                adapter.submit(devices)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
