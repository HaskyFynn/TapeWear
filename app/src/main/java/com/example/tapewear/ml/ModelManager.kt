package com.example.tapewear.ml

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.core.graphics.scale
import com.example.tapewear.BoxOps
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.EnrollmentStore
import com.example.tapewear.ui.camera.OverlayView
import com.example.tapewear.util.MathUtils
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Facade for the auth pipeline.
 *
 * Detector and Embedder interfaces + implementations live here.
 * Scoring, enrollment persistence, math, and config are delegated to:
 *   [ScoringEngine], [EnrollmentStore], [MathUtils], [AuthConfig].
 */
object ModelManager {

    // =====================================================================
    //  Detector interface + implementations
    // =====================================================================

    interface Detector : Closeable {
        fun detect(full: Bitmap): List<Detection>
        override fun close() {}
    }

    data class Detection(
        val box: RectF,
        val score: Float,
        val classIndex: Int,
        val supportQuad: List<PointF>? = null
    )

    /**
     * Simple detector that maps the overlay box to bitmap coordinates.
     * Fallback when YOLO is not available.
     */
    class OverlayDetector(
        private val overlay: OverlayView?,
        private val texture: TextureView?
    ) : Detector {
        override fun detect(full: Bitmap): List<Detection> {
            Log.w("TapeWear_YOLO", "OverlayDetector is disabled for research runs; returning empty detections.")
            return emptyList()
        }
    }

    /**
     * YOLO-based detector for [1, 5, N] TFLite model.
     */
    class TFLiteYoloDetector(
        context: Context,
        assetName: String = "best_float32.tflite",
        numThreads: Int = 2
    ) : Detector {

        private val interpreter: Interpreter
        private val inSize: Int
        private val outChannels: Int
        private val outCount: Int

        companion object {
            private const val TAG = "TapeWear_YOLO"

            private fun mapAsset(context: Context, assetName: String): ByteBuffer {
                val afd = context.assets.openFd(assetName)
                val fis = FileInputStream(afd.fileDescriptor)
                val channel = fis.channel
                val mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.length
                )
                fis.close()
                afd.close()
                return mapped
            }
        }

        init {
            val t0 = SystemClock.elapsedRealtime()

            val opts = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseNNAPI(false)
            }
            interpreter = Interpreter(mapAsset(context, assetName), opts)

            val inTensor = interpreter.getInputTensor(0)
            val inShape = inTensor.shape()
            require(inShape.size == 4) {
                "YOLO input must be [1,H,W,C], got ${inShape.contentToString()}"
            }
            inSize = inShape[1]

            val outTensor = interpreter.getOutputTensor(0)
            val outShape = outTensor.shape()
            require(outShape.size == 3 && outShape[1] == 5) {
                "YOLO output must be [1,5,N], got ${outShape.contentToString()}"
            }
            outChannels = outShape[1]
            outCount = outShape[2]

            val t1 = SystemClock.elapsedRealtime()
            Log.i(TAG, "YOLO ready: inSize=$inSize out=[1,$outChannels,$outCount] in ${t1 - t0} ms")
        }

        @Synchronized
        override fun detect(full: Bitmap): List<Detection> {
            val resized = full.scale(inSize, inSize, filter = true)

            val input = ByteBuffer
                .allocateDirect(4 * inSize * inSize * 3)
                .order(ByteOrder.nativeOrder())

            val row = IntArray(inSize)
            for (y in 0 until inSize) {
                resized.getPixels(row, 0, inSize, 0, y, inSize, 1)
                for (x in 0 until inSize) {
                    val p = row[x]
                    val r = ((p ushr 16) and 0xFF) / 255f
                    val g = ((p ushr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    input.putFloat(r)
                    input.putFloat(g)
                    input.putFloat(b)
                }
            }
            input.rewind()

            val output = Array(1) { Array(outChannels) { FloatArray(outCount) } }

            val t0 = SystemClock.elapsedRealtime()
            interpreter.run(input, output)
            val t1 = SystemClock.elapsedRealtime()
            Log.d("TapeWear_Main", "YOLO inference took ${t1 - t0} ms")

            val chans = output[0]
            if (chans.size != 5) {
                resized.recycle()
                Log.w("TapeWear_YOLO", "Unexpected channels=${chans.size}, returning empty detections.")
                return emptyList()
            }

            val cxArr = chans[0]
            val cyArr = chans[1]
            val wArr = chans[2]
            val hArr = chans[3]
            val objArr = chans[4]

            val minConf = AuthConfig.YOLO_CONF_THRESHOLD
            val minAreaRatio = AuthConfig.YOLO_MIN_BOX_AREA_RATIO.coerceAtLeast(0f)
            val nmsIou = AuthConfig.YOLO_NMS_IOU_THRESHOLD.coerceIn(0f, 1f)
            val maxOut = AuthConfig.YOLO_MAX_DETECTIONS.coerceAtLeast(1)
            val normalizedCoords = isLikelyNormalizedCoords(cxArr, cyArr, wArr, hArr)
            val sx = full.width.toFloat() / inSize.toFloat()
            val sy = full.height.toFloat() / inSize.toFloat()
            val found = ArrayList<Detection>(8)
            val modelAreaDen = inSize.toFloat() * inSize.toFloat()

            for (i in 0 until outCount) {
                val score = objArr[i]
                if (score < minConf) continue

                val boxModel = decodeRawBox(i, cxArr, cyArr, wArr, hArr, normalizedCoords)
                val areaRatio = (boxModel.width() * boxModel.height()) / modelAreaDen
                if (areaRatio < minAreaRatio) continue
                val mapped = RectF(
                    boxModel.left * sx,
                    boxModel.top * sy,
                    boxModel.right * sx,
                    boxModel.bottom * sy
                )
                found.add(Detection(mapped, score, 0))
            }

            if (found.isEmpty()) {
                resized.recycle()
                Log.d("TapeWear_YOLO", "No object above $minConf, returning empty list.")
                return emptyList()
            }

            val top = nonMaxSuppression(found, iouThreshold = nmsIou, maxDetections = maxOut)
            if (top.isEmpty()) {
                resized.recycle()
                Log.d("TapeWear_YOLO", "All detections removed by NMS/min-area filtering.")
                return emptyList()
            }
            val best = top.first()

            resized.recycle()

            Log.d(
                "TapeWear_YOLO",
                "YOLO detections raw=${found.size} kept=${top.size} best=${best.box} score=${"%.3f".format(best.score)}"
            )
            return top
        }

        private fun isLikelyNormalizedCoords(
            cxArr: FloatArray,
            cyArr: FloatArray,
            wArr: FloatArray,
            hArr: FloatArray
        ): Boolean {
            var maxAbs = 0f
            val stride = (outCount / 64).coerceAtLeast(1)
            var i = 0
            while (i < outCount) {
                maxAbs = maxOf(maxAbs, kotlin.math.abs(cxArr[i]))
                maxAbs = maxOf(maxAbs, kotlin.math.abs(cyArr[i]))
                maxAbs = maxOf(maxAbs, kotlin.math.abs(wArr[i]))
                maxAbs = maxOf(maxAbs, kotlin.math.abs(hArr[i]))
                i += stride
            }
            return maxAbs <= 2f
        }

        private fun decodeRawBox(
            idx: Int,
            cxArr: FloatArray,
            cyArr: FloatArray,
            wArr: FloatArray,
            hArr: FloatArray,
            normalized: Boolean
        ): RectF {
            val cxPx = if (normalized) cxArr[idx] * inSize else cxArr[idx]
            val cyPx = if (normalized) cyArr[idx] * inSize else cyArr[idx]
            val wPx = (if (normalized) wArr[idx] * inSize else wArr[idx]).coerceAtLeast(1f)
            val hPx = (if (normalized) hArr[idx] * inSize else hArr[idx]).coerceAtLeast(1f)

            val left = (cxPx - wPx / 2f).coerceIn(0f, inSize.toFloat() - 1f)
            val top = (cyPx - hPx / 2f).coerceIn(0f, inSize.toFloat() - 1f)
            val right = (cxPx + wPx / 2f).coerceIn(left + 1f, inSize.toFloat())
            val bottom = (cyPx + hPx / 2f).coerceIn(top + 1f, inSize.toFloat())

            return RectF(left, top, right, bottom)
        }

        private fun nonMaxSuppression(
            detections: List<Detection>,
            iouThreshold: Float,
            maxDetections: Int
        ): List<Detection> {
            if (detections.isEmpty() || maxDetections <= 0) return emptyList()

            val sorted = detections.sortedByDescending { it.score }
            val selected = ArrayList<Detection>(maxDetections)

            for (candidate in sorted) {
                var shouldKeep = true
                for (picked in selected) {
                    if (BoxOps.iou(candidate.box, picked.box) > iouThreshold) {
                        shouldKeep = false
                        break
                    }
                }
                if (!shouldKeep) continue
                selected.add(candidate)
                if (selected.size >= maxDetections) break
            }
            return selected
        }

        override fun close() {
            interpreter.close()
        }
    }

    /**
     * TAW-Loc detector for the learned support-mask localizer.
     *
     * The model predicts presence, a quadrilateral, and a dense support mask.
     * For the current app pipeline we use the mask's largest connected
     * component as an axis-aligned ROI, preserving the existing TAW-Emb crop
     * contract while still testing our localizer in the field.
     */
    class TFLiteTAWLocDetector(
        context: Context,
        assetName: String = "tawloc_v1_maskrect_float32.tflite",
        numThreads: Int = 2
    ) : Detector {

        private val interpreter: Interpreter
        private val inSize: Int
        private val outputElementCounts: IntArray
        private val presenceOutputIndex: Int
        private val quadOutputIndex: Int
        private val maskOutputIndex: Int

        companion object {
            private const val TAG = "TapeWear_TAWLoc"
            private const val LETTERBOX_FILL = 114

            private fun mapAsset(context: Context, assetName: String): ByteBuffer {
                val afd = context.assets.openFd(assetName)
                val fis = FileInputStream(afd.fileDescriptor)
                val channel = fis.channel
                val mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.startOffset,
                    afd.length
                )
                fis.close()
                afd.close()
                return mapped
            }
        }

        private data class LetterboxMeta(
            val scale: Float,
            val padLeft: Int,
            val padTop: Int
        )

        private data class MaskComponent(
            val minX: Int,
            val minY: Int,
            val maxX: Int,
            val maxY: Int,
            val area: Int,
            val pixelIndices: IntArray
        ) {
            fun toRectF(): RectF = RectF(
                minX.toFloat(),
                minY.toFloat(),
                (maxX + 1).toFloat(),
                (maxY + 1).toFloat()
            )

            fun toAxisQuad(): List<PointF> = listOf(
                PointF(minX.toFloat(), minY.toFloat()),
                PointF((maxX + 1).toFloat(), minY.toFloat()),
                PointF((maxX + 1).toFloat(), (maxY + 1).toFloat()),
                PointF(minX.toFloat(), (maxY + 1).toFloat())
            )
        }

        init {
            val t0 = SystemClock.elapsedRealtime()
            val opts = Interpreter.Options().apply {
                setNumThreads(numThreads)
                setUseNNAPI(false)
            }
            interpreter = Interpreter(mapAsset(context, assetName), opts)

            val inShape = interpreter.getInputTensor(0).shape()
            require(inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3 && inShape[1] == inShape[2]) {
                "TAW-Loc input must be [1,H,W,3], got ${inShape.contentToString()}"
            }
            inSize = inShape[1]

            val outputCount = interpreter.outputTensorCount
            require(outputCount >= 3) {
                "TAW-Loc must expose presence, quad, and mask outputs; got $outputCount outputs"
            }
            outputElementCounts = IntArray(outputCount) { outputIndex ->
                interpreter.getOutputTensor(outputIndex).shape().fold(1) { acc, dim -> acc * dim }
            }
            presenceOutputIndex = outputElementCounts.indexOfFirst { it == 1 }
            quadOutputIndex = outputElementCounts.indexOfFirst { it == 8 }
            maskOutputIndex = outputElementCounts.indexOfFirst { it == inSize * inSize }

            require(presenceOutputIndex >= 0 && quadOutputIndex >= 0 && maskOutputIndex >= 0) {
                "Unexpected TAW-Loc output element counts=${outputElementCounts.contentToString()}"
            }

            val t1 = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "TAW-Loc ready: inSize=$inSize outputs=${outputElementCounts.contentToString()} in ${t1 - t0} ms"
            )
        }

        @Synchronized
        override fun detect(full: Bitmap): List<Detection> {
            val (input, meta) = buildLetterboxedInput(full)
            val outputs = HashMap<Int, Any>(outputElementCounts.size)
            for (i in outputElementCounts.indices) {
                outputs[i] = ByteBuffer
                    .allocateDirect(4 * outputElementCounts[i])
                    .order(ByteOrder.nativeOrder())
            }

            val t0 = SystemClock.elapsedRealtime()
            interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)
            val t1 = SystemClock.elapsedRealtime()
            Log.d("TapeWear_Main", "TAW-Loc inference took ${t1 - t0} ms")

            val presence = readScalar(outputs[presenceOutputIndex] as ByteBuffer)
            val score = sigmoid(presence)
            val minConf = AuthConfig.TAWLOC_CONF_THRESHOLD
            if (score < minConf) {
                Log.d(TAG, "No support above $minConf, score=${"%.3f".format(score)}")
                return emptyList()
            }

            val component = largestMaskComponent(outputs[maskOutputIndex] as ByteBuffer)
            val maskQuad = component?.toMinAreaQuad() ?: component?.toAxisQuad()
            val modelQuad = maskQuad ?: quadFallbackQuad(outputs[quadOutputIndex] as ByteBuffer)
            if (modelQuad == null) {
                Log.d(TAG, "Presence accepted but mask/quad produced no usable box.")
                return emptyList()
            }

            val mappedQuad = mapModelQuadToFull(modelQuad, full.width, full.height, meta)
            if (mappedQuad == null) {
                Log.d(TAG, "Recovered support was outside the source image after unletterboxing.")
                return emptyList()
            }
            val mapped = boundsForQuad(mappedQuad)

            Log.d(
                TAG,
                "TAW-Loc detection box=$mapped score=${"%.3f".format(score)} source=${if (maskQuad != null) "mask-minrect" else "quad-head"}"
            )
            return listOf(Detection(mapped, score, 0, supportQuad = mappedQuad))
        }

        private fun buildLetterboxedInput(full: Bitmap): Pair<ByteBuffer, LetterboxMeta> {
            val scale = minOf(
                inSize.toFloat() / full.width.toFloat(),
                inSize.toFloat() / full.height.toFloat()
            )
            val resizedW = (full.width * scale).roundToInt().coerceAtLeast(1)
            val resizedH = (full.height * scale).roundToInt().coerceAtLeast(1)
            val padLeft = (inSize - resizedW) / 2
            val padTop = (inSize - resizedH) / 2

            val canvasBitmap = Bitmap.createBitmap(inSize, inSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.rgb(LETTERBOX_FILL, LETTERBOX_FILL, LETTERBOX_FILL))

            val resized = Bitmap.createScaledBitmap(full, resizedW, resizedH, true)
            canvas.drawBitmap(resized, padLeft.toFloat(), padTop.toFloat(), null)
            if (resized !== full) {
                resized.recycle()
            }

            val input = ByteBuffer
                .allocateDirect(4 * inSize * inSize * 3)
                .order(ByteOrder.nativeOrder())
            val row = IntArray(inSize)
            for (y in 0 until inSize) {
                canvasBitmap.getPixels(row, 0, inSize, 0, y, inSize, 1)
                for (x in 0 until inSize) {
                    val p = row[x]
                    input.putFloat(((p ushr 16) and 0xFF) / 255f)
                    input.putFloat(((p ushr 8) and 0xFF) / 255f)
                    input.putFloat((p and 0xFF) / 255f)
                }
            }
            canvasBitmap.recycle()
            input.rewind()
            return input to LetterboxMeta(scale = scale, padLeft = padLeft, padTop = padTop)
        }

        private fun readScalar(buffer: ByteBuffer): Float {
            buffer.rewind()
            return buffer.getFloat()
        }

        private fun largestMaskComponent(buffer: ByteBuffer): MaskComponent? {
            val total = inSize * inSize
            val active = BooleanArray(total)
            var activeCount = 0

            buffer.rewind()
            for (i in 0 until total) {
                if (sigmoid(buffer.getFloat()) >= AuthConfig.TAWLOC_MASK_THRESHOLD) {
                    active[i] = true
                    activeCount++
                }
            }
            if (activeCount <= 0) return null

            val minArea = (total * AuthConfig.TAWLOC_MIN_MASK_AREA_RATIO)
                .roundToInt()
                .coerceAtLeast(4)
            val visited = BooleanArray(total)
            val stack = IntArray(activeCount)
            val componentPixels = IntArray(activeCount)
            var best: MaskComponent? = null

            fun push(idx: Int, stackSize: Int): Int {
                if (!active[idx] || visited[idx]) return stackSize
                visited[idx] = true
                stack[stackSize] = idx
                return stackSize + 1
            }

            for (start in 0 until total) {
                if (!active[start] || visited[start]) continue
                visited[start] = true
                var stackSize = 1
                stack[0] = start

                var area = 0
                var minX = inSize
                var minY = inSize
                var maxX = 0
                var maxY = 0

                while (stackSize > 0) {
                    val idx = stack[--stackSize]
                    val x = idx % inSize
                    val y = idx / inSize
                    componentPixels[area] = idx
                    area++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y

                    if (x > 0) stackSize = push(idx - 1, stackSize)
                    if (x < inSize - 1) stackSize = push(idx + 1, stackSize)
                    if (y > 0) stackSize = push(idx - inSize, stackSize)
                    if (y < inSize - 1) stackSize = push(idx + inSize, stackSize)
                }

                if (area >= minArea && (best == null || area > best!!.area)) {
                    best = MaskComponent(
                        minX = minX,
                        minY = minY,
                        maxX = maxX,
                        maxY = maxY,
                        area = area,
                        pixelIndices = componentPixels.copyOf(area)
                    )
                }
            }

            return best
        }

        private fun MaskComponent.toMinAreaQuad(): List<PointF>? {
            if (pixelIndices.size < 4) return null

            val points = ArrayList<PointF>(pixelIndices.size)
            for (idx in pixelIndices) {
                points.add(PointF((idx % inSize) + 0.5f, (idx / inSize) + 0.5f))
            }

            val hull = convexHull(points)
            if (hull.size < 3) return null

            var bestArea = Float.POSITIVE_INFINITY
            var bestUx = 1f
            var bestUy = 0f
            var bestMinU = 0f
            var bestMaxU = 0f
            var bestMinV = 0f
            var bestMaxV = 0f

            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (len <= 1e-3f) continue

                val ux = dx / len
                val uy = dy / len
                val vx = -uy
                val vy = ux
                var minU = Float.POSITIVE_INFINITY
                var maxU = Float.NEGATIVE_INFINITY
                var minV = Float.POSITIVE_INFINITY
                var maxV = Float.NEGATIVE_INFINITY

                for (p in hull) {
                    val u = p.x * ux + p.y * uy
                    val v = p.x * vx + p.y * vy
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }

                val area = (maxU - minU) * (maxV - minV)
                if (area < bestArea) {
                    bestArea = area
                    bestUx = ux
                    bestUy = uy
                    bestMinU = minU
                    bestMaxU = maxU
                    bestMinV = minV
                    bestMaxV = maxV
                }
            }

            if (!bestArea.isFinite()) return null
            val vx = -bestUy
            val vy = bestUx
            val corners = listOf(
                fromProjection(bestMinU, bestMinV, bestUx, bestUy, vx, vy),
                fromProjection(bestMaxU, bestMinV, bestUx, bestUy, vx, vy),
                fromProjection(bestMaxU, bestMaxV, bestUx, bestUy, vx, vy),
                fromProjection(bestMinU, bestMaxV, bestUx, bestUy, vx, vy)
            )
            return orderQuadForWarp(corners)
        }

        private fun fromProjection(
            u: Float,
            v: Float,
            ux: Float,
            uy: Float,
            vx: Float,
            vy: Float
        ): PointF = PointF(
            (u * ux + v * vx).coerceIn(0f, inSize.toFloat()),
            (u * uy + v * vy).coerceIn(0f, inSize.toFloat())
        )

        private fun convexHull(points: List<PointF>): List<PointF> {
            if (points.size <= 1) return points
            val sorted = points.sortedWith(compareBy<PointF> { it.x }.thenBy { it.y })

            fun cross(o: PointF, a: PointF, b: PointF): Float =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = ArrayList<PointF>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                    lower.removeAt(lower.size - 1)
                }
                lower.add(p)
            }

            val upper = ArrayList<PointF>()
            for (i in sorted.indices.reversed()) {
                val p = sorted[i]
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                    upper.removeAt(upper.size - 1)
                }
                upper.add(p)
            }

            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        private fun quadFallbackQuad(buffer: ByteBuffer): List<PointF>? {
            buffer.rewind()
            val points = ArrayList<PointF>(4)

            repeat(4) {
                val x = (buffer.getFloat() * inSize).coerceIn(0f, inSize.toFloat())
                val y = (buffer.getFloat() * inSize).coerceIn(0f, inSize.toFloat())
                points.add(PointF(x, y))
            }

            return if (points.size == 4) orderQuadForWarp(points) else null
        }

        private fun mapModelQuadToFull(
            modelQuad: List<PointF>,
            fullW: Int,
            fullH: Int,
            meta: LetterboxMeta
        ): List<PointF>? {
            if (modelQuad.size != 4) return null
            val mapped = modelQuad.map { p ->
                PointF(
                    ((p.x - meta.padLeft) / meta.scale).coerceIn(0f, fullW.toFloat()),
                    ((p.y - meta.padTop) / meta.scale).coerceIn(0f, fullH.toFloat())
                )
            }
            val bounds = boundsForQuad(mapped)
            if (bounds.width() <= 1f || bounds.height() <= 1f) return null
            return orderQuadForWarp(mapped)
        }

        private fun sigmoid(x: Float): Float =
            (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

        override fun close() {
            interpreter.close()
        }
    }

    var detector: Detector? = null

    fun configuredDetectorName(): String =
        if (AuthConfig.LOCATOR_BACKEND == AuthConfig.LOCATOR_BACKEND_TAW_LOC) "taw_loc" else "yolo"

    fun configuredDetectorLabel(): String =
        if (configuredDetectorName() == "taw_loc") "TAW-Loc" else "YOLO"

    fun activeDetectorName(): String = when (detector) {
        is TFLiteYoloDetector -> "yolo"
        is TFLiteTAWLocDetector -> "taw_loc"
        is OverlayDetector -> "overlay"
        null -> "none"
        else -> "custom"
    }

    fun activeDetectorLabel(): String = when (activeDetectorName()) {
        "taw_loc" -> "TAW-Loc"
        "yolo" -> "YOLO"
        "overlay" -> "Overlay"
        "none" -> configuredDetectorLabel()
        else -> "Detector"
    }

    fun detectorConfidenceThreshold(): Float =
        if (configuredDetectorName() == "taw_loc") AuthConfig.TAWLOC_CONF_THRESHOLD else AuthConfig.YOLO_CONF_THRESHOLD

    fun isDetectorReady(): Boolean = detector is TFLiteYoloDetector || detector is TFLiteTAWLocDetector

    fun detectorMatchesConfig(): Boolean = activeDetectorName() == configuredDetectorName()

    fun createConfiguredDetector(
        context: Context,
        numThreads: Int = 2
    ): Detector =
        if (configuredDetectorName() == "taw_loc") {
            TFLiteTAWLocDetector(
                context = context,
                assetName = "tawloc_v1_maskrect_float32.tflite",
                numThreads = numThreads
            )
        } else {
            TFLiteYoloDetector(
                context = context,
                assetName = "best_float32.tflite",
                numThreads = numThreads
            )
        }

    @Synchronized
    fun ensureConfiguredDetector(
        context: Context,
        numThreads: Int = 2
    ) {
        if (isDetectorReady() && detectorMatchesConfig()) return
        resetDetector()
        detector = createConfiguredDetector(context, numThreads)
    }

    @Synchronized
    fun resetDetector() {
        try { detector?.close() } catch (_: Exception) {}
        detector = null
    }

    fun embedderInputBitmap(src: Bitmap, detection: Detection): Pair<Bitmap, Rect>? {
        val quad = detection.supportQuad
        if (AuthConfig.TAWLOC_USE_CANONICAL_WARP && quad != null && quad.size == 4) {
            val canonical = warpQuadToSquare(
                src = src,
                quad = quad,
                outputSize = AuthConfig.TAWLOC_CANONICAL_WARP_SIZE
            )
            if (canonical != null) {
                return canonical to Rect(0, 0, canonical.width, canonical.height)
            }
        }

        val roi = BoxOps.clamp(detection.box, src.width, src.height)
        return src to roi
    }

    fun warpQuadToSquare(
        src: Bitmap,
        quad: List<PointF>,
        outputSize: Int
    ): Bitmap? {
        if (quad.size != 4 || outputSize <= 1) return null
        val ordered = orderQuadForWarp(quad)
        val srcPts = floatArrayOf(
            ordered[0].x, ordered[0].y,
            ordered[1].x, ordered[1].y,
            ordered[2].x, ordered[2].y,
            ordered[3].x, ordered[3].y
        )
        val maxDst = (outputSize - 1).toFloat()
        val dstPts = floatArrayOf(
            0f, 0f,
            maxDst, 0f,
            maxDst, maxDst,
            0f, maxDst
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) return null

        val out = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
        canvas.drawBitmap(src, matrix, paint)
        return out
    }

    fun orderQuadForWarp(points: List<PointF>): List<PointF> {
        if (points.size != 4) return points
        val copy = points.map { PointF(it.x, it.y) }
        val tl = copy.minByOrNull { it.x + it.y }
        val br = copy.maxByOrNull { it.x + it.y }
        val tr = copy.minByOrNull { it.y - it.x }
        val bl = copy.maxByOrNull { it.y - it.x }
        val ordered = listOfNotNull(tl, tr, br, bl)

        val uniqueCount = ordered
            .map { "${it.x.roundToInt()}:${it.y.roundToInt()}" }
            .toSet()
            .size
        if (ordered.size == 4 && uniqueCount == 4) {
            return ordered
        }

        val sorted = copy.sortedWith(compareBy<PointF> { it.y }.thenBy { it.x })
        val top = sorted.take(2).sortedBy { it.x }
        val bottom = sorted.takeLast(2).sortedBy { it.x }
        return listOf(top[0], top[1], bottom[1], bottom[0])
    }

    fun boundsForQuad(points: List<PointF>): RectF {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    // =====================================================================
    //  Embedder interface + implementation
    // =====================================================================

    interface Embedder : Closeable {
        fun embed(src: Bitmap, roi: Rect): FloatArray
        override fun close() {}
    }

    /**
     * Hybrid patch + gradient + HSV color histogram embedder.
     * See [AuthConfig] for all tunable parameters.
     */
    object HybridPatchEmbedder : Embedder {

        private const val SIZE = AuthConfig.EMBED_CROP_SIZE
        private const val PATCH_SIZE = AuthConfig.EMBED_PATCH_SIZE
        private const val CELLS = AuthConfig.EMBED_CELLS
        private const val ANGLE_BINS = AuthConfig.EMBED_ANGLE_BINS
        private const val COLOR_BINS = AuthConfig.EMBED_COLOR_BINS

        private const val PIX_DIM = PATCH_SIZE * PATCH_SIZE
        private const val GRAD_DIM = CELLS * CELLS * ANGLE_BINS
        private const val COLOR_DIM = COLOR_BINS * 3
        private const val DIM = PIX_DIM + GRAD_DIM + COLOR_DIM

        private const val CENTER_CROP_RATIO = AuthConfig.EMBED_CENTER_CROP_RATIO

        private const val PIX_WEIGHT = AuthConfig.EMBED_PIX_WEIGHT
        private const val GRAD_WEIGHT = AuthConfig.EMBED_GRAD_WEIGHT
        private const val COLOR_WEIGHT = AuthConfig.EMBED_COLOR_WEIGHT

        override fun embed(src: Bitmap, roi: Rect): FloatArray {
            val left0 = roi.left.coerceIn(0, src.width - 1)
            val top0 = roi.top.coerceIn(0, src.height - 1)
            val right0 = roi.right.coerceIn(left0 + 1, src.width)
            val bottom0 = roi.bottom.coerceIn(top0 + 1, src.height)

            val w0 = right0 - left0
            val h0 = bottom0 - top0

            val cx = left0 + w0 / 2f
            val cy = top0 + h0 / 2f
            val halfW = (w0 * CENTER_CROP_RATIO * 0.5f)
            val halfH = (h0 * CENTER_CROP_RATIO * 0.5f)

            var left = (cx - halfW).roundToInt()
            var top = (cy - halfH).roundToInt()
            var right = (cx + halfW).roundToInt()
            var bottom = (cy + halfH).roundToInt()

            left = left.coerceIn(0, src.width - 2)
            top = top.coerceIn(0, src.height - 2)
            right = right.coerceIn(left + 1, src.width)
            bottom = bottom.coerceIn(top + 1, src.height)

            val w = right - left
            val h = bottom - top

            val crop = Bitmap.createBitmap(src, left, top, w, h)
            val resized = Bitmap.createScaledBitmap(crop, SIZE, SIZE, true)
            crop.recycle()

            val gray = FloatArray(SIZE * SIZE)
            val row = IntArray(SIZE)

            val histH = FloatArray(COLOR_BINS)
            val histS = FloatArray(COLOR_BINS)
            val histV = FloatArray(COLOR_BINS)

            val hsv = FloatArray(3)

            var sum = 0.0
            var sum2 = 0.0
            var idx = 0

            for (y in 0 until SIZE) {
                resized.getPixels(row, 0, SIZE, 0, y, SIZE, 1)
                for (x in 0 until SIZE) {
                    val p = row[x]
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF

                    val vGray = (r * 0.299 + g * 0.587 + b * 0.114) / 255.0
                    gray[idx] = vGray.toFloat()
                    sum += vGray
                    sum2 += vGray * vGray
                    idx++

                    Color.colorToHSV(p, hsv)
                    val hDeg = hsv[0]
                    val sVal = hsv[1]
                    val vVal = hsv[2]

                    val hBin = ((hDeg / 360f) * COLOR_BINS).toInt().coerceIn(0, COLOR_BINS - 1)
                    val sBin = (sVal * COLOR_BINS).toInt().coerceIn(0, COLOR_BINS - 1)
                    val vBin = (vVal * COLOR_BINS).toInt().coerceIn(0, COLOR_BINS - 1)

                    histH[hBin] += 1f
                    histS[sBin] += 1f
                    histV[vBin] += 1f
                }
            }

            val nPix = SIZE * SIZE
            val mean = sum / nPix
            val varc = (sum2 / nPix) - mean * mean
            val std = sqrt(varc.coerceAtLeast(1e-6))

            for (i in 0 until nPix) {
                gray[i] = ((gray[i] - mean) / std).toFloat()
            }

            // Pixel block (downsample)
            val featPix = FloatArray(PIX_DIM)
            val step = SIZE / PATCH_SIZE
            var k = 0
            var yy = 0
            while (yy < SIZE && k < PIX_DIM) {
                var xx = 0
                while (xx < SIZE && k < PIX_DIM) {
                    featPix[k] = gray[yy * SIZE + xx]
                    k++
                    xx += step
                }
                yy += step
            }

            // Gradient block
            val featGrad = FloatArray(GRAD_DIM)
            val cellW = SIZE / CELLS
            val cellH = SIZE / CELLS

            for (y in 1 until SIZE - 1) {
                for (x in 1 until SIZE - 1) {
                    val idx0 = y * SIZE + x
                    val gx = gray[idx0 + 1] - gray[idx0 - 1]
                    val gy = gray[idx0 + SIZE] - gray[idx0 - SIZE]
                    val mag = sqrt((gx * gx + gy * gy).toDouble())
                    if (mag <= 0.0) continue

                    var angle = atan2(gy.toDouble(), gx.toDouble())
                    if (angle < 0.0) angle += PI
                    val bin = ((angle / PI) * ANGLE_BINS).toInt().coerceIn(0, ANGLE_BINS - 1)

                    var cxCell = x / cellW
                    var cyCell = y / cellH
                    if (cxCell >= CELLS) cxCell = CELLS - 1
                    if (cyCell >= CELLS) cyCell = CELLS - 1

                    val cellIdx = cyCell * CELLS + cxCell
                    val featIdx = cellIdx * ANGLE_BINS + bin
                    featGrad[featIdx] += mag.toFloat()
                }
            }

            // Color hist block: L1 normalize
            fun l1norm(h: FloatArray) {
                var s = 0.0
                for (v in h) s += kotlin.math.abs(v.toDouble())
                val d = if (s <= 0.0) 1.0 else s
                for (i in h.indices) h[i] = (h[i] / d).toFloat()
            }
            l1norm(histH)
            l1norm(histS)
            l1norm(histV)

            for (i in histH.indices) histH[i] *= COLOR_WEIGHT
            for (i in histS.indices) histS[i] *= COLOR_WEIGHT
            for (i in histV.indices) histV[i] *= COLOR_WEIGHT

            resized.recycle()

            val feat = FloatArray(DIM)
            var offset = 0

            for (i in featPix.indices) featPix[i] *= PIX_WEIGHT
            for (i in featGrad.indices) featGrad[i] *= GRAD_WEIGHT

            System.arraycopy(featPix, 0, feat, offset, PIX_DIM)
            offset += PIX_DIM
            System.arraycopy(featGrad, 0, feat, offset, GRAD_DIM)
            offset += GRAD_DIM
            System.arraycopy(histH, 0, feat, offset, COLOR_BINS)
            offset += COLOR_BINS
            System.arraycopy(histS, 0, feat, offset, COLOR_BINS)
            offset += COLOR_BINS
            System.arraycopy(histV, 0, feat, offset, COLOR_BINS)

            return MathUtils.l2norm(feat)
        }
    }

    var cvEmbedder: Embedder = HybridPatchEmbedder
    var mlEmbedder: Embedder? = null

    val embedder: Embedder
        get() = if (AuthConfig.USE_ML_EMBEDDER && mlEmbedder != null) mlEmbedder!! else cvEmbedder

    // =====================================================================
    //  Facade — delegates to EnrollmentStore and ScoringEngine
    // =====================================================================

    // Backward-compatible factory functions (typealias can't live inside an object)
    fun Verdict(similarity: Float, isMatch: Boolean) = ScoringEngine.Verdict(similarity, isMatch)
    fun ScoredResult(
        verdict: ScoringEngine.Verdict,
        scoredFrames: Int = 0,
        detectMs: Long,
        embedMs: Long,
        cosineMs: Long
    ) = ScoringEngine.ScoredResult(verdict, scoredFrames, detectMs, embedMs, cosineMs)

    fun setActiveSlot(slot: Int) { EnrollmentStore.activeSlot = slot }
    fun getActiveSlot(): Int = EnrollmentStore.activeSlot

    fun hasModel(ctx: Context, slot: Int = getActiveSlot()): Boolean =
        EnrollmentStore.hasModel(ctx, slot)

    fun loadEnroll(ctx: Context, slot: Int = getActiveSlot()) =
        EnrollmentStore.load(ctx, slot)

    var COSINE_THRESHOLD: Float
        get() = AuthConfig.MATCH_THRESHOLD
        set(_) {}   // kept for source compat; real value in AuthConfig

    fun scoreFromBitmaps(
        context: Context,
        frames: List<Bitmap>,
        take: Int = 3,
        slot: Int = getActiveSlot()
    ): ScoringEngine.ScoredResult {
        val det = detector ?: run {
            Log.e("TapeWear_Score", "scoreFromBitmaps: detector is null")
            return ScoredResult(Verdict(-1f, false), scoredFrames = 0, detectMs = 0, embedMs = 0, cosineMs = 0)
        }
        return ScoringEngine.scoreFromBitmaps(context, det, embedder, frames, take, slot)
    }

    fun enrollFromBitmaps(
        context: Context,
        frames: List<Bitmap>,
        maxEmbeds: Int = AuthConfig.MAX_ENROLL_EMBEDS,
        slot: Int = getActiveSlot()
    ): Int {
        val det = detector ?: run {
            Log.e("TapeWear_Model", "enrollFromBitmaps: detector is null")
            return 0
        }
        return ScoringEngine.enrollFromBitmaps(context, det, embedder, frames, maxEmbeds, slot)
    }

    fun score(emb: FloatArray, ctx: Context, slot: Int = getActiveSlot()): ScoringEngine.Verdict =
        ScoringEngine.score(emb, ctx, slot)

    fun close() {
        resetDetector()
        try { mlEmbedder?.close() } catch (_: Exception) {}
        mlEmbedder = null
    }
}
