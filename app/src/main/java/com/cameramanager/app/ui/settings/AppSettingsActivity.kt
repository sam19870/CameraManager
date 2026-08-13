package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cameramanager.app.databinding.ActivityAppSettingsBinding
import com.cameramanager.app.ui.tunnel.TunnelManageActivity

/**
 * 应用设置（底部导航「设置」Tab入口）。
 * 包含：内网穿透通道管理、告警推送开关、后台服务开关、关于。
 *
 * 防闪退策略：onCreate 全 try-catch，所有跳转用 safeStart
 */
class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityAppSettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "应用设置"

            binding.cardTunnel.setOnClickListener {
                safeStart(TunnelManageActivity.intent(this))
            }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("设置页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun safeStart(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { t -> toast("打开失败: ${t.message ?: "未知错误"}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "AppSettings"
        fun intent(context: Context): Intent =
            Intent(context, AppSettingsActivity::class.java)
    }
}
