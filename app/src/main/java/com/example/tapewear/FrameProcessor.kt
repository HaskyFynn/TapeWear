package com.example.tapewear

import java.nio.ByteBuffer
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FrameProcessor {

    private var prevSmall: ByteArray? = null

    /**
     * Estimate average brightness in Y-plane over ROI and return a simple status.
     * Uses sub-sampling so it stays cheap.
     */
    fun lightingStatus(
        yPlane: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        imgH: Int,
        roiIn: Rect
    ): String {
        if (imgW <= 0 || imgH <= 0) return "Align the tag in the box"

        // Clamp ROI to image bounds defensively
        val roi = Rect(
            roiIn.left.coerceIn(0, imgW - 1),
            roiIn.top.coerceIn(0, imgH - 1),
            roiIn.right.coerceIn(1, imgW),
            roiIn.bottom.coerceIn(1, imgH)
        )

        val w = roi.width()
        val h = roi.height()
        if (w <= 2 || h <= 2) return "Align the tag in the box"

        val buf = yPlane.duplicate()
        var sum = 0L
        var count = 0

        val stepX = max(2, w / 64)
        val stepY = max(2, h / 64)

        // Sample Y values inside ROI using absolute indices
        for (y in 0 until h step stepY) {
            val baseIndex = (roi.top + y) * rowStride + roi.left
            for (x in 0 until w step stepX) {
                val idx = baseIndex + x
                if (idx < 0 || idx >= buf.capacity()) continue
                val v = buf.get(idx).toInt() and 0xFF
                sum += v
                count++
            }
        }

        if (count == 0) return "Align the tag in the box"
        val avg = (sum / count).toInt()

        val status = when {
            avg < 35  -> "Too dark"
            avg > 220 -> "Too bright"
            else      -> "Good lighting"
        }

        Log.d(
            "TapeWear_FrameProc",
            "lightingStatus: avgY=$avg count=$count roi=$roi status=$status"
        )
        return status
    }

    /**
     * Very coarse motion estimate over ROI using frame-to-frame mean absolute difference
     * on a heavily downsampled Y patch.
     */
    fun motionStatus(
        yPlane: ByteBuffer,
        rowStride: Int,
        imgW: Int,
        imgH: Int,
        roiIn: Rect
    ): String {
        if (imgW <= 0 || imgH <= 0) return "Align the tag in the box"

        // Clamp ROI to image bounds
        val roi = Rect(
            roiIn.left.coerceIn(0, imgW - 1),
            roiIn.top.coerceIn(0, imgH - 1),
            roiIn.right.coerceIn(1, imgW),
            roiIn.bottom.coerceIn(1, imgH)
        )

        val w = roi.width()
        val h = roi.height()
        if (w <= 4 || h <= 4) return "Align the tag in the box"

        // Copy ROI Y bytes into a dense array
        val roiY = ByteArray(w * h)
        val base = yPlane.duplicate()
        for (r in 0 until h) {
            val srcPos = (roi.top + r) * rowStride + roi.left
            if (srcPos < 0 || srcPos + w > base.capacity()) break
            base.position(srcPos)
            base.get(roiY, r * w, w)
        }

        // Downsample for speed
        val target = 48
        val sx = max(1, w / target)
        val sy = max(1, h / target)

        fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
        val smallW = ceilDiv(w, sx)
        val smallH = ceilDiv(h, sy)
        val small = ByteArray(smallW * smallH)

        var idx = 0
        for (yy in 0 until h step sy) {
            val row = yy * w
            for (xx in 0 until w step sx) {
                if (idx >= small.size) break
                small[idx++] = roiY[row + xx]
            }
            if (idx >= small.size) break
        }

        val prev = prevSmall
        prevSmall = small.copyOf()

        if (prev == null || prev.size != small.size) {
            Log.d("TapeWear_FrameProc", "motionStatus: first frame or size change, asking to align")
            return "Align the tag in the box"
        }

        var diff = 0L
        for (i in small.indices) {
            diff += abs(
                (small[i].toInt() and 0xFF) -
                        (prev[i].toInt() and 0xFF)
            )
        }
        val meanDiff = diff.toDouble() / small.size

        val status = if (meanDiff > 10.0) "Hold steady" else "Stable"
        Log.d(
            "TapeWear_FrameProc",
            "motionStatus: meanDiff=%.2f smallW=%d smallH=%d status=%s"
                .format(meanDiff, smallW, smallH, status)
        )
        return status
    }
}