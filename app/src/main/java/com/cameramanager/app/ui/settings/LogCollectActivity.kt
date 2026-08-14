package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.cameramanager.app.databinding.ActivityLogCollectBinding
import com.cameramanager.app.util.LogCollector

/**
 * 日志收集页：查看日志占用、导出分享、清空日志。
 * 用于排查「视频无法播放 / 模块出错 / 崩溃异常」等运行时问题。
 */
class LogCollectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogCollectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityLogCollectBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "日志收集"

            refreshInfo()

            binding.txtExport.setOnClickListener { exportAndShare() }
            binding.txtClear.setOnClickListener { confirmClear() }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("日志页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun refreshInfo() {
        val count = LogCollector.logFileCount()
        val size = LogCollector.logDirSize()
        binding.tvSummary.text = if (count == 0) {
            "暂无日志。\n当出现视频无法播放、报错、闪退时，日志会自动记录在这里。"
        } else {
            "已记录 $count 个日志文件，共 ${formatSize(size)}。\n导出后请把文件发给我，以便定位问题。"
        }
    }

    private fun exportAndShare() {
        runCatching {
            val uri = LogCollector.exportAsUri()
            if (uri == null) {
                toast("暂无日志可导出")
                return
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CameraManager 日志")
                putExtra(Intent.EXTRA_TEXT, "CameraManager 运行日志，请帮我排查问题。")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享日志"))
        }.onFailure { t ->
            Log.e(TAG, "export failed: ${t.message}", t)
            toast("导出失败: ${t.message ?: "未知错误"}")
        }
    }

    private fun confirmClear() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清空日志")
            .setMessage("确定要清空全部日志吗？")
            .setPositiveButton("清空") { _, _ ->
                runCatching { LogCollector.clearAll() }
                    .onSuccess { toast("已清空"); refreshInfo() }
                    .onFailure { t -> toast("清空失败: ${t.message}") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "LogCollect"
        fun intent(context: Context): Intent = Intent(context, LogCollectActivity::class.java)
    }
}