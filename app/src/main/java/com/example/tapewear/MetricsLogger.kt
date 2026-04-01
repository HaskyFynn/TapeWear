package com.example.tapewear

import android.content.Context
import android.os.Debug
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MetricsLogger {

    private const val TAG = "TapeWear_Metrics"

    private fun authFile(night: Boolean) = if (night) "auth_log_night_v2.csv" else "auth_log_day_v2.csv"
    private fun regFile(night: Boolean)  = if (night) "reg_log_night_v2.csv" else "reg_log_day_v2.csv"
    private fun stagesFile(night: Boolean)= if (night) "auth_stages_log_night_v2.csv" else "auth_stages_log_day_v2.csv"

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    // ---- helpers ----

    @Synchronized
    private fun now(): String = isoFormatter.format(Date())

    class OverheadSnapshot(
        val startCpuMs: Long = android.os.Process.getElapsedCpuTime()
    ) {
        fun deltaCpuMs(): Long = android.os.Process.getElapsedCpuTime() - startCpuMs
        fun currentProcessRamMb(): Double {
            val rt = Runtime.getRuntime()
            val javaUsed = rt.totalMemory() - rt.freeMemory()
            val nativeUsed = Debug.getNativeHeapAllocatedSize()
            return (javaUsed + nativeUsed) / 1_048_576.0
        }
    }

    private fun getPublicFile(ctx: Context, filename: String): File {
        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
        val parent = File(dir, "TapeWear_Metrics")
        parent.mkdirs()
        return File(parent, filename)
    }

    private fun appendCsvLine(file: File, header: String, line: String) {
        try {
            if (!file.exists()) {
                file.writeText("$header\n")
            }
            file.appendText("$line\n")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to ${file.absolutePath}: ${e.message}", e)
        }
    }

    private data class RuntimeConfigSnapshot(
        val matchThreshold: Float,
        val yoloConfThreshold: Float,
        val useMlEmbedder: Boolean,
        val qualityMotionMax: Double,
        val qualityBlurMin: Double,
        val regBurstMs: Long,
        val regTargetFrames: Int,
        val detector: String
    )

    private fun runtimeConfig(nightMode: Boolean): RuntimeConfigSnapshot {
        val gates = if (nightMode) Quality.NIGHT else Quality.DAY
        val detector = if (ModelManager.detector is ModelManager.TFLiteYoloDetector) "yolo" else "none"
        return RuntimeConfigSnapshot(
            matchThreshold = AuthConfig.MATCH_THRESHOLD,
            yoloConfThreshold = AuthConfig.YOLO_CONF_THRESHOLD,
            useMlEmbedder = AuthConfig.USE_ML_EMBEDDER,
            qualityMotionMax = gates.motionMax,
            qualityBlurMin = gates.blurMin,
            regBurstMs = AuthConfig.REG_BURST_MS,
            regTargetFrames = AuthConfig.REG_TARGET_FRAMES,
            detector = detector
        )
    }

    // ===================================================================
    //  AUTH LOG — one row per authentication attempt
    // ===================================================================

    private const val AUTH_HEADER =
        "datetime,slot,similarity,is_match,burst_ms," +
        "frames_collected,frames_scored,night_mode,demo_mode," +
        "used_mb,thread_cpu_ms," +
        "match_threshold,yolo_conf_threshold,use_ml_embedder," +
        "quality_motion_max,quality_blur_min,detector"

    /**
     * Log a complete authentication attempt. Call once per auth, after scoring.
     */
    fun logAuth(
        ctx: Context,
        slot: Int,
        similarity: Float,
        isMatch: Boolean,
        burstMs: Long,
        framesCollected: Int,
        framesScored: Int,
        nightMode: Boolean,
        demoMode: Boolean,
        overhead: OverheadSnapshot?
    ) {
        val cfg = runtimeConfig(nightMode)
        val line = String.format(
            Locale.US,
            "%s,%d,%.4f,%b,%d,%d,%d,%b,%b,%.2f,%d,%.4f,%.4f,%b,%.4f,%.4f,%s",
            now(), slot, similarity, isMatch, burstMs,
            framesCollected, framesScored,
            nightMode, demoMode,
            overhead?.currentProcessRamMb() ?: 0.0,
            overhead?.deltaCpuMs() ?: 0L,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.useMlEmbedder,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.detector
        )
        val file = getPublicFile(ctx, authFile(nightMode))
        appendCsvLine(file, AUTH_HEADER, line)
        Log.d(TAG, "auth → $line")
    }

    // ===================================================================
    //  REG LOG — one row per registration session
    // ===================================================================

    private const val REG_HEADER =
        "datetime,slot,reg_total_ms,kept_samples,used_for_enroll," +
        "night_mode,demo_mode,used_mb,thread_cpu_ms," +
        "match_threshold,yolo_conf_threshold,use_ml_embedder," +
        "quality_motion_max,quality_blur_min,reg_burst_ms,reg_target_frames,detector"

    /**
     * Log a complete registration session. Call once per registration, after enrollment.
     */
    fun logRegistration(
        ctx: Context,
        slot: Int,
        regTotalMs: Long,
        keptSamples: Int,
        usedForEnroll: Int,
        nightMode: Boolean,
        demoMode: Boolean,
        overhead: OverheadSnapshot?
    ) {
        val cfg = runtimeConfig(nightMode)
        val line = String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,%b,%b,%.2f,%d,%.4f,%.4f,%b,%.4f,%.4f,%d,%d,%s",
            now(), slot, regTotalMs, keptSamples, usedForEnroll,
            nightMode, demoMode,
            overhead?.currentProcessRamMb() ?: 0.0,
            overhead?.deltaCpuMs() ?: 0L,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.useMlEmbedder,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.regBurstMs,
            cfg.regTargetFrames,
            cfg.detector
        )
        val file = getPublicFile(ctx, regFile(nightMode))
        appendCsvLine(file, REG_HEADER, line)
        Log.d(TAG, "reg → $line")
    }

    // ===================================================================
    //  AUTH STAGES LOG — per-stage latency breakdown
    // ===================================================================

    private const val STAGES_HEADER =
        "datetime,slot,settle_ms,capture_ms,quality_ms," +
        "detect_ms,embed_ms,cosine_ms,total_ms," +
        "match_threshold,yolo_conf_threshold,use_ml_embedder," +
        "quality_motion_max,quality_blur_min,detector"

    /**
     * Log per-stage latency breakdown for one auth attempt.
     * Ideal for stacked bar charts showing where time is spent.
     */
    fun logAuthStages(
        ctx: Context,
        slot: Int,
        settleMs: Long,
        captureMs: Long,
        qualityMs: Long,
        detectMs: Long,
        embedMs: Long,
        cosineMs: Long,
        totalMs: Long,
        nightMode: Boolean
    ) {
        val cfg = runtimeConfig(nightMode)
        val line = String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%b,%.4f,%.4f,%s",
            now(), slot,
            settleMs, captureMs, qualityMs,
            detectMs, embedMs, cosineMs,
            totalMs,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.useMlEmbedder,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.detector
        )
        val file = getPublicFile(ctx, stagesFile(nightMode))
        appendCsvLine(file, STAGES_HEADER, line)
        Log.d(TAG, "stages → $line")
    }
}
