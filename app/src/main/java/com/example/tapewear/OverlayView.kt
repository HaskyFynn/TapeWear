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

    data class LiveDetection(
        val box: RectF,
        val score: Float
    )

    /** Message shown above the box */
    var statusText: String = "Align the tag in the box"
        set(value) {
            field = value
            invalidate()
        }

    // Box fills 60% of the shorter screen side
    private val roiScale = 0.60f
    private val roiVerticalBias = -0.12f // negative = move up
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

    private val detStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#00E5FF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val detLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#88000000".toColorInt()
        style = Paint.Style.FILL
    }

    private val detLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val roiRect = RectF()
    private val drawDetRect = RectF()
    private val drawLabelRect = RectF()
    private val dp: Float
        get() = resources.displayMetrics.density

    @Volatile
    private var liveDetections: List<LiveDetection> = emptyList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val size = min(w, h) * roiScale
        val left = (w - size) / 2f
        val baseTop = (h - size) / 2f
        val top = (baseTop + h * roiVerticalBias).coerceIn(16f * dp, h - size - 16f * dp)
        roiRect.set(left, top, left + size, top + size)

        borderPaint.strokeWidth = borderDp * dp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val r = cornerDp * dp
        canvas.drawRoundRect(roiRect, r, r, borderPaint)

        for (det in liveDetections) {
            drawDetRect.set(
                det.box.left.coerceIn(0f, width.toFloat()),
                det.box.top.coerceIn(0f, height.toFloat()),
                det.box.right.coerceIn(0f, width.toFloat()),
                det.box.bottom.coerceIn(0f, height.toFloat())
            )
            if (drawDetRect.width() <= 1f || drawDetRect.height() <= 1f) continue

            canvas.drawRoundRect(drawDetRect, 10f * dp, 10f * dp, detStrokePaint)

            val label = "YOLO ${(det.score * 100f).toInt()}%"
            val labelW = detLabelTextPaint.measureText(label) + 12f * dp
            val labelH = 18f * dp
            val labelLeft = drawDetRect.left
            val labelTop = (drawDetRect.top - labelH - 4f * dp).coerceAtLeast(0f)
            drawLabelRect.set(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH)

            canvas.drawRoundRect(drawLabelRect, 6f * dp, 6f * dp, detLabelBgPaint)
            canvas.drawText(
                label,
                drawLabelRect.left + 6f * dp,
                drawLabelRect.bottom - 5f * dp,
                detLabelTextPaint
            )
        }

        if (statusText.isNotEmpty()) {
            val y = (roiRect.top - 24f * dp).coerceAtLeast(24f * dp)
            canvas.drawText(statusText, width / 2f, y, textPaint)
        }
    }

    /** Rectangle used by OverlayDetector / framing logic */
    fun getFramingBox(): RectF = RectF(roiRect)

    fun setLiveDetections(detections: List<LiveDetection>) {
        liveDetections = detections
        postInvalidateOnAnimation()
    }

    fun clearLiveDetections() {
        if (liveDetections.isEmpty()) return
        liveDetections = emptyList()
        postInvalidateOnAnimation()
    }
}
