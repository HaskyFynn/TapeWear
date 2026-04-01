package com.example.tapewear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log

/**
 * The detect → embed → score pipeline.
 * Uses [AuthConfig] for all thresholds, [MathUtils] for vector math,
 * and [EnrollmentStore] for enrollment persistence.
 */
object ScoringEngine {

    private const val TAG = "TapeWear_Score"

    // ---- Data classes ----

    data class Verdict(val similarity: Float, val isMatch: Boolean)

    /** Verdict plus per-stage timing breakdown (ms). */
    data class ScoredResult(
        val verdict: Verdict,
        val scoredFrames: Int,
        val detectMs: Long,
        val embedMs: Long,
        val cosineMs: Long
    )

    // ---- Scoring ----

    fun scoreFromBitmaps(
        context: Context,
        detector: ModelManager.Detector,
        embedder: ModelManager.Embedder,
        frames: List<Bitmap>,
        take: Int = 3,
        slot: Int = EnrollmentStore.activeSlot
    ): ScoredResult {
        val emptyResult = ScoredResult(Verdict(-1f, false), 0, 0, 0, 0)
        if (frames.isEmpty()) return emptyResult

        val enroll = EnrollmentStore.load(context, slot) ?: run {
            Log.e(TAG, "scoreFromBitmaps: no enroll model for slot=$slot")
            return emptyResult
        }
        val proto = enroll.mean
        if (proto.isEmpty()) {
            Log.e(TAG, "scoreFromBitmaps: enroll mean is empty")
            return emptyResult
        }

        val simsPerFrame = arrayListOf<Float>()
        val maxFrames = take.coerceAtLeast(1)
        var idx = 0

        var totalDetectMs = 0L
        var totalEmbedMs = 0L
        var totalCosineMs = 0L

        for (bmp in frames) {
            if (idx >= maxFrames) break

            val tDet0 = SystemClock.elapsedRealtime()
            val dets = detector.detect(bmp)
            val tDet1 = SystemClock.elapsedRealtime()
            totalDetectMs += tDet1 - tDet0

            if (dets.isEmpty()) {
                Log.d(TAG, "scoreFromBitmaps: no detection for frame#$idx")
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

            val tEmb0 = SystemClock.elapsedRealtime()
            val emb = embedder.embed(bmp, roiRect)
            val tEmb1 = SystemClock.elapsedRealtime()
            totalEmbedMs += tEmb1 - tEmb0

            if (emb.size != proto.size) {
                Log.w(TAG, "frame#$idx embedding dim mismatch: emb=${emb.size} proto=${proto.size}")
                idx++
                continue
            }

            val tCos0 = SystemClock.elapsedRealtime()
            val sim = MathUtils.cosine(emb, proto).coerceIn(-1f, 1f)
            val tCos1 = SystemClock.elapsedRealtime()
            totalCosineMs += tCos1 - tCos0

            Log.d(TAG, "frame#$idx roi=$roiRect det=${tDet1-tDet0}ms emb=${tEmb1-tEmb0}ms sim=%.4f".format(sim))

            simsPerFrame.add(sim)
            idx++
        }

        if (simsPerFrame.isEmpty()) {
            Log.w(TAG, "scoreFromBitmaps: no valid frames scored")
            return ScoredResult(Verdict(-1f, false), 0, totalDetectMs, totalEmbedMs, totalCosineMs)
        }

        val sims = simsPerFrame.sorted()
        val n = sims.size
        val median = if (n % 2 == 1) sims[n / 2] else (sims[n / 2 - 1] + sims[n / 2]) / 2f
        val minSim = sims.first()
        val maxSim = sims.last()
        val spread = maxSim - minSim
        val mean = sims.sum() / n

        val isStable = spread <= AuthConfig.MAX_SPREAD
        val strong = median >= AuthConfig.MATCH_THRESHOLD
        val enough = n >= AuthConfig.MIN_SCORED_FRAMES

        val isMatch = isStable && strong && enough

        Log.d(TAG, "slot=$slot sims=$sims median=%.4f mean=%.4f spread=%.4f n=$n -> match=$isMatch"
            .format(median, mean, spread))

        return ScoredResult(
            verdict = Verdict(median, isMatch),
            scoredFrames = simsPerFrame.size,
            detectMs = totalDetectMs,
            embedMs = totalEmbedMs,
            cosineMs = totalCosineMs
        )
    }

    // ---- Enrollment ----

    fun enrollFromBitmaps(
        context: Context,
        detector: ModelManager.Detector,
        embedder: ModelManager.Embedder,
        frames: List<Bitmap>,
        maxEmbeds: Int = AuthConfig.MAX_ENROLL_EMBEDS,
        slot: Int = EnrollmentStore.activeSlot
    ): Int {
        if (frames.isEmpty()) return 0

        val embList = arrayListOf<FloatArray>()
        for ((idx, bmp) in frames.withIndex()) {
            val dets = detector.detect(bmp)
            if (dets.isEmpty()) {
                Log.d(TAG, "enrollFromBitmaps: no detection for frame#$idx")
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
            Log.d(TAG, "enroll frame#$idx roi=$roiRect embDim=${emb.size} time=${t1 - t0}ms")
            embList.add(emb)
            if (embList.size >= maxEmbeds) break
        }

        if (embList.isEmpty()) {
            Log.w(TAG, "enrollFromBitmaps: no embeddings collected")
            return 0
        }

        fitOrUpdate(embList, context, slot)
        return embList.size
    }

    fun fitOrUpdate(embeds: List<FloatArray>, ctx: Context, slot: Int = EnrollmentStore.activeSlot) {
        if (embeds.isEmpty()) return
        val dim = embeds[0].size
        val mean = FloatArray(dim)
        for (e in embeds) {
            for (i in 0 until dim) {
                mean[i] += e[i]
            }
        }
        for (i in 0 until dim) mean[i] /= embeds.size.toFloat()
        EnrollmentStore.save(ctx, mean, embeds.size, slot)
    }

    fun score(emb: FloatArray, ctx: Context, slot: Int = EnrollmentStore.activeSlot): Verdict {
        val en = EnrollmentStore.load(ctx, slot) ?: return Verdict(-1f, false)
        val sim = MathUtils.cosine(emb, en.mean)
        return Verdict(sim, sim >= AuthConfig.MATCH_THRESHOLD)
    }

    // ---- Helpers ----

    private fun chooseRoi(dets: List<ModelManager.Detection>): ModelManager.Detection =
        dets.maxByOrNull { it.score } ?: dets.first()
}
