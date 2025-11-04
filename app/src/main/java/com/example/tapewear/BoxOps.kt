package com.example.tapewear

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object BoxOps {
    fun clamp(r: RectF, w: Int, h: Int): Rect {
        val L = r.left.coerceIn(0f, (w - 1).toFloat())
        val T = r.top.coerceIn(0f, (h - 1).toFloat())
        val R = r.right.coerceIn(L + 1f, w.toFloat())
        val B = r.bottom.coerceIn(T + 1f, h.toFloat())
        return Rect(L.toInt(), T.toInt(), R.toInt(), B.toInt())
    }

    fun expand(r: RectF, scale: Float, w: Int, h: Int): RectF {
        val cx = (r.left + r.right) * 0.5f
        val cy = (r.top + r.bottom) * 0.5f
        val hw = (r.width() * 0.5f) * scale
        val hh = (r.height() * 0.5f) * scale
        val L = (cx - hw).coerceIn(0f, (w - 1).toFloat())
        val T = (cy - hh).coerceIn(0f, (h - 1).toFloat())
        val R = (cx + hw).coerceIn(L + 1f, w.toFloat())
        val B = (cy + hh).coerceIn(T + 1f, h.toFloat())
        return RectF(L, T, R, B)
    }

    fun iou(a: RectF, b: RectF): Float {
        val L = max(a.left, b.left)
        val T = max(a.top, b.top)
        val R = min(a.right, b.right)
        val B = min(a.bottom, b.bottom)
        val iw = (R - L).coerceAtLeast(0f)
        val ih = (B - T).coerceAtLeast(0f)
        val inter = iw * ih
        val ua = a.width() * a.height() + b.width() * b.height() - inter
        return if (ua <= 0f) 0f else inter / ua
    }
}
