package com.cameramanager.app.service

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Frame-difference based motion & human-shape detector.
 *
 * Strategy:
 *  - Downscale frames to a small grid (e.g. 32x24) for performance.
 *  - Compare cell luminance between consecutive frames.
 *  - A cell counts as "changed" if the delta exceeds a threshold scaled by
 *    [sensitivity] (1..5). This filters out minor light changes.
 *  - Human-shape filter: require the changed region's bounding box aspect ratio
 *    (height > width) and minimum area, which rejects flying insects (tiny
 *    scattered points) and flicker (whole-frame diffuse changes).
 */
class MotionDetector(
    private val sensitivity: Int = 3,
    private val detectType: String = "human"
) {

    private val gridW = 32
    private val gridH = 24
    private var prevGrid: IntArray? = null

    /** Returns true if motion/human detected in this frame. */
    fun process(frame: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(frame, gridW, gridH, false)
        val grid = IntArray(gridW * gridH)
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val px = small.getPixel(x, y)
                grid[y * gridW + x] = luminance(px)
            }
        }
        small.recycle()
        val prev = prevGrid
        prevGrid = grid
        if (prev == null) return false

        // threshold scaled by sensitivity (higher sensitivity => smaller threshold)
        val threshold = 40 - sensitivity * 6  // sens 1->34, 5->10
        val minCells = when (detectType) {
            "human" -> 12 + (5 - sensitivity) * 4
            else -> 6 + (5 - sensitivity) * 3
        }

        var changed = 0
        var minX = gridW; var maxX = 0; var minY = gridH; var maxY = 0
        for (i in grid.indices) {
            if (abs(grid[i] - prev[i]) > threshold) {
                changed++
                val x = i % gridW
                val y = i / gridW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (changed < minCells) return false

        // Human-shape heuristic: vertical bounding box dominant.
        if (detectType == "human") {
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw <= 0 || bh <= 0) return false
            // reject wide horizontal bands (light changes / passing vehicles edge)
            if (bh < bw * 0.8f) return false
            // reject tiny single-cell noise (insects)
            if (changed < 20 && bw < 4 && bh < 4) return false
        }
        return true
    }

    private fun luminance(pixel: Int): Int {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    fun reset() { prevGrid = null }
}
