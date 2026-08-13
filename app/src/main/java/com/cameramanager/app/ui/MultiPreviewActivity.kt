package com.cameramanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.cameramanager.app.databinding.ActivityMultiPreviewBinding
import com.cameramanager.app.ui.preview.PreviewActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 多分屏集中预览：支持 1/4/6/9 分屏，首页进来后摄像头直接显示画面（无需点击图标）。
 * 点击某格 → 全屏预览。
 *
 * 防闪退策略：
 *  - onCreate 全 try-catch
 *  - 跳转预览用 safeStart
 */
class MultiPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiPreviewBinding
    private val viewModel: DeviceListViewModel by viewModels { DeviceViewModelFactory() }
    private lateinit var adapter: MultiPreviewAdapter
    private var spanCount = 2

    companion object { private const val TAG = "MultiPreview" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityMultiPreviewBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "分屏预览"

            adapter = MultiPreviewAdapter(
                onClick = { safeStart(PreviewActivity.intent(this, it.id)) }
            )
            binding.recycler.layoutManager = GridLayoutManager(this, spanCount)
            binding.recycler.adapter = adapter

            // 分屏切换按钮（1/4/6/9）
            binding.btnSpan1?.setOnClickListener { changeSpan(1) }
            binding.btnSpan2?.setOnClickListener { changeSpan(2) }
            binding.btnSpan3?.setOnClickListener { changeSpan(3) }
            binding.btnSpan4?.setOnClickListener { changeSpan(3, true) }

            lifecycleScope.launch {
                viewModel.devices.collectLatest { devices ->
                    adapter.submit(devices)
                    binding.emptyState?.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("分屏初始化失败: ${t.message}")
            finish()
        }
    }

    private fun changeSpan(span: Int, grid3x3: Boolean = false) {
        spanCount = if (grid3x3) 3 else span
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        adapter.notifyDataSetChanged()
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { t -> toast("打开失败: ${t.message ?: "未知错误"}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
