package com.cameramanager.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.databinding.ActivityPatrolBinding
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.net.NetworkRouter
import com.cameramanager.app.rtsp.RtspPlayer
import com.cameramanager.app.ui.preview.PreviewActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 视频轮巡（单画面自动轮播）：对一个摄像头停留 [intervalMs]，然后自动切到下一台。
 * 对齐参考视频监控系统的「视频轮询」模块体验。
 *
 *  - 顶部：当前通道名 + 连接状态
 *  - 控制条：上一台 / 暂停·继续 / 下一台
 *  - 底部：轮巡间隔滑杆 + 通道列表（点击某台立即切换）
 *  - 点击画面 → 全屏预览
 */
class PatrolActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatrolBinding
    private val viewModel: DeviceListViewModel by viewModels { DeviceViewModelFactory() }

    private var player: RtspPlayer? = null
    private var devices: List<Device> = emptyList()
    private var currentIndex = 0
    private var intervalMs = 5_000L
    private var playing = true
    private var switching = false
    private var ticker: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityPatrolBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)

            player = RtspPlayer(this).apply {
                listener = object : RtspPlayer.Listener {
                    override fun onStateChanged(state: RtspPlayer.State) {
                        runOnUiThread { binding.statusText.text = stateLabel(state) }
                    }
                    override fun onError(message: String) {
                        runOnUiThread { binding.statusText.text = "连接失败" }
                    }
                    override fun onStalled(timeoutCount: Int, lastError: String) {
                        runOnUiThread { binding.statusText.text = "超时: $timeoutCount" }
                    }
                    override fun onReconnecting(attempt: Int, max: Int) {
                        runOnUiThread { binding.statusText.text = "重连 $attempt/$max" }
                    }
                }
            }

            binding.btnPrev.setOnClickListener { runCatching { step(-1) } }
            binding.btnNext.setOnClickListener { runCatching { step(1) } }
            binding.btnPlayPause.setOnClickListener { runCatching { togglePlay() } }
            binding.videoLayout.setOnClickListener {
                runCatching { goFullPreview() }
            }

            binding.intervalSeek.setOnSeekBarChangeListener(object :
                android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: android.widget.SeekBar?, v: Int, fromUser: Boolean) {
                    intervalMs = ((v + 1) * 1000L).coerceAtLeast(2000L)
                    binding.intervalLabel.text = "${intervalMs / 1000} 秒"
                }
                override fun onStartTrackingTouch(seek: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seek: android.widget.SeekBar?) {}
            })

            lifecycleScope.launch {
                viewModel.devices.collectLatest { list ->
                    devices = list
                    binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    if (currentIndex >= list.size) currentIndex = 0
                    if (list.isNotEmpty()) showCurrent() else stopCurrent()
                }
            }
        }.onFailure { t ->
            Log.e("Patrol", "init failed: ${t.message}", t)
            Toast.makeText(this, "轮巡初始化失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showCurrent() {
        val d = devices.getOrNull(currentIndex) ?: return
        binding.currentName.text = "通道 ${currentIndex + 1}/${devices.size} · ${d.name}"
        binding.statusText.text = "连接中…"
        switchTo(d)
    }

    private fun switchTo(dev: Device) {
        val p = player ?: run { ticker?.cancel(); return }
        if (switching) return
        switching = true
        lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { NetworkRouter.resolve(this@PatrolActivity, dev) }
            val url = dev.rtspUrlForProfile(dev.streamProfile, useHost = route.host, usePort = route.rtspPort)
            p.play(binding.videoSurface, url, dev.streamProfile)
            switching = false
            restartTicker()
        }
    }

    private fun restartTicker() {
        ticker?.cancel()
        if (!playing || devices.size < 2) return
        ticker = lifecycleScope.launch {
            while (isActive) {
                delay(intervalMs)
                if (devices.size > 1) {
                    currentIndex = (currentIndex + 1) % devices.size
                    binding.currentName.text =
                        "通道 ${currentIndex + 1}/${devices.size} · ${devices.getOrNull(currentIndex)?.name ?: ""}"
                    switchTo(devices[currentIndex])
                }
            }
        }
    }

    private fun step(delta: Int) {
        if (devices.isEmpty()) return
        currentIndex = ((currentIndex + delta) % devices.size + devices.size) % devices.size
        showCurrent()
    }

    private fun togglePlay() {
        playing = !playing
        binding.btnPlayPause.text = if (playing) "暂停" else "继续"
        if (playing) restartTicker() else ticker?.cancel()
    }

    private fun goFullPreview() {
        val d = devices.getOrNull(currentIndex) ?: return
        runCatching {
            startActivity(PreviewActivity.intent(this, d.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { t -> Toast.makeText(this, "打开预览失败: ${t.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun stopCurrent() {
        ticker?.cancel()
        player?.stop()
        binding.currentName.text = "当前通道：--"
        binding.statusText.text = "空闲"
    }

    private fun stateLabel(state: RtspPlayer.State) = when (state) {
        RtspPlayer.State.IDLE -> "空闲"
        RtspPlayer.State.OPENING -> "连接中…"
        RtspPlayer.State.BUFFERING -> "缓冲中…"
        RtspPlayer.State.PLAYING -> "在线"
        RtspPlayer.State.PAUSED -> "已暂停"
        RtspPlayer.State.STOPPED -> "已停止"
        RtspPlayer.State.ENDED -> "已结束"
        RtspPlayer.State.ERROR -> "连接失败"
        else -> "连接中…"
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        ticker?.cancel()
        runCatching { player?.release() }
        super.onDestroy()
    }
}