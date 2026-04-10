package com.example.tapewear.data

import android.content.Context
import com.example.tapewear.Quality
import com.example.tapewear.config.AuthConfig

object SettingsStore {
    private const val PREFS_NAME = "tapewear_settings"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        AuthConfig.MATCH_THRESHOLD = prefs.getFloat("MATCH_THRESHOLD", AuthConfig.DEFAULT_MATCH_THRESHOLD)
        AuthConfig.YOLO_CONF_THRESHOLD = prefs.getFloat("YOLO_CONF_THRESHOLD", AuthConfig.DEFAULT_YOLO_CONF_THRESHOLD)
        AuthConfig.USE_ML_EMBEDDER = prefs.getBoolean("USE_ML_EMBEDDER", AuthConfig.DEFAULT_USE_ML_EMBEDDER)
        AuthConfig.REG_BURST_MS = prefs.getLong("REG_BURST_MS", AuthConfig.DEFAULT_REG_BURST_MS)
        AuthConfig.REG_TARGET_FRAMES = prefs.getInt("REG_TARGET_FRAMES", AuthConfig.DEFAULT_REG_TARGET_FRAMES)
        AuthConfig.HANDS_FREE_ENABLED = prefs.getBoolean("HANDS_FREE_ENABLED", AuthConfig.DEFAULT_HANDS_FREE_ENABLED)
        AuthConfig.HANDS_FREE_CONSECUTIVE_HITS = prefs.getInt("HANDS_FREE_CONSECUTIVE_HITS", AuthConfig.DEFAULT_HANDS_FREE_CONSECUTIVE_HITS)

        Quality.DAY.motionMax = prefs.getFloat("DAY_motionMax", Quality.DAY.motionMax.toFloat()).toDouble()
        Quality.DAY.blurMin = prefs.getFloat("DAY_blurMin", Quality.DAY.blurMin.toFloat()).toDouble()

        Quality.NIGHT.motionMax = prefs.getFloat("NIGHT_motionMax", Quality.NIGHT.motionMax.toFloat()).toDouble()
        Quality.NIGHT.blurMin = prefs.getFloat("NIGHT_blurMin", Quality.NIGHT.blurMin.toFloat()).toDouble()

        // Experiment mode
        AuthConfig.EXPERIMENT_MODE = prefs.getBoolean("EXPERIMENT_MODE", false)
        AuthConfig.EXPERIMENT_ILLUMINATION = prefs.getString("EXPERIMENT_ILLUMINATION", "bright") ?: "bright"
        AuthConfig.EXPERIMENT_DISTANCE = prefs.getString("EXPERIMENT_DISTANCE", "near") ?: "near"
        AuthConfig.EXPERIMENT_FLASH_ENABLED = prefs.getBoolean("EXPERIMENT_FLASH_ENABLED", true)
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("MATCH_THRESHOLD", AuthConfig.MATCH_THRESHOLD)
            .putFloat("YOLO_CONF_THRESHOLD", AuthConfig.YOLO_CONF_THRESHOLD)
            .putBoolean("USE_ML_EMBEDDER", AuthConfig.USE_ML_EMBEDDER)
            .putLong("REG_BURST_MS", AuthConfig.REG_BURST_MS)
            .putInt("REG_TARGET_FRAMES", AuthConfig.REG_TARGET_FRAMES)
            .putBoolean("HANDS_FREE_ENABLED", AuthConfig.HANDS_FREE_ENABLED)
            .putInt("HANDS_FREE_CONSECUTIVE_HITS", AuthConfig.HANDS_FREE_CONSECUTIVE_HITS)
            .putFloat("DAY_motionMax", Quality.DAY.motionMax.toFloat())
            .putFloat("DAY_blurMin", Quality.DAY.blurMin.toFloat())
            .putFloat("NIGHT_motionMax", Quality.NIGHT.motionMax.toFloat())
            .putFloat("NIGHT_blurMin", Quality.NIGHT.blurMin.toFloat())
            // Experiment mode
            .putBoolean("EXPERIMENT_MODE", AuthConfig.EXPERIMENT_MODE)
            .putString("EXPERIMENT_ILLUMINATION", AuthConfig.EXPERIMENT_ILLUMINATION)
            .putString("EXPERIMENT_DISTANCE", AuthConfig.EXPERIMENT_DISTANCE)
            .putBoolean("EXPERIMENT_FLASH_ENABLED", AuthConfig.EXPERIMENT_FLASH_ENABLED)
            .apply()
    }

    fun resetToDefaults(context: Context) {
        AuthConfig.MATCH_THRESHOLD = AuthConfig.DEFAULT_MATCH_THRESHOLD
        AuthConfig.YOLO_CONF_THRESHOLD = AuthConfig.DEFAULT_YOLO_CONF_THRESHOLD
        AuthConfig.USE_ML_EMBEDDER = AuthConfig.DEFAULT_USE_ML_EMBEDDER
        AuthConfig.REG_BURST_MS = AuthConfig.DEFAULT_REG_BURST_MS
        AuthConfig.REG_TARGET_FRAMES = AuthConfig.DEFAULT_REG_TARGET_FRAMES
        AuthConfig.HANDS_FREE_ENABLED = AuthConfig.DEFAULT_HANDS_FREE_ENABLED
        AuthConfig.HANDS_FREE_CONSECUTIVE_HITS = AuthConfig.DEFAULT_HANDS_FREE_CONSECUTIVE_HITS

        Quality.DAY.motionMax = 30.0
        Quality.DAY.blurMin = 10.0

        Quality.NIGHT.motionMax = 30.0
        Quality.NIGHT.blurMin = 10.0

        // Experiment mode resets to off
        AuthConfig.EXPERIMENT_MODE = false
        AuthConfig.EXPERIMENT_ILLUMINATION = "bright"
        AuthConfig.EXPERIMENT_DISTANCE = "near"
        AuthConfig.EXPERIMENT_FLASH_ENABLED = true

        ExperimentStore.clearStudyMetadata(context)
        save(context)
    }
}
