package com.cameramanager.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cameramanager.app.databinding.ActivityAppSettingsBinding
import com.cameramanager.app.ui.scan.DeviceScanActivity
import com.cameramanager.app.ui.tunnel.TunnelManageActivity

/**
 * 应用设置（底部导航「设置」Tab入口）。
 * 包含：局域网扫描、内网穿透通道管理、告警推送开关、后台服务开关、关于。
 *
 * 开关状态 100% 持久化到 SharedPrefs，不再 XML 写死 android:checked="true" 导致"关不掉"。
 *
 * 防闪退策略：onCreate 全 try-catch，所有跳转用 safeStart（先resolveActivity）
 */
class AppSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityAppSettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "应用设置"

            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // ========== 回显：先读保存的值，再设 checked（不要 XML 写死默认 true） ==========
            // 初次安装的默认值：告警推送开、后台服务关（Android 12+ 后台服务用户要主动开，别默认常开耗流量耗电）
            val defaultAlarm = !prefs.contains(PREF_KEY_ALARM) || prefs.getBoolean(PREF_KEY_ALARM, true)
            val defaultBg = prefs.contains(PREF_KEY_BG) && prefs.getBoolean(PREF_KEY_BG, false)

            // 先 null 掉监听器，避免 setChecked 触发监听导致误写
            binding.switchAlarm.setOnCheckedChangeListener(null)
            binding.switchBackground.setOnCheckedChangeListener(null)
            binding.switchAlarm.isChecked = defaultAlarm
            binding.switchBackground.isChecked = defaultBg

            // 跳转卡片
            binding.cardTunnel.setOnClickListener {
                safeStart(TunnelManageActivity.intent(this))
            }
            binding.cardLanScan.setOnClickListener {
                safeStart(Intent(this, DeviceScanActivity::class.java))
            }

            // ========== 开关监听器：用户改值立刻 commit，下次进来就能回显到用户关/开的状态 ==========
            binding.switchAlarm.setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(PREF_KEY_ALARM, on).apply()
                toast(if (on) "已开启告警推送" else "已关闭告警推送")
            }
            binding.switchBackground.setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(PREF_KEY_BG, on).apply()
                // TODO: 等真正的后台侦测服务 ForegroundService 接入后再 startForegroundService / stopService
                // 现在先保证"用户关了它就真的关了"的语义，下次启动 App 时按 prefs 判断拉不拉服务
                toast(if (on) "已开启后台侦测（保持运行以持续监控）" else "已关闭后台侦测，退出即停止监控")
            }
        }.onFailure { t ->
            Log.e(TAG, "onCreate failed: ${t.message}", t)
            toast("设置页初始化失败: ${t.message}")
            finish()
        }
    }

    private fun safeStart(intent: Intent) {
        runCatching {
            val c = intent.resolveActivity(packageManager)
            if (c == null) { toast("目标页未注册，无法打开"); return }
            // 关键：SINGLE_TOP + 禁止重复启动，防止"像浏览器一样开新页"+闪屏
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }.onFailure { t -> toast("打开失败: ${t.message ?: "未知错误"}") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    companion object {
        private const val TAG = "AppSettings"
        private const val PREFS_NAME = "app_global_settings"
        internal const val PREF_KEY_ALARM = "alarm_push_enabled"
        internal const val PREF_KEY_BG = "bg_detect_service_enabled"

        /** 对外读：告警推送开关 */
        fun isAlarmEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY_ALARM, true)

        /** 对外读：后台侦测服务开关 */
        fun isBgDetectEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY_BG, false)

        fun intent(context: Context): Intent =
            Intent(context, AppSettingsActivity::class.java)
    }
}
