package com.example.tapewear

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.ExperimentStore
import com.example.tapewear.ml.ModelManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetricsLogger {

    private const val TAG = "TapeWear_Metrics"

    // Standard files
    private fun authFile(night: Boolean) = if (night) "auth_log_night_v4.csv" else "auth_log_day_v4.csv"
    private fun regFile(night: Boolean) = if (night) "reg_log_night_v4.csv" else "reg_log_day_v4.csv"
    private fun stagesFile(night: Boolean) = if (night) "auth_stages_log_night_v4.csv" else "auth_stages_log_day_v4.csv"

    // Experiment files
    const val LEGACY_EXP_REG_FILE_V2 = "experiment_registration_v2.csv"
    const val LEGACY_EXP_AUTH_FILE_V2 = "experiment_authentication_v2.csv"
    const val LEGACY_EXP_STAGES_FILE_V2 = "experiment_auth_stages_v2.csv"
    const val LEGACY_EXP_REG_FILE_V3 = "experiment_registration_v3.csv"
    const val LEGACY_EXP_AUTH_FILE_V3 = "experiment_authentication_v3.csv"
    const val LEGACY_EXP_STAGES_FILE_V3 = "experiment_auth_stages_v3.csv"
    const val EXP_REG_FILE = "experiment_registration_v4.csv"
    const val EXP_AUTH_FILE = "experiment_authentication_v4.csv"
    const val EXP_STAGES_FILE = "experiment_auth_stages_v4.csv"
    const val EXP_SESSION_SUMMARY_FILE = "experiment_session_summary_v4.txt"

    private const val EXPERIMENT_PREFIX_HEADER =
        "participant_id,operator_id,study_block,session_id,session_started_at," +
            "app_version,device_model,android_version,timezone,trial_status,failure_reason,"

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

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

    data class FlashTelemetry(
        val flashHardwareAvailable: Boolean,
        val torchLastAckState: String,
        val torchAckedOnDuringAttempt: Boolean
    ) {
        companion object {
            fun unsupported() = FlashTelemetry(
                flashHardwareAvailable = false,
                torchLastAckState = "unsupported",
                torchAckedOnDuringAttempt = false
            )
        }
    }

    private data class RuntimeConfigSnapshot(
        val matchThreshold: Float,
        val yoloConfThreshold: Float,
        val pipelineMode: String,
        val useMlEmbedder: Boolean,
        val experimentMode: Boolean,
        val captureIllumination: String,
        val captureDistance: String,
        val handsFreeEnabled: Boolean,
        val handsFreeConsecutiveHits: Int,
        val experimentFlashEnabled: Boolean,
        val flashRequested: Boolean,
        val qualityMotionMax: Double,
        val qualityBlurMin: Double,
        val regBurstMs: Long,
        val regTargetFrames: Int,
        val detector: String
    )

    private data class ExperimentLogContext(
        val participantId: String,
        val operatorId: String,
        val studyBlock: String,
        val sessionId: String,
        val sessionStartedAt: String,
        val appVersion: String,
        val deviceModel: String,
        val androidVersion: String,
        val timezone: String
    )

    private fun csvSafe(raw: String): String =
        raw.replace(",", "_").replace("\"", "").replace("\n", " ").replace("\r", "").trim()

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

    private fun runtimeConfig(nightMode: Boolean): RuntimeConfigSnapshot {
        val gates = if (nightMode) Quality.NIGHT else Quality.DAY
        val detector = if (ModelManager.detector is ModelManager.TFLiteYoloDetector) "yolo" else "none"
        val experimentMode = AuthConfig.EXPERIMENT_MODE
        val captureIllumination = if (experimentMode) {
            AuthConfig.EXPERIMENT_ILLUMINATION.lowercase(Locale.US)
        } else if (nightMode) {
            "dim"
        } else {
            "bright"
        }
        val captureDistance = if (experimentMode) {
            AuthConfig.EXPERIMENT_DISTANCE.lowercase(Locale.US)
        } else {
            "unspecified"
        }
        val pipelineMode = if (AuthConfig.USE_ML_EMBEDDER) "ml" else "cv"
        val flashRequested = if (experimentMode) {
            nightMode && AuthConfig.EXPERIMENT_FLASH_ENABLED
        } else {
            nightMode
        }
        return RuntimeConfigSnapshot(
            matchThreshold = AuthConfig.MATCH_THRESHOLD,
            yoloConfThreshold = AuthConfig.YOLO_CONF_THRESHOLD,
            pipelineMode = pipelineMode,
            useMlEmbedder = AuthConfig.USE_ML_EMBEDDER,
            experimentMode = experimentMode,
            captureIllumination = captureIllumination,
            captureDistance = captureDistance,
            handsFreeEnabled = AuthConfig.HANDS_FREE_ENABLED,
            handsFreeConsecutiveHits = AuthConfig.HANDS_FREE_CONSECUTIVE_HITS,
            experimentFlashEnabled = AuthConfig.EXPERIMENT_FLASH_ENABLED,
            flashRequested = flashRequested,
            qualityMotionMax = gates.motionMax,
            qualityBlurMin = gates.blurMin,
            regBurstMs = AuthConfig.REG_BURST_MS,
            regTargetFrames = AuthConfig.REG_TARGET_FRAMES,
            detector = detector
        )
    }

    private fun resolveAppVersion(ctx: Context): String {
        return runCatching {
            val packageInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            packageInfo.versionName?.takeIf { it.isNotBlank() }
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toString()
                }
        }.getOrElse { "unknown" }
    }

    private fun experimentContext(ctx: Context): ExperimentLogContext {
        val metadata = ExperimentStore.getStudyMetadata(ctx)
        return ExperimentLogContext(
            participantId = csvSafe(metadata.participantId.ifBlank { "unknown" }),
            operatorId = csvSafe(metadata.operatorId.ifBlank { "unknown" }),
            studyBlock = csvSafe(metadata.studyBlock.ifBlank { "unknown" }),
            sessionId = csvSafe(metadata.sessionId.ifBlank { "unknown" }),
            sessionStartedAt = csvSafe(metadata.sessionStartedAt.ifBlank { "" }),
            appVersion = csvSafe(resolveAppVersion(ctx)),
            deviceModel = csvSafe("${Build.MANUFACTURER} ${Build.MODEL}".trim()),
            androidVersion = csvSafe(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()),
            timezone = csvSafe(TimeZone.getDefault().id)
        )
    }

    private fun experimentPrefix(ctx: Context, trialStatus: String, failureReason: String): String {
        val exp = experimentContext(ctx)
        return String.format(
            Locale.US,
            "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
            exp.participantId,
            exp.operatorId,
            exp.studyBlock,
            exp.sessionId,
            exp.sessionStartedAt,
            exp.appVersion,
            exp.deviceModel,
            exp.androidVersion,
            exp.timezone,
            csvSafe(trialStatus.ifBlank { "completed" }),
            csvSafe(failureReason)
        )
    }

    private const val AUTH_HEADER =
        "datetime,slot,similarity,is_match,burst_ms," +
            "frames_collected,frames_scored,night_mode,demo_mode,experiment_mode," +
            "capture_illumination,capture_distance,pipeline_mode,use_ml_embedder," +
            "hands_free_enabled,hands_free_consecutive_hits,experiment_flash_enabled,flash_requested," +
            "flash_hardware_available,torch_last_ack_state,torch_acked_on_during_attempt," +
            "used_mb,thread_cpu_ms," +
            "match_threshold,yolo_conf_threshold," +
            "quality_motion_max,quality_blur_min,detector"

    private const val EXP_AUTH_HEADER =
        EXPERIMENT_PREFIX_HEADER + "pattern_tag_name,illumination,distance,trial_number,attempt," + AUTH_HEADER

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
        overhead: OverheadSnapshot?,
        flashTelemetry: FlashTelemetry = FlashTelemetry.unsupported(),
        trialStatus: String = "completed",
        failureReason: String = "",
        tagNameOverride: String? = null
    ) {
        val cfg = runtimeConfig(nightMode)
        val baseLine = String.format(
            Locale.US,
            "%s,%d,%.4f,%b,%d,%d,%d,%b,%b,%b,%s,%s,%s,%b,%b,%d,%b,%b,%b,%s,%b,%.2f,%d,%.4f,%.4f,%.4f,%.4f,%s",
            now(), slot, similarity, isMatch, burstMs,
            framesCollected, framesScored,
            nightMode, demoMode,
            cfg.experimentMode,
            csvSafe(cfg.captureIllumination),
            csvSafe(cfg.captureDistance),
            csvSafe(cfg.pipelineMode),
            cfg.useMlEmbedder,
            cfg.handsFreeEnabled,
            cfg.handsFreeConsecutiveHits,
            cfg.experimentFlashEnabled,
            cfg.flashRequested,
            flashTelemetry.flashHardwareAvailable,
            csvSafe(flashTelemetry.torchLastAckState),
            flashTelemetry.torchAckedOnDuringAttempt,
            overhead?.currentProcessRamMb() ?: 0.0,
            overhead?.deltaCpuMs() ?: 0L,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.detector
        )

        if (AuthConfig.EXPERIMENT_MODE) {
            val tag = csvSafe(tagNameOverride ?: (ExperimentStore.getCurrentTagName(ctx) ?: "unknown"))
            val trial = ExperimentStore.getAuthTrialCount(ctx)
            val attempt = ExperimentStore.getAuthAttempt(ctx)
            val line = String.format(
                Locale.US,
                "%s,%s,%s,%s,%d,%d,%s",
                experimentPrefix(ctx, trialStatus, failureReason),
                tag,
                AuthConfig.EXPERIMENT_ILLUMINATION,
                AuthConfig.EXPERIMENT_DISTANCE,
                trial,
                attempt,
                baseLine
            )
            val file = getPublicFile(ctx, EXP_AUTH_FILE)
            appendCsvLine(file, EXP_AUTH_HEADER, line)
            Log.d(TAG, "exp_auth -> $line")
        } else {
            val file = getPublicFile(ctx, authFile(nightMode))
            appendCsvLine(file, AUTH_HEADER, baseLine)
            Log.d(TAG, "auth -> $baseLine")
        }
    }

    private const val REG_HEADER =
        "datetime,slot,reg_total_ms,kept_samples,used_for_enroll," +
            "night_mode,demo_mode,experiment_mode," +
            "capture_illumination,capture_distance,pipeline_mode,use_ml_embedder," +
            "hands_free_enabled,hands_free_consecutive_hits,experiment_flash_enabled,flash_requested," +
            "flash_hardware_available,torch_last_ack_state,torch_acked_on_during_attempt," +
            "used_mb,thread_cpu_ms," +
            "match_threshold,yolo_conf_threshold," +
            "quality_motion_max,quality_blur_min,reg_burst_ms,reg_target_frames,detector"

    private const val EXP_REG_HEADER =
        EXPERIMENT_PREFIX_HEADER + "pattern_tag_name,illumination,distance," + REG_HEADER

    fun logRegistration(
        ctx: Context,
        slot: Int,
        regTotalMs: Long,
        keptSamples: Int,
        usedForEnroll: Int,
        nightMode: Boolean,
        demoMode: Boolean,
        overhead: OverheadSnapshot?,
        flashTelemetry: FlashTelemetry = FlashTelemetry.unsupported(),
        trialStatus: String = "completed",
        failureReason: String = "",
        tagNameOverride: String? = null
    ) {
        val cfg = runtimeConfig(nightMode)
        val baseLine = String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,%b,%b,%b,%s,%s,%s,%b,%b,%d,%b,%b,%b,%s,%b,%.2f,%d,%.4f,%.4f,%.4f,%.4f,%d,%d,%s",
            now(), slot, regTotalMs, keptSamples, usedForEnroll,
            nightMode, demoMode,
            cfg.experimentMode,
            csvSafe(cfg.captureIllumination),
            csvSafe(cfg.captureDistance),
            csvSafe(cfg.pipelineMode),
            cfg.useMlEmbedder,
            cfg.handsFreeEnabled,
            cfg.handsFreeConsecutiveHits,
            cfg.experimentFlashEnabled,
            cfg.flashRequested,
            flashTelemetry.flashHardwareAvailable,
            csvSafe(flashTelemetry.torchLastAckState),
            flashTelemetry.torchAckedOnDuringAttempt,
            overhead?.currentProcessRamMb() ?: 0.0,
            overhead?.deltaCpuMs() ?: 0L,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.regBurstMs,
            cfg.regTargetFrames,
            cfg.detector
        )

        if (AuthConfig.EXPERIMENT_MODE) {
            val tag = csvSafe(tagNameOverride ?: (ExperimentStore.getCurrentTagName(ctx) ?: "unknown"))
            val line = String.format(
                Locale.US,
                "%s,%s,%s,%s,%s",
                experimentPrefix(ctx, trialStatus, failureReason),
                tag,
                AuthConfig.EXPERIMENT_ILLUMINATION,
                AuthConfig.EXPERIMENT_DISTANCE,
                baseLine
            )
            val file = getPublicFile(ctx, EXP_REG_FILE)
            appendCsvLine(file, EXP_REG_HEADER, line)
            Log.d(TAG, "exp_reg -> $line")
        } else {
            val file = getPublicFile(ctx, regFile(nightMode))
            appendCsvLine(file, REG_HEADER, baseLine)
            Log.d(TAG, "reg -> $baseLine")
        }
    }

    private const val STAGES_HEADER =
        "datetime,slot,settle_ms,capture_ms,quality_ms," +
            "detect_ms,embed_ms,cosine_ms,total_ms,night_mode,demo_mode,experiment_mode," +
            "capture_illumination,capture_distance,pipeline_mode,use_ml_embedder," +
            "hands_free_enabled,hands_free_consecutive_hits,experiment_flash_enabled,flash_requested," +
            "flash_hardware_available,torch_last_ack_state,torch_acked_on_during_attempt," +
            "match_threshold,yolo_conf_threshold," +
            "quality_motion_max,quality_blur_min,detector"

    private const val EXP_STAGES_HEADER =
        EXPERIMENT_PREFIX_HEADER + "pattern_tag_name,illumination,distance,trial_number,attempt," + STAGES_HEADER

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
        nightMode: Boolean,
        demoMode: Boolean,
        flashTelemetry: FlashTelemetry = FlashTelemetry.unsupported(),
        trialStatus: String = "completed",
        failureReason: String = "",
        tagNameOverride: String? = null
    ) {
        val cfg = runtimeConfig(nightMode)
        val baseLine = String.format(
            Locale.US,
            "%s,%d,%d,%d,%d,%d,%d,%d,%d,%b,%b,%b,%s,%s,%s,%b,%b,%d,%b,%b,%b,%s,%b,%.4f,%.4f,%.4f,%.4f,%s",
            now(), slot,
            settleMs, captureMs, qualityMs,
            detectMs, embedMs, cosineMs,
            totalMs,
            nightMode,
            demoMode,
            cfg.experimentMode,
            csvSafe(cfg.captureIllumination),
            csvSafe(cfg.captureDistance),
            csvSafe(cfg.pipelineMode),
            cfg.useMlEmbedder,
            cfg.handsFreeEnabled,
            cfg.handsFreeConsecutiveHits,
            cfg.experimentFlashEnabled,
            cfg.flashRequested,
            flashTelemetry.flashHardwareAvailable,
            csvSafe(flashTelemetry.torchLastAckState),
            flashTelemetry.torchAckedOnDuringAttempt,
            cfg.matchThreshold,
            cfg.yoloConfThreshold,
            cfg.qualityMotionMax,
            cfg.qualityBlurMin,
            cfg.detector
        )

        if (AuthConfig.EXPERIMENT_MODE) {
            val tag = csvSafe(tagNameOverride ?: (ExperimentStore.getCurrentTagName(ctx) ?: "unknown"))
            val trial = ExperimentStore.getAuthTrialCount(ctx)
            val attempt = ExperimentStore.getAuthAttempt(ctx)
            val line = String.format(
                Locale.US,
                "%s,%s,%s,%s,%d,%d,%s",
                experimentPrefix(ctx, trialStatus, failureReason),
                tag,
                AuthConfig.EXPERIMENT_ILLUMINATION,
                AuthConfig.EXPERIMENT_DISTANCE,
                trial,
                attempt,
                baseLine
            )
            val file = getPublicFile(ctx, EXP_STAGES_FILE)
            appendCsvLine(file, EXP_STAGES_HEADER, line)
            Log.d(TAG, "exp_stages -> $line")
        } else {
            val file = getPublicFile(ctx, stagesFile(nightMode))
            appendCsvLine(file, STAGES_HEADER, baseLine)
            Log.d(TAG, "stages -> $baseLine")
        }
    }
}
