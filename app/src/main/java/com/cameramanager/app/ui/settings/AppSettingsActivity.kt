package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.cameramanager.app.databinding.ActivityAppSettingsBinding
import com.cameramanager.app.ui.tunnel.TunnelManageActivity

/**
 * 应用设置（底部导航「设置」Tab入口）。
 * 包含：内网穿透通道管理、告警推送开关、后台服务开关、关于。
 */
class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.cardTunnel.setOnClickListener {
            startActivity(TunnelManageActivity.intent(this))
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AppSettingsActivity::class.java)
    }
}
