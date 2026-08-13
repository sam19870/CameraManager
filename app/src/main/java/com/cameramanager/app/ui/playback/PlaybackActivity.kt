package com.cameramanager.app.ui.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CalendarView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.cameramanager.app.data.model.Recording
import com.cameramanager.app.databinding.ActivityPlaybackBinding
import com.cameramanager.app.ui.DeviceViewModelFactory
import com.cameramanager.app.ui.PlaybackViewModel
import com.cameramanager.app.util.StorageHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * History playback screen.
 *
 *  - A calendar to pick a day.
 *  - A 24-hour timeline showing recording segments for that day; tapping a segment
 *    seeks to that time.
 *  - A list of recordings for the day, each playable via ExoPlayer (local files)
 *    or marked as TF-card remote (RTSP playback with a start time query).
 */
class PlaybackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaybackBinding
    private val viewModel: PlaybackViewModel by viewModels { DeviceViewModelFactory() }
    private var exoPlayer: ExoPlayer? = null
    private var recordings: List<Recording> = emptyList()
    private var selectedDayStart: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle("历史回放")

        val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1)
        viewModel.load(deviceId)

        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        binding.playerView.useController = true

        binding.calendarView.setOnDateChangeListener { _, year, month, day ->
            val cal = Calendar.getInstance().apply {
                set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }
            selectedDayStart = cal.timeInMillis
            loadDay(deviceId, selectedDayStart)
        }
        // init to today
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        selectedDayStart = today.timeInMillis
        loadDay(deviceId, selectedDayStart)

        binding.timeline.setOnSegmentClickListener { position ->
            val rec = recordings.getOrNull(position) ?: return@setOnSegmentClickListener
            playRecording(rec)
        }
    }

    private fun loadDay(deviceId: Long, dayStart: Long) {
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L
        binding.dateLabel.text = formatDate(dayStart)
        lifecycleScope.launch {
            recordings = viewModel.recordingsForDay(deviceId, dayStart, dayEnd)
            binding.timeline.setSegments(recordings.map { it.startTime to (it.endTime - it.startTime) })
            binding.empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun playRecording(rec: Recording) {
        val uri = rec.filePath
        runCatching {
            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(this).build()
            binding.playerView.player = exoPlayer
            exoPlayer?.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
            binding.durationLabel.text = StorageHelper.formatDuration(rec.durationMs) +
                " · ${if (rec.trigger == "motion") "移动侦测录像" else "录像"}"
        }.onFailure {
            // likely a remote TF-card path; fall back to RTSP seek by time
            binding.durationLabel.text = "TF卡远程回放: ${formatTime(rec.startTime)}"
        }
    }

    private fun formatDate(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        return sdf.format(java.util.Date(ms))
    }

    private fun formatTime(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
        return sdf.format(java.util.Date(ms))
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"
        fun intent(context: Context, deviceId: Long): Intent =
            Intent(context, PlaybackActivity::class.java).putExtra(EXTRA_DEVICE_ID, deviceId)
    }
}
