package com.example.tapewear

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.TextureView
import androidx.core.graphics.scale
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


object ModelManager {

    // ----------------- Detector plug-in -----------------

    interface Detector : Closeable {
        fun detect(full: Bitmap): List<Detection>
        override fun close() {}
    }

    data class Detection(val box: RectF, val score: Float, val classIndex: Int)

    /**
     * Simple detector that just maps the overlay box to bitmap coordinates.
     * Used as a fallback when YOLO is not available.
     */
    class OverlayDetector(
        private val overlay: OverlayView?,
        private val texture: TextureView?
    ) : Detector {
        override fun detect(full: Bitmap): List<Detection> {
            if (overlay == null || texture == null || texture.width <= 0 || texture.height <= 0) {
                Log.w("TapeWear_YOLO", "OverlayDetector: fallback to full-frame box")
                return listOf(
                    Detection(
                        RectF(0f, 0f, full.width.toFloat(), full.height.toFloat()),
                        1f,
                        0
                    )
                )
            }

            val vr = overlay.getFramingBox()
            val vw = max(1, texture.width)
            val vh = max(1, texture.height)

            val l = vr.left * full.width / vw
            val t = vr.top * full.height / vh
            val r = vr.right * full.width / vw
            val b = vr.bottom * full.height / vh

            val mapped = RectF(
                l.coerceIn(0f, full.width - 1f),
                t.coerceIn(0f, full.height - 1f),
                r.coerceIn(1f, full.width.toFloat()),
                b.coerceIn(1f, full.height.toFloat())
            )

            Log.d("TapeWear_YOLO", "OverlayDetector box=$mapped")
            return listOf(Detection(mapped, 1f, 0))
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
                Log.w("TapeWear_YOLO", "Unexpected channels=${chans.size}, falling back to full frame.")
                return listOf(
                    Detection(
                        RectF(0f, 0f, full.width.toFloat(), full.height.toFloat()),
                        1f,
                        0
                    )
                )
            }

            val cxArr = chans[0]
            val cyArr = chans[1]
            val wArr = chans[2]
            val hArr = chans[3]
            val objArr = chans[4]

            val minConf = 0.45f
            var bestIdx = -1
            var bestScore = 0f
            for (i in 0 until outCount) {
                val s = objArr[i]
                if (s >= minConf && s > bestScore) {
                    bestScore = s
                    bestIdx = i
                }
            }

            if (bestIdx < 0) {
                resized.recycle()
                Log.d("TapeWear_YOLO", "No object above $minConf, returning empty list.")
                return emptyList()
            }

            val boxModel = decodeBox(bestIdx, cxArr, cyArr, wArr, hArr)

            val sx = full.width.toFloat() / inSize.toFloat()
            val sy = full.height.toFloat() / inSize.toFloat()

            val mapped = RectF(
                boxModel.left * sx,
                boxModel.top * sy,
                boxModel.right * sx,
                boxModel.bottom * sy
            )

            resized.recycle()

            Log.d("TapeWear_YOLO", "YOLO box=$mapped score=$bestScore")
            return listOf(Detection(mapped, bestScore, 0))
        }

        private fun decodeBox(
            idx: Int,
            cxArr: FloatArray,
            cyArr: FloatArray,
            wArr: FloatArray,
            hArr: FloatArray
        ): RectF {
            val n80 = 80 * 80
            val n40 = 40 * 40

            val (g, offset) = when {
                idx < n80 -> 80 to 0
                idx < n80 + n40 -> 40 to n80
                else -> 20 to (n80 + n40)
            }

            val cell = idx - offset
            val cy = cell / g
            val cx = cell % g

            val gridCxNorm = (cx + 0.5f) / g.toFloat()
            val gridCyNorm = (cy + 0.5f) / g.toFloat()

            val netCxNorm = cxArr[idx].coerceIn(0f, 1f)
            val netCyNorm = cyArr[idx].coerceIn(0f, 1f)
            val centerX = (0.4f * netCxNorm + 0.6f * gridCxNorm).coerceIn(0f, 1f)
            val centerY = (0.4f * netCyNorm + 0.6f * gridCyNorm).coerceIn(0f, 1f)

            val wNorm = wArr[idx].coerceIn(0.05f, 1f)
            val hNorm = hArr[idx].coerceIn(0.05f, 1f)

            val cxPx = centerX * inSize
            val cyPx = centerY * inSize
            val wPx = wNorm * inSize
            val hPx = hNorm * inSize

            val left = (cxPx - wPx / 2f).coerceIn(0f, inSize - 1f)
            val top = (cyPx - hPx / 2f).coerceIn(0f, inSize - 1f)
            val right = (cxPx + wPx / 2f).coerceIn(left + 1f, inSize.toFloat())
            val bottom = (cyPx + hPx / 2f).coerceIn(top + 1f, inSize.toFloat())

            return RectF(left, top, right, bottom)
        }

        override fun close() {
            interpreter.close()
        }
    }

    var detector: Detector? = null

    // ----------------- Persistence -----------------

    // bump version so we don't mix with old embeddings
    private const val MODELS_DIR = "models_v4"
    private const val LEGACY_ENROLL_FILE = "enroll.json"
    private const val KEY_VEC = "vec"
    private const val KEY_DIM = "dim"
    private const val KEY_COUNT = "count"

    @Volatile
    private var _activeSlot: Int = 1
    fun setActiveSlot(slot: Int) { _activeSlot = slot.coerceIn(1, 10) }
    fun getActiveSlot(): Int = _activeSlot

    data class Enroll(val mean: FloatArray, val count: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Enroll
            return count == other.count && mean.contentEquals(other.mean)
        }
        override fun hashCode(): Int = 31 * count + mean.contentHashCode()
    }

    // Used only by direct score(), not by scoreFromBitmaps
    var COSINE_THRESHOLD = 0.80f

    private fun modelsDir(ctx: Context) =
        File(ctx.filesDir, MODELS_DIR).apply { mkdirs() }

    private fun fileForSlot(ctx: Context, slot: Int): File =
        File(modelsDir(ctx), "pattern_%02d.json".format(slot))

    private fun saveEnroll(ctx: Context, mean: FloatArray, count: Int, slot: Int) {
        val bb = ByteBuffer.allocate(mean.size * 4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            asFloatBuffer().put(mean)
        }
        val vecStr = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        val js = JSONObject()
            .put(KEY_DIM, mean.size)
            .put(KEY_COUNT, count)
            .put(KEY_VEC, vecStr)
        fileForSlot(ctx, slot).writeText(js.toString())
        Log.i(
            "TapeWear_Model",
            "Saved enroll slot=$slot dim=${mean.size} count=$count to ${fileForSlot(ctx, slot).absolutePath}"
        )
    }

    fun loadEnroll(ctx: Context, slot: Int = getActiveSlot()): Enroll? {
        val f = when {
            fileForSlot(ctx, slot).exists() -> fileForSlot(ctx, slot)
            slot == 1 && File(ctx.filesDir, LEGACY_ENROLL_FILE).exists() ->
                File(ctx.filesDir, LEGACY_ENROLL_FILE)
            else -> return null
        }
        return try {
            val js = JSONObject(f.readText())
            val dim = js.getInt(KEY_DIM)
            val count = js.getInt(KEY_COUNT)
            val vecStr = js.getString(KEY_VEC)
            val bytes = Base64.decode(vecStr, Base64.NO_WRAP)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(dim)
            fb.get(arr)
            Log.i(
                "TapeWear_Model",
                "Loaded enroll slot=$slot dim=$dim count=$count from ${f.absolutePath}"
            )
            Enroll(arr, count)
        } catch (e: Exception) {
            Log.e("TapeWear_Model", "Failed to load enroll slot=$slot: ${e.message}", e)
            null
        }
    }

    fun hasModel(ctx: Context, slot: Int = getActiveSlot()): Boolean {
        val f = fileForSlot(ctx, slot)
        if (f.exists() && f.length() > 0) return true
        if (slot == 1) {
            val legacy = File(ctx.filesDir, LEGACY_ENROLL_FILE)
            if (legacy.exists() && legacy.length() > 0) return true


        }
        return false
    }

    // ----------------- High-level APIs -----------------

    fun enrollFromBitmaps(
        context: Context,
        frames: List<Bitmap>,
        maxEmbeds: Int = 32,
        slot: Int = getActiveSlot()
    ): Int {
        if (frames.isEmpty()) return 0
        val det = detector ?: run {
            Log.e("TapeWear_Model", "enrollFromBitmaps: detector is null")
            return 0
        }

        val embList = arrayListOf<FloatArray>()
        for ((idx, bmp) in frames.withIndex()) {
            val dets = det.detect(bmp)
            if (dets.isEmpty()) {
                Log.d("TapeWear_Model", "enrollFromBitmaps: no detection for frame#$idx")
                continue
            }
            val chosen = chooseRoi(dets)
            val roi = BoxOps.clamp(chosen.box, bmp.width, bmp.height)
            val roiRect = Rect(
                roi.left.toInt(),
                roi.top.toInt(),
                roi.right.toInt(),
                roi.bottom.toInt()
            )
            val t0 = SystemClock.elapsedRealtime()
            val emb = embedder.embed(bmp, roiRect)
            val t1 = SystemClock.elapsedRealtime()
            Log.d(
                "TapeWear_Model",
                "enroll frame#$idx roi=$roiRect embDim=${emb.size} time=${t1 - t0}ms"
            )
            embList.add(emb)
            if (embList.size >= maxEmbeds) break
        }

        if (embList.isEmpty()) {
            Log.w("TapeWear_Model", "enrollFromBitmaps: no embeddings collected")
            return 0
        }

        fitOrUpdate(embList, context, slot)
        return embList.size
    }

    fun scoreFromBitmaps(
        context: Context,
        frames: List<Bitmap>,
        take: Int = 3,
        slot: Int = getActiveSlot()
    ): Verdict {
        if (frames.isEmpty()) return Verdict(-1f, false)
        val det = detector ?: run {
            Log.e("TapeWear_Score", "scoreFromBitmaps: detector is null")
            return Verdict(-1f, false)
        }

        val enroll = loadEnroll(context, slot) ?: run {
            Log.e("TapeWear_Score", "scoreFromBitmaps: no enroll model for slot=$slot")
            return Verdict(-1f, false)
        }
        val proto = enroll.mean
        if (proto.isEmpty()) {
            Log.e("TapeWear_Score", "scoreFromBitmaps: enroll mean is empty")
            return Verdict(-1f, false)
        }

        val simsPerFrame = arrayListOf<Float>()
        val maxFrames = take.coerceAtLeast(1)
        var idx = 0

        for (bmp in frames) {
            if (idx >= maxFrames) break
            val dets = det.detect(bmp)
            if (dets.isEmpty()) {
                Log.d("TapeWear_Score", "scoreFromBitmaps: no detection for frame#$idx")
                idx++
                continue
            }
            val chosen = chooseRoi(dets)
            val roi = BoxOps.clamp(chosen.box, bmp.width, bmp.height)
            val roiRect = Rect(
                roi.left.toInt(),
                roi.top.toInt(),
                roi.right.toInt(),
                roi.bottom.toInt()
            )

            val t0 = SystemClock.elapsedRealtime()
            val emb = embedder.embed(bmp, roiRect)
            val t1 = SystemClock.elapsedRealtime()

            if (emb.size != proto.size) {
                Log.w(
                    "TapeWear_Score",
                    "frame#$idx embedding dim mismatch: emb=${emb.size} proto=${proto.size}"
                )
                idx++
                continue
            }
            val sim = cosine(emb, proto).coerceIn(-1f, 1f)

            // Just log the norm for sanity; not used in scoring
            val normEmb = l2norm(emb.copyOf()).let { v ->
                var ss = 0.0
                for (x in v) ss += x * x
                sqrt(ss).toFloat()
            }

            Log.d(
                "TapeWear_Score",
                "frame#$idx roi=$roiRect time=${t1 - t0}ms sim=%.4f norm=%.3f"
                    .format(sim, normEmb)
            )

            simsPerFrame.add(sim)
            idx++


        }

        if (simsPerFrame.isEmpty()) {
            Log.w("TapeWear_Score", "scoreFromBitmaps: no valid frames scored")
            return Verdict(-1f, false)
        }

        val sims = simsPerFrame.sorted()
        val n = sims.size
        val median = if (n % 2 == 1) sims[n / 2] else (sims[n / 2 - 1] + sims[n / 2]) / 2f
        val minSim = sims.first()
        val maxSim = sims.last()
        val spread = maxSim - minSim
        val mean = sims.sum() / n

        val matchThreshold = 0.80f
        val maxSpread = 0.20f
        val minFrames = 1

        val isStable = spread <= maxSpread
        val strong = median >= matchThreshold
        val enough = n >= minFrames

        val isMatch = isStable && strong && enough

        Log.d(
            "TapeWear_Score",
            "slot=$slot sims=$sims median=%.4f mean=%.4f spread=%.4f n=$n -> match=$isMatch"
                .format(median, mean, spread)
        )

        return Verdict(median, isMatch)
    }

    private fun chooseRoi(dets: List<Detection>): Detection =
        dets.maxByOrNull { it.score } ?: dets.first()

    // ----------------- Embedder -----------------

    interface Embedder : Closeable {
        fun embed(src: Bitmap, roi: Rect): FloatArray
        override fun close() {}
    }


    /**
     * Hybrid patch + gradient + HSV color histogram:
     *
     *  - Take YOLO ROI, shrink to 90% around its center (removes noisy borders).
     *  - Crop and resize to 64×64.
     *  - Grayscale + mean/std normalization.
     *  - Block 1: 32×32 downsampled intensities (1024-D).
     *  - Block 2: 4×4 cells, 8-bin gradient histograms (128-D).
     *  - Block 3: 16-bin per-channel HSV hist (H, S, V), L1-normalised and up-weighted (48-D).
     *  - Concatenate with block weights and L2-normalise → 1200-D.
     */
    object HybridPatchEmbedder : Embedder {

        private const val SIZE = 64
        private const val PATCH_SIZE = 32
        private const val CELLS = 4
        private const val ANGLE_BINS = 8
        private const val COLOR_BINS = 16

        private const val PIX_DIM = PATCH_SIZE * PATCH_SIZE           // 1024
        private const val GRAD_DIM = CELLS * CELLS * ANGLE_BINS       // 128
        private const val COLOR_DIM = COLOR_BINS * 3                  // 48
        private const val DIM = PIX_DIM + GRAD_DIM + COLOR_DIM        // 1200

        // 0.90 center crop inside YOLO ROI
        private const val CENTER_CROP_RATIO = 0.90f

        // Block weights before final L2 normalisation
        private const val PIX_WEIGHT = 0.35f
        private const val GRAD_WEIGHT = 2.0f
        private const val COLOR_WEIGHT = 3.0f

        override fun embed(src: Bitmap, roi: Rect): FloatArray {
            // Clamp ROI to image bounds
            val left0 = roi.left.coerceIn(0, src.width - 1)
            val top0 = roi.top.coerceIn(0, src.height - 1)
            val right0 = roi.right.coerceIn(left0 + 1, src.width)
            val bottom0 = roi.bottom.coerceIn(top0 + 1, src.height)

            val w0 = right0 - left0
            val h0 = bottom0 - top0

            // Center crop: take 90% around ROI center
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

                    // HSV for color features
                    Color.colorToHSV(p, hsv)
                    val hDeg = hsv[0]           // 0..360
                    val sVal = hsv[1]           // 0..1
                    val vVal = hsv[2]           // 0..1

                    val hBin = ((hDeg / 360f) * COLOR_BINS)
                        .toInt()
                        .coerceIn(0, COLOR_BINS - 1)
                    val sBin = (sVal * COLOR_BINS)
                        .toInt()
                        .coerceIn(0, COLOR_BINS - 1)
                    val vBin = (vVal * COLOR_BINS)
                        .toInt()
                        .coerceIn(0, COLOR_BINS - 1)

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
                val v = (gray[i] - mean).toFloat() / std.toFloat()
                gray[i] = v
            }

            // Pixel block 32×32 (downsample)
            val featPix = FloatArray(PIX_DIM)
            val step = SIZE / PATCH_SIZE       // 2
            var k = 0
            var yy = 0
            while (yy < SIZE && k < PIX_DIM) {
                var xx = 0
                while (xx < SIZE && k < PIX_DIM) {
                    val gi = yy * SIZE + xx
                    featPix[k] = gray[gi]
                    k++
                    xx += step
                }
                yy += step
            }

            // Gradient block: 4×4 cells, 8 bins
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
                    val bin = ((angle / PI) * ANGLE_BINS)
                        .toInt()
                        .coerceIn(0, ANGLE_BINS - 1)

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

            // Up-weight color block
            for (i in histH.indices) histH[i] *= COLOR_WEIGHT
            for (i in histS.indices) histS[i] *= COLOR_WEIGHT
            for (i in histV.indices) histV[i] *= COLOR_WEIGHT

            resized.recycle()

            val feat = FloatArray(DIM)
            var offset = 0

            // Apply block weights
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

            return l2norm(feat)
        }
    }

    var embedder: Embedder = HybridPatchEmbedder

    // ----------------- Fit and score helpers -----------------

    fun fitOrUpdate(embeds: List<FloatArray>, ctx: Context, slot: Int = getActiveSlot()) {
        if (embeds.isEmpty()) return
        val dim = embeds[0].size
        val mean = FloatArray(dim)
        for (e in embeds) {
            for (i in 0 until dim) {
                mean[i] += e[i]
            }
        }
        for (i in 0 until dim) mean[i] /= embeds.size.toFloat()
        saveEnroll(ctx, mean, embeds.size, slot)
    }

    data class Verdict(val similarity: Float, val isMatch: Boolean)

    fun score(emb: FloatArray, ctx: Context, slot: Int = getActiveSlot()): Verdict {
        val en = loadEnroll(ctx, slot) ?: return Verdict(-1f, false)
        val sim = cosine(emb, en.mean)
        return Verdict(sim, sim >= COSINE_THRESHOLD)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var num = 0.0
        var da = 0.0
        var db = 0.0
        var i = 0
        while (i < n) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            num += x * y
            da += x * x
            db += y * y
            i++
        }
        val denom = (sqrt(da) * sqrt(db)).let { if (it == 0.0) 1.0 else it }
        return (num / denom).toFloat()
    }

    private fun l2norm(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = if (s <= 0.0) 1.0 else sqrt(s)
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }

    fun close() {
        detector?.close()
        embedder.close()
    }
}