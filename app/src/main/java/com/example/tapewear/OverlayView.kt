package com.example.tapewear

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

@Suppress("DEPRECATION")
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Message shown above the box */
    var statusText: String = "Align the tag in the box"
        set(value) { field = value; invalidate() }

    // --- Tunables ---
    private val roiScale = 0.60f           // box = 60% of the shorter side
    private val cornerDp = 24f             // corner radius in dp
    private val borderDp = 4f              // border width in dp

    // --- Colors (UMich) ---
    private val maize = Color.parseColor("#FFCB05")
    private val blue = Color.parseColor("#00274C")   // Michigan Blue (OPAQUE)

    // --- Paints ---
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = maize
        style = Paint.Style.STROKE
    }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blue
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val roiRect = RectF()
    private val maskPath = Path()
    private val dp get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Centered square ROI
        val size = min(w, h) * roiScale
        val left = (w - size) / 2f
        val top  = (h - size) / 2f
        roiRect.set(left, top, left + size, top + size)

        borderPaint.strokeWidth = borderDp * dp

        // Create a path for the mask. This is the key change.
        // We create a path that is the entire view, then we subtract the ROI rectangle.
        maskPath.reset()
        maskPath.fillType = Path.FillType.EVEN_ODD
        maskPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        val r = cornerDp * dp
        maskPath.addRoundRect(roiRect, r, r, Path.Direction.CCW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) Draw the mask path. This single command draws the blue overlay WITH the hole.
        canvas.drawPath(maskPath, maskPaint)

        // 2) Draw maize border around ROI
        val r = cornerDp * dp
        canvas.drawRoundRect(roiRect, r, r, borderPaint)

        // 3) Optional status text above ROI
        if (statusText.isNotEmpty()) {
            val y = (roiRect.top - 24f * dp).coerceAtLeast(24f * dp)
            canvas.drawText(statusText, width / 2f, y, textPaint)
        }
    }

    /** Expose ROI in view coordinates for cropping */
    fun getFramingBox(): RectF = RectF(roiRect)
}
