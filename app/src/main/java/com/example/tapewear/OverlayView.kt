package com.example.tapewear

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.min
import androidx.core.graphics.toColorInt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Message shown above the box */
    var statusText: String = "Align the tag in the box"
        set(value) {
            field = value
            invalidate()
        }

    // Box fills 60% of the shorter screen side
    private val roiScale = 0.60f
    private val cornerDp = 24f        // corner radius in dp
    private val borderDp = 4f         // border width in dp

    // Maize color for KNUST / TapeWear accent
    private val maize = "#FFCB05".toColorInt()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maize
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            16f,
            resources.displayMetrics
        )
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val roiRect = RectF()
    private val dp: Float
        get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val size = min(w, h) * roiScale
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        roiRect.set(left, top, left + size, top + size)

        borderPaint.strokeWidth = borderDp * dp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val r = cornerDp * dp
        canvas.drawRoundRect(roiRect, r, r, borderPaint)

        if (statusText.isNotEmpty()) {
            val y = (roiRect.top - 24f * dp).coerceAtLeast(24f * dp)
            canvas.drawText(statusText, width / 2f, y, textPaint)
        }
    }

    /** Rectangle used by OverlayDetector / framing logic */
    fun getFramingBox(): RectF = RectF(roiRect)
}