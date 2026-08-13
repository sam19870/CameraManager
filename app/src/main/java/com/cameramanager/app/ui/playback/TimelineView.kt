package com.cameramanager.app.ui.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar

/**
 * 24-hour timeline that renders recording segments as colored bars. Supports
 * tapping a segment to seek playback.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Segment(val startMs: Long, val durationMs: Long, val isMotion: Boolean = false)

    private var segments: List<Segment> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
    }
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55555555"); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888"); textSize = sp(10)
    }
    private val rect = RectF()

    private var onSegmentClickListener: ((Int) -> Unit)? = null

    @JvmName("setOnSegmentClickListener_")
    fun setOnSegmentClickListener(l: (Int) -> Unit) { onSegmentClickListener = l }

    fun setSegments(items: List<Pair<Long, Long>>) {
        segments = items.map { Segment(it.first, it.second, false) }
        invalidate()
    }

    fun setSegments(items: List<Pair<Long, Long>>, isMotionList: List<Boolean>) {
        require(items.size == isMotionList.size) { "items.size != isMotionList.size" }
        segments = items.mapIndexed { i, p -> Segment(p.first, p.second, isMotionList[i]) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // grid: every 6 hours
        val dayMs = 24 * 60 * 60 * 1000L
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        for (hr in 0..24 step 6) {
            val x = w * hr / 24f
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawText("${hr}:00", x + 4f, h - 4f, labelPaint)
        }
        // segments
        segments.forEachIndexed { i, seg ->
            val startOffset = (seg.startMs - dayStart).toFloat().coerceAtLeast(0f)
            val segStart = w * (startOffset / dayMs)
            val segWidth = (w * (seg.durationMs / dayMs.toFloat())).coerceAtLeast(4f)
            rect.set(segStart, h * 0.35f, segStart + segWidth, h * 0.75f)
            canvas.drawRoundRect(rect, 6f, 6f, if (seg.isMotion) motionPaint else barPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val w = width.toFloat()
            val dayMs = 24 * 60 * 60 * 1000L
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayStart = cal.timeInMillis
            val tapMs = dayStart + (x / w * dayMs).toLong()
            val idx = segments.indexOfFirst { tapMs in it.startMs..(it.startMs + it.durationMs) }
            if (idx >= 0) onSegmentClickListener?.invoke(idx)
        }
        return true
    }

    private fun sp(value: Int): Float =
        value * resources.displayMetrics.scaledDensity
}
