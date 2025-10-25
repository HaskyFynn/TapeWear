package com.example.tapewear

import java.nio.ByteBuffer
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

class FrameProcessor {

    private var prevSmall: ByteArray? = null

    /** Average brightness in ROI */
    fun lightingStatus(
        yPlane: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        imgH: Int,
        roi: Rect
    ): String {
        val w = roi.width()
        val h = roi.height()
        if (w <= 2 || h <= 2) return "Align the tag in the box"

        val buf = yPlane.duplicate()
        var sum = 0L
        var count = 0

        val stepX = max(2, w / 64)
        val stepY = max(2, h / 64)

        for (y in 0 until h step stepY) {
            buf.position((roi.top + y) * rowStride + roi.left)
            for (x in 0 until w step stepX) {
                val v = buf.get(x).toInt() and 0xFF
                sum += v
                count++
            }
        }

        if (count == 0) return "Align the tag in the box"
        val avg = (sum / count).toInt()

        return when {
            avg < 35  -> "Too dark"
            avg > 220 -> "Too bright"
            else      -> "Good lighting"
        }
    }

    /** Motion estimate on downsampled ROI. Returns "Hold steady" when movement is high, else "Stable". */
    fun motionStatus(
        yPlane: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        imgH: Int,
        roi: Rect
    ): String {
        val w = roi.width()
        val h = roi.height()
        if (w <= 4 || h <= 4) return "Align the tag in the box"

        // Copy ROI Y bytes
        val roiY = ByteArray(w * h)
        val base = yPlane.duplicate()
        for (r in 0 until h) {
            base.position((roi.top + r) * rowStride + roi.left)
            base.get(roiY, r * w, w)
        }

        // Downsample for speed
        val target = 48
        val sx = max(1, w / target)
        val sy = max(1, h / target)
        val smallW = max(1, w / sx)
        val smallH = max(1, h / sy)
        val small = ByteArray(smallW * smallH)

        var idx = 0
        for (yy in 0 until h step sy) {
            val row = yy * w
            for (xx in 0 until w step sx) {
                small[idx++] = roiY[row + xx]
            }
        }

        val prev = prevSmall
        prevSmall = small.copyOf()
        if (prev == null || prev.size != small.size) return "Align the tag in the box"

        var diff = 0L
        for (i in small.indices) {
            diff += abs((small[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
        }
        val meanDiff = diff.toDouble() / small.size
        return if (meanDiff > 10.0) "Hold steady" else "Stable"
    }
}
