package com.cameramanager.app.ui.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Simple horizontal mic-level meter for the intercom screen.
 */
class LevelMeter @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var level: Int = 0 // 0..100
        set(value) { field = value.coerceIn(0, 100); invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFFFFF") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CAF50") }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, bgPaint)
        val fillW = w * level / 100f
        rect.set(0f, 0f, fillW, h)
        fillPaint.color = when {
            level > 80 -> Color.parseColor("#F44336")
            level > 50 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#4CAF50")
        }
        canvas.drawRoundRect(rect, h / 2, h / 2, fillPaint)
    }
}
