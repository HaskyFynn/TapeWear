package com.example.tapewear

import android.content.Context

object SettingsStore {
    private const val PREFS_NAME = "tapewear_settings"

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        AuthConfig.MATCH_THRESHOLD = prefs.getFloat("MATCH_THRESHOLD", AuthConfig.DEFAULT_MATCH_THRESHOLD)
        AuthConfig.YOLO_CONF_THRESHOLD = prefs.getFloat("YOLO_CONF_THRESHOLD", AuthConfig.DEFAULT_YOLO_CONF_THRESHOLD)
        AuthConfig.USE_ML_EMBEDDER = prefs.getBoolean("USE_ML_EMBEDDER", AuthConfig.DEFAULT_USE_ML_EMBEDDER)
        AuthConfig.REG_BURST_MS = prefs.getLong("REG_BURST_MS", AuthConfig.DEFAULT_REG_BURST_MS)
        AuthConfig.REG_TARGET_FRAMES = prefs.getInt("REG_TARGET_FRAMES", AuthConfig.DEFAULT_REG_TARGET_FRAMES)

        Quality.DAY.motionMax = prefs.getFloat("DAY_motionMax", Quality.DAY.motionMax.toFloat()).toDouble()
        Quality.DAY.blurMin = prefs.getFloat("DAY_blurMin", Quality.DAY.blurMin.toFloat()).toDouble()

        Quality.NIGHT.motionMax = prefs.getFloat("NIGHT_motionMax", Quality.NIGHT.motionMax.toFloat()).toDouble()
        Quality.NIGHT.blurMin = prefs.getFloat("NIGHT_blurMin", Quality.NIGHT.blurMin.toFloat()).toDouble()
    }

    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("MATCH_THRESHOLD", AuthConfig.MATCH_THRESHOLD)
            .putFloat("YOLO_CONF_THRESHOLD", AuthConfig.YOLO_CONF_THRESHOLD)
            .putBoolean("USE_ML_EMBEDDER", AuthConfig.USE_ML_EMBEDDER)
            .putLong("REG_BURST_MS", AuthConfig.REG_BURST_MS)
            .putInt("REG_TARGET_FRAMES", AuthConfig.REG_TARGET_FRAMES)
            .putFloat("DAY_motionMax", Quality.DAY.motionMax.toFloat())
            .putFloat("DAY_blurMin", Quality.DAY.blurMin.toFloat())
            .putFloat("NIGHT_motionMax", Quality.NIGHT.motionMax.toFloat())
            .putFloat("NIGHT_blurMin", Quality.NIGHT.blurMin.toFloat())
            .apply()
    }

    fun resetToDefaults(context: Context) {
        AuthConfig.MATCH_THRESHOLD = AuthConfig.DEFAULT_MATCH_THRESHOLD
        AuthConfig.YOLO_CONF_THRESHOLD = AuthConfig.DEFAULT_YOLO_CONF_THRESHOLD
        AuthConfig.USE_ML_EMBEDDER = AuthConfig.DEFAULT_USE_ML_EMBEDDER
        AuthConfig.REG_BURST_MS = AuthConfig.DEFAULT_REG_BURST_MS
        AuthConfig.REG_TARGET_FRAMES = AuthConfig.DEFAULT_REG_TARGET_FRAMES

        Quality.DAY.motionMax = 30.0
        Quality.DAY.blurMin = 10.0

        Quality.NIGHT.motionMax = 30.0
        Quality.NIGHT.blurMin = 10.0

        save(context)
    }
}
