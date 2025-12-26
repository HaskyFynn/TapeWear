package com.example.tapewear

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException

object MetricsLogger {

    private const val TAG = "TapeWear_Metrics"

    // --- GENERAL METRIC FILES (in /files/) ---
    private const val FILE_SYSTEM_GENERAL = "general_system_metrics.csv"
    private const val FILE_REG_LATENCY_GENERAL = "general_reg_latency.csv"

    // --- SLOT-SPECIFIC FILE NAMES (inside /files/slot_X/) ---
    private const val FILE_AUTH_ATTEMPTS_SLOT = "auth_attempts.csv"
    private const val FILE_REG_SAMPLES_SLOT = "reg_samples.csv"

    /**
     * Helper to write a line to a file, creating directories if needed.
     */
    private fun appendCsvLine(
        file: File,
        header: String,
        line: String
    ) {
        try {
            file.parentFile?.mkdirs()

            if (!file.exists()) {
                file.writeText("$header\n")
            }
            file.appendText("$line\n")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to ${file.absolutePath}: ${e.message}", e)
        }
    }

    // ===================================================================
    //                  GENERAL METRICS LOGGING
    // ===================================================================

    /**
     * Logs a system-wide snapshot of CPU and memory usage.
     * Format: ts_ms,stage,used_mb,thread_cpu_ms
     */
    fun logSystemSnapshot(ctx: Context, stage: String) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        val threadCpuMs = Debug.threadCpuTimeNanos() / 1_000_000L
        val ts = SystemClock.elapsedRealtime()

        val header = "ts_ms,stage,used_mb,thread_cpu_ms"
        val line = "%d,%s,%.2f,%d".format(ts, stage, usedMb, threadCpuMs)

        val file = File(ctx.filesDir, FILE_SYSTEM_GENERAL)
        appendCsvLine(file, header, line)
    }

    /**
     * Logs the overall latency for a registration session.
     * Format: ts_ms,slot,reg_total_ms,kept_samples
     */
    fun logRegistrationSession(
        ctx: Context,
        slot: Int,
        regTotalMs: Long,
        keptSamples: Int
    ) {
        val ts = SystemClock.elapsedRealtime()
        val header = "ts_ms,slot,reg_total_ms,kept_samples"
        val line = listOf(ts, slot, regTotalMs, keptSamples).joinToString(",")

        val file = File(ctx.filesDir, FILE_REG_LATENCY_GENERAL)
        appendCsvLine(file, header, line)
    }

    // ===================================================================
    //                  SLOT-SPECIFIC METRICS LOGGING
    // ===================================================================

    /**
     * Logs the number of frames kept for a registration session in a specific slot folder.
     * Format: ts_ms,kept_samples,used_for_enroll
     */
    fun logFramesPerRegistration(
        ctx: Context,
        slot: Int,
        keptSamples: Int,
        usedForEnroll: Int
    ) {
        val ts = SystemClock.elapsedRealtime()
        val header = "ts_ms,kept_samples,used_for_enroll"
        val line = listOf(ts, keptSamples, usedForEnroll).joinToString(",")

        val slotDir = File(ctx.filesDir, "slot_$slot")
        val file = File(slotDir, FILE_REG_SAMPLES_SLOT)
        appendCsvLine(file, header, line)
    }

    /**
     * Logs a specific authentication attempt in a specific slot folder.
     * Format: ts_ms,similarity,is_match,burst_ms,frames_collected
     */
    fun logAuthAttempt(
        ctx: Context,
        slot: Int,
        similarity: Float,
        isMatch: Boolean,
        burstMs: Long,
        framesCollected: Int
    ) {
        val ts = SystemClock.elapsedRealtime()
        val header = "ts_ms,similarity,is_match,burst_ms,frames_collected"
        val line = "%d,%.4f,%b,%d,%d".format(ts, similarity, isMatch, burstMs, framesCollected)

        val slotDir = File(ctx.filesDir, "slot_$slot")
        val file = File(slotDir, FILE_AUTH_ATTEMPTS_SLOT)
        appendCsvLine(file, header, line)
    }
}
