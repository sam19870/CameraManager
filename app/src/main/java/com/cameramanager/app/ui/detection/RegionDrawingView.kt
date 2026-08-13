package com.cameramanager.app.ui.detection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.cameramanager.app.vendor.CameraVendorApi

/**
 * View on which the user draws a polygonal detection / privacy region with their
 * finger. Taps add vertices; a "close" gesture (tap near the first vertex) closes
 * the polygon. Multiple polygons can be drawn.
 *
 * Emits normalized rectangles ([CameraVendorApi.Rect]) via [onRegionsChanged].
 */
class RegionDrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val polygons = mutableListOf<MutableList<Pair<Float, Float>>>()
    private var current: MutableList<Pair<Float, Float>>? = null

    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800"); strokeWidth = 4f; style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800"); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FF9800"); style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER
    }

    var onRegionsChanged: ((List<List<CameraVendorApi.Rect>>) -> Unit)? = null

    fun reset() {
        polygons.clear(); current = null
        invalidate()
    }

    fun finishPolygon() {
        current?.let { if (it.size >= 3) polygons.add(it) }
        current = null
        emitRegions()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // background hint
        if (polygons.isEmpty() && current == null) {
            canvas.drawText("点击画面添加侦测区域顶点\n长按或点起点闭合多边形",
                width / 2f, height / 2f, hintPaint)
        }
        polygons.forEach { drawPolygon(canvas, it, closed = true) }
        current?.let { drawPolygon(canvas, it, closed = false) }
    }

    private fun drawPolygon(canvas: Canvas, pts: List<Pair<Float, Float>>, closed: Boolean) {
        if (pts.size < 2) {
            pts.forEach { canvas.drawCircle(it.first, it.second, 10f, vertexPaint) }
            return
        }
        val path = Path()
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
        if (closed) {
            path.close()
            canvas.drawPath(path, fillPaint)
        }
        canvas.drawPath(path, linePaint)
        pts.forEach { canvas.drawCircle(it.first, it.second, 10f, vertexPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x; val y = event.y
                val cur = current ?: mutableListOf<Pair<Float, Float>>().also { current = it }
                // close polygon if tapping near first vertex
                if (cur.size >= 3) {
                    val first = cur[0]
                    if (kotlin.math.abs(first.first - x) < 40 && kotlin.math.abs(first.second - y) < 40) {
                        polygons.add(cur); current = null; emitRegions(); invalidate()
                        return true
                    }
                }
                cur.add(x to y)
                invalidate()
            }
        }
        return true
    }

    /** Convert polygons to a list of bounding rectangles (normalized 0..1). */
    private fun emitRegions() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val rects = polygons.map { poly ->
            val minX = poly.minOf { it.first }; val maxX = poly.maxOf { it.first }
            val minY = poly.minOf { it.second }; val maxY = poly.maxOf { it.second }
            listOf(CameraVendorApi.Rect(minX / w, minY / h, (maxX - minX) / w, (maxY - minY) / h))
        }
        onRegionsChanged?.invoke(rects)
    }
}
