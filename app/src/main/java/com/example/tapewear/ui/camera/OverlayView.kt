package com.example.tapewear.ui.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.tapewear.ml.ModelManager
import kotlin.math.min
import androidx.core.graphics.toColorInt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class LiveDetection(
        val box: RectF,
        val score: Float,
        val quad: List<PointF>? = null
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

    private val accentColor = "#FFCB05".toColorInt()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
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
    private val previewRect = RectF()
    private val drawDetRect = RectF()
    private val drawLabelRect = RectF()
    private val drawQuadPath = Path()
    private val dp: Float
        get() = resources.displayMetrics.density

    @Volatile
    private var liveDetections: List<LiveDetection> = emptyList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRoiRect(w, h)
        borderPaint.strokeWidth = borderDp * dp
    }

    private fun updateRoiRect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            roiRect.setEmpty()
            return
        }
        val bounds = if (previewRect.width() > 0f && previewRect.height() > 0f) {
            previewRect
        } else {
            RectF(0f, 0f, w.toFloat(), h.toFloat())
        }
        val size = min(bounds.width(), bounds.height()) * roiScale
        val left = bounds.centerX() - size / 2f
        val baseTop = bounds.centerY() - size / 2f
        val top = (baseTop + bounds.height() * roiVerticalBias)
            .coerceIn(bounds.top + 16f * dp, bounds.bottom - size - 16f * dp)
        roiRect.set(left, top, left + size, top + size)
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

            val quad = det.quad
            if (quad != null && quad.size == 4) {
                drawQuadPath.reset()
                drawQuadPath.moveTo(quad[0].x, quad[0].y)
                for (i in 1 until 4) {
                    drawQuadPath.lineTo(quad[i].x, quad[i].y)
                }
                drawQuadPath.close()
                canvas.drawPath(drawQuadPath, detStrokePaint)
            }

            val label = "${ModelManager.activeDetectorLabel()} ${(det.score * 100f).toInt()}%"
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

    fun setPreviewContentRect(rect: RectF?) {
        if (rect == null || rect.width() <= 0f || rect.height() <= 0f) {
            previewRect.setEmpty()
        } else {
            previewRect.set(rect)
        }
        updateRoiRect(width, height)
        postInvalidateOnAnimation()
    }

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
