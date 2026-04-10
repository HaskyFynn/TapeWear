package com.example.tapewear.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared image analysis utilities used by both registration and authentication.
 * Extracted to eliminate duplication across activities.
 */
object ImageUtils {

    /**
     * Average luminance of the bitmap (0..255), subsampled ×2 for speed.
     */
    fun meanLuma(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w); var sum = 0L; var cnt = 0; var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                sum += (r + g + b) / 3; cnt++; x += 2
            }
            y += 2
        }
        return if (cnt == 0) 128.0 else sum.toDouble() / cnt
    }

    /**
     * Edge-based sharpness metric (higher = sharper), subsampled ×2.
     */
    fun blurMetric(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var acc = 0.0; var cnt = 0
        var y = 1
        while (y < h - 1) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 1
            while (x < w - 1) {
                val l = row[x - 1] and 0xFF
                val r = row[x + 1] and 0xFF
                val dx = r - l
                acc += (dx * dx).toDouble()
                cnt++
                x += 2
            }
            y += 2
        }
        return if (cnt == 0) 0.0 else acc / cnt
    }

    /**
     * Mean absolute pixel difference between two bitmaps (motion metric), subsampled ×3.
     */
    fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val w = min(a.width, b.width)
        val h = min(a.height, b.height)
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        var sum = 0L; var cnt = 0
        var y = 0
        while (y < h) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val pa = rowA[x] and 0xFF
                val pb = rowB[x] and 0xFF
                sum += abs(pa - pb)
                cnt++
                x += 3
            }
            y += 3
        }
        return if (cnt == 0) 0.0 else sum.toDouble() / cnt
    }
}
