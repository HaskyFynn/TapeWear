package com.example.tapewear

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

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
        // OPAQUE mask: covers everything except the ROI "hole"
        color = blue
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val roiRect = RectF()
    private val dp get() = resources.displayMetrics.density

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Centered square ROI
        val size = min(w, h) * roiScale
        val left = (w - size) / 2f
        val top  = (h - size) / 2f
        roiRect.set(left, top, left + size, top + size)

        borderPaint.strokeWidth = borderDp * dp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val r = cornerDp * dp

        // 1) Draw an OPAQUE blue layer over the whole screen
        val save = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, w, h, maskPaint)

        // 2) Punch a CLEAR rounded hole where the ROI is
        canvas.drawRoundRect(roiRect, r, r, clearPaint)
        canvas.restoreToCount(save)

        // 3) Draw maize border around ROI
        canvas.drawRoundRect(roiRect, r, r, borderPaint)

        // 4) Optional status text above ROI (kept within screen)
        if (statusText.isNotEmpty()) {
            val y = (roiRect.top - 24f * dp).coerceAtLeast(24f * dp)
            canvas.drawText(statusText, w / 2f, y, textPaint)
        }
    }

    /** Expose ROI in view coordinates for cropping */
    fun getFramingBox(): RectF = RectF(roiRect)
}
