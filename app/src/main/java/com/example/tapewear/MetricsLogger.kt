package com.example.tapewear

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

object MetricsLogger {

    private const val TAG = "TapeWear_Metrics"

    private fun appendLine(ctx: Context, fileName: String, line: String) {
        try {
            val f = File(ctx.filesDir, fileName)
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "appendLine($fileName) failed: ${e.message}", e)
        }
    }

    /**
     * Coarse CPU / memory snapshot.
     * File: metrics_system.txt
     * Format: ts,stage,usedMb,freeMb,maxMb,heapAllocMb,threadCpuMs
     */
    fun logSystemSnapshot(ctx: Context, stage: String) {
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory()
        val free = rt.freeMemory()
        val max = rt.maxMemory()
        val used = total - free
        val heapAlloc = Debug.getNativeHeapAllocatedSize()
        val threadCpuMs = Debug.threadCpuTimeNanos() / 1_000_000L
        val ts = SystemClock.elapsedRealtime()

        val line = listOf(
            ts.toString(),
            stage,
            (used / (1024.0 * 1024.0)).toString(),
            (free / (1024.0 * 1024.0)).toString(),
            (max / (1024.0 * 1024.0)).toString(),
            (heapAlloc / (1024.0 * 1024.0)).toString(),
            threadCpuMs.toString()
        ).joinToString(",")

        appendLine(ctx, "metrics_system.txt", line)
    }

    /**
     * Registration latency per session.
     * File: metrics_reg_latency.txt
     * Format: ts,slot,regTotalMs,keptSamples,usedForEnroll,savedCrops
     */
    fun logRegistrationSession(
        ctx: Context,
        slot: Int,
        regTotalMs: Long,
        keptSamples: Int,
        usedForEnroll: Int,
        savedCrops: Int
    ) {
        val ts = SystemClock.elapsedRealtime()
        val line = listOf(
            ts.toString(),
            slot.toString(),
            regTotalMs.toString(),
            keptSamples.toString(),
            usedForEnroll.toString(),
            savedCrops.toString()
        ).joinToString(",")
        appendLine(ctx, "metrics_reg_latency.txt", line)
    }

    /**
     * Frames per registration session.
     * File: metrics_reg_frames.txt
     * Format: ts,slot,keptSamples,usedForEnroll,savedCrops
     */
    fun logFramesPerRegistration(
        ctx: Context,
        slot: Int,
        keptSamples: Int,
        usedForEnroll: Int,
        savedCrops: Int
    ) {
        val ts = SystemClock.elapsedRealtime()
        val line = listOf(
            ts.toString(),
            slot.toString(),
            keptSamples.toString(),
            usedForEnroll.toString(),
            savedCrops.toString()
        ).joinToString(",")
        appendLine(ctx, "metrics_reg_frames.txt", line)
    }

    /**
     * All authentication attempts.
     * File: metrics_auth_all.txt
     * Format: ts,slot,similarity,isMatch,burstMs,fps
     */
    fun logAuthAttempt(
        ctx: Context,
        slot: Int,
        similarity: Float,
        isMatch: Boolean,
        burstMs: Long,
        fps: Float
    ) {
        val ts = SystemClock.elapsedRealtime()
        val line = listOf(
            ts.toString(),
            slot.toString(),
            similarity.toString(),
            isMatch.toString(),
            burstMs.toString(),
            fps.toString()
        ).joinToString(",")
        appendLine(ctx, "metrics_auth_all.txt", line)
    }

    /**
     * Slot 1 only – repeated attempts.
     * File: metrics_auth_slot1.txt
     * Format: ts,similarity,isMatch,burstMs,fps
     */
    fun logAuthSlot1Repeat(
        ctx: Context,
        similarity: Float,
        isMatch: Boolean,
        burstMs: Long,
        fps: Float
    ) {
        val ts = SystemClock.elapsedRealtime()
        val line = listOf(
            ts.toString(),
            similarity.toString(),
            isMatch.toString(),
            burstMs.toString(),
            fps.toString()
        ).joinToString(",")


        appendLine(ctx, "metrics_auth_slot1.txt", line)
    }

    /**
     * Keep the best similarity per slot.
     * File: metrics_auth_best.json
     * JSON map: { "slot_1": 0.92, "slot_2": 0.88, ... }
     */
    fun updateBestAuth(
        ctx: Context,
        slot: Int,
        similarity: Float
    ) {
        val f = File(ctx.filesDir, "metrics_auth_best.json")
        val js = if (f.exists() && f.length() > 0) {
            try {
                JSONObject(f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse metrics_auth_best.json, resetting: ${e.message}")
                JSONObject()
            }
        } else {
            JSONObject()
        }

        val key = "slot_$slot"
        val old = js.optDouble(key, -1.0)
        if (similarity.toDouble() > old) {
            js.put(key, similarity.toDouble())
            try {
                f.writeText(js.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write metrics_auth_best.json: ${e.message}", e)
            }
        }
    }
}