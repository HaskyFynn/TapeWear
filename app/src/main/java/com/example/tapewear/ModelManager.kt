package com.example.tapewear

import android.content.Context
import android.graphics.*
import android.view.TextureView
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector-ready model manager.
 *  - detector: plug your YOLO here later (bitmap -> List<Detection> in *bitmap* coords).
 *  - default detector uses the overlay ROI mapped into bitmap coords.
 *  - embed(): placeholder handcrafted 128D descriptor; swap with CNN later.
 *  - enroll/score helpers accept raw bitmaps & optional overlay/texture for mapping.
 */
object ModelManager {

    // ----------------- Detector plug-in -----------------
    interface Detector {
        /** Return detections in BITMAP SPACE (pixels of the provided bitmap). */
        fun detect(full: Bitmap): List<Detection>
    }

    /** Default detector uses the overlay box (works now; replace with YOLO later). */
    class OverlayDetector(
        private val overlay: OverlayView?,
        private val texture: TextureView?
    ) : Detector {
        override fun detect(full: Bitmap): List<Detection> {
            if (overlay == null || texture == null) {
                // Fall back to whole frame
                return listOf(Detection(RectF(0f, 0f, full.width.toFloat(), full.height.toFloat()), 1f, 0))
            }
            val vr = overlay.getFramingBox() // in view coords
            val vw = max(1, texture.width)
            val vh = max(1, texture.height)
            val L = vr.left * full.width / vw
            val T = vr.top * full.height / vh
            val R = vr.right * full.width / vw
            val B = vr.bottom * full.height / vh
            return listOf(Detection(RectF(L, T, R, B), 1f, 0))
        }
    }

    /** Swap this to your YOLO-based detector when it’s ready. */
    var detector: Detector? = null

    // ----------------- Persistence -----------------
    private const val ENROLL_FILE = "enroll.json"
    private const val KEY_VEC = "vec"
    private const val KEY_DIM = "dim"
    private const val KEY_COUNT = "count"

    data class Enroll(val mean: FloatArray, val count: Int)

    // Cosine cutoff for placeholder matcher (tune later with data)
    var COSINE_THRESHOLD = 0.85f

    // ----------------- Public high-level APIs -----------------

    /**
     * Enroll from multiple frames: detect -> pick ROI -> embed -> average -> save.
     * @return number of embeddings actually used.
     */
    fun enrollFromBitmaps(
        frames: List<Bitmap>,
        overlay: OverlayView?,
        texture: TextureView?,
        maxEmbeds: Int = 32,
        expandBox: Float = 1.10f,     // slightly include margins
        preferOverlayIoU: Boolean = true
    ): Int {
        if (frames.isEmpty()) return 0
        val embList = ArrayList<FloatArray>()
        val det = detector ?: OverlayDetector(overlay, texture)

        for (bmp in frames) {
            val dets = det.detect(bmp)
            if (dets.isEmpty()) continue

            val chosen = chooseRoi(dets, bmp, overlay, texture, preferOverlayIoU)
            val expanded = BoxOps.expand(chosen.box, expandBox, bmp.width, bmp.height)
            val roi = BoxOps.clamp(expanded, bmp.width, bmp.height)

            val emb = embed(bmp, roi)
            embList.add(emb)

            if (embList.size >= maxEmbeds) break
        }

        if (embList.isEmpty()) return 0
        fitOrUpdate(embList, texture?.context ?: overlay?.context ?: return 0)
        return embList.size
    }

    /**
     * Score from one or more frames (e.g., pass your sharpest 3–5).
     */
    fun scoreFromBitmaps(
        frames: List<Bitmap>,
        overlay: OverlayView?,
        texture: TextureView?,
        take: Int = 3,
        expandBox: Float = 1.10f
    ): Verdict {
        if (frames.isEmpty()) return Verdict(-1f, false)
        val det = detector ?: OverlayDetector(overlay, texture)

        val embs = ArrayList<FloatArray>()
        var k = 0
        for (bmp in frames) {
            val dets = det.detect(bmp)
            if (dets.isEmpty()) continue
            val chosen = chooseRoi(dets, bmp, overlay, texture, preferOverlayIoU = true)
            val roi = BoxOps.clamp(BoxOps.expand(chosen.box, expandBox, bmp.width, bmp.height), bmp.width, bmp.height)
            embs += embed(bmp, roi)
            k++
            if (k >= take) break
        }
        if (embs.isEmpty()) return Verdict(-1f, false)

        // Average probe embedding
        val dim = embs[0].size
        val mean = FloatArray(dim)
        for (e in embs) for (i in 0 until dim) mean[i] += e[i]
        for (i in 0 until dim) mean[i] /= embs.size.toFloat()

        return score(mean, texture?.context ?: overlay?.context ?: return Verdict(-1f, false))
    }

    // ----------------- ROI choosing strategy -----------------
    private fun chooseRoi(
        dets: List<Detection>,
        bmp: Bitmap,
        overlay: OverlayView?,
        texture: TextureView?,
        preferOverlayIoU: Boolean
    ): Detection {
        if (!preferOverlayIoU || overlay == null || texture == null) {
            // Highest score, then largest area
            return dets.maxWithOrNull(
                compareBy<Detection>({ it.score })
                    .thenBy { it.box.width() * it.box.height() }
            ) ?: dets.first()
        }

        // Prefer detections overlapping the overlay box
        val overlayBox = OverlayDetector(overlay, texture).detect(bmp).first().box
        var best = dets.first()
        var bestKey = roiPrefKey(overlayBox, best)
        for (d in dets) {
            val k = roiPrefKey(overlayBox, d)
            if (k > bestKey) { best = d; bestKey = k }
        }
        return best
    }

    /** Blend IoU with detection score (0.7 IoU, 0.3 score). */
    private fun roiPrefKey(overlayRect: RectF, d: Detection): Float {
        val iou = BoxOps.iou(overlayRect, d.box)
        return 0.7f * iou + 0.3f * d.score
    }

    // ----------------- Embedding (placeholder) -----------------
    /**
     * Handcrafted 128D descriptor over a normalized crop (128x128).
     * Replace this with a CNN (e.g., MobileNetV3) when ready.
     */
    fun embed(src: Bitmap, roi: Rect): FloatArray {
        val safeW = roi.width().coerceAtLeast(1)
        val safeH = roi.height().coerceAtLeast(1)
        val crop = Bitmap.createBitmap(src, roi.left, roi.top, safeW, safeH)
        val resized = Bitmap.createScaledBitmap(crop, 128, 128, true)
        crop.recycle()
        val vec = feature128(resized)
        resized.recycle()
        return l2norm(vec)
    }

    private fun feature128(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val binsL = FloatArray(64) // luminance histogram
        val binsE = FloatArray(64) // simple edge-magnitude histogram

        fun lum(p: Int): Int = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3

        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val l = lum(p)
                val lbin = (l * 64 / 256).coerceIn(0, 63)
                binsL[lbin] += 1f

                val lpx = lum(row[max(0, x - 1)])
                val rpx = lum(row[min(w - 1, x + 1)])
                val mag = kotlin.math.abs(rpx - lpx).coerceAtMost(255)
                val ebin = (mag * 64 / 256).coerceIn(0, 63)
                binsE[ebin] += 1f

                x += 2
            }
            y += 2
        }
        val out = FloatArray(128)
        System.arraycopy(binsL, 0, out, 0, 64)
        System.arraycopy(binsE, 0, out, 64, 64)
        return out
    }

    private fun l2norm(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = if (s <= 0.0) 1.0 else sqrt(s)
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }

    // ----------------- Persistence & scoring -----------------
    private fun saveEnroll(ctx: Context, mean: FloatArray, count: Int) {
        val bb = ByteBuffer.allocate(mean.size * 4)
        bb.asFloatBuffer().put(mean)
        val vecStr = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        val js = JSONObject()
            .put(KEY_DIM, mean.size)
            .put(KEY_COUNT, count)
            .put(KEY_VEC, vecStr)
        File(ctx.filesDir, ENROLL_FILE).writeText(js.toString())
    }

    fun loadEnroll(ctx: Context): Enroll? {
        val f = File(ctx.filesDir, ENROLL_FILE)
        if (!f.exists()) return null
        return try {
            val js = JSONObject(f.readText())
            val dim = js.getInt(KEY_DIM)
            val count = js.getInt(KEY_COUNT)
            val vecStr = js.getString(KEY_VEC)
            val bytes = Base64.decode(vecStr, Base64.NO_WRAP)
            val fb = ByteBuffer.wrap(bytes).asFloatBuffer()
            val arr = FloatArray(dim)
            fb.get(arr)
            Enroll(arr, count)
        } catch (_: Exception) { null }
    }

    /** Fit (or overwrite) the enrollment mean vector. */
    fun fitOrUpdate(embeds: List<FloatArray>, ctx: Context) {
        if (embeds.isEmpty()) return
        val dim = embeds[0].size
        val mean = FloatArray(dim)
        for (e in embeds) for (i in 0 until dim) mean[i] += e[i]
        for (i in 0 until dim) mean[i] /= embeds.size.toFloat()
        saveEnroll(ctx, mean, embeds.size)
    }

    data class Verdict(val similarity: Float, val isMatch: Boolean)

    fun score(emb: FloatArray, ctx: Context): Verdict {
        val en = loadEnroll(ctx) ?: return Verdict(-1f, false)
        val sim = cosine(emb, en.mean)
        return Verdict(sim, sim >= COSINE_THRESHOLD)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            num += x * y
            da += x * x
            db += y * y
        }
        val denom = (sqrt(da) * sqrt(db)).let { if (it == 0.0) 1.0 else it }
        return (num / denom).toFloat()
    }

    // --- NEW: Embedder plug-in ---------------------------------------------------
    interface Embedder {
        /** Returns an L2-normalized embedding vector. */
        fun embed(src: Bitmap, roi: Rect): FloatArray
    }

    /** Default handcrafted embedder (your current 128-D). */
    private object HandcraftedEmbedder : Embedder {
        override fun embed(src: Bitmap, roi: Rect): FloatArray {
            val crop = Bitmap.createBitmap(src, roi.left, roi.top, roi.width(), roi.height())
            val target = 128
            val resized = Bitmap.createScaledBitmap(
                crop, target, max(1, target * crop.height / crop.width), true
            )
            crop.recycle()
            val vec = feature128(resized)       // <- your existing function
            resized.recycle()
            return l2norm(vec)
        }
    }

    /** Active embedder (swap to TFLiteEmbedder when ready). */
    var embedder: Embedder = HandcraftedEmbedder

}
