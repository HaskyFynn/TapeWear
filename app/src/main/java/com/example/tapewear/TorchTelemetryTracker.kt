package com.example.tapewear

import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

/**
 * Tracks torch callback acknowledgements so metrics can distinguish
 * between "torch was requested" and "torch actually reported on".
 */
class TorchTelemetryTracker(
    private val cameraManager: CameraManager
) {
    @Volatile
    private var trackedCameraId: String? = null

    @Volatile
    private var flashHardwareAvailable = false

    @Volatile
    private var torchLastAckState = "unknown"

    @Volatile
    private var torchAckedOnDuringAttempt = false

    private val callbackHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != trackedCameraId) return
            torchLastAckState = if (enabled) "on" else "off"
            if (enabled) {
                torchAckedOnDuringAttempt = true
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != trackedCameraId) return
            torchLastAckState = "unavailable"
        }
    }

    fun configure(cameraId: String?, flashAvailable: Boolean) {
        trackedCameraId = cameraId
        flashHardwareAvailable = flashAvailable
        torchLastAckState = if (flashAvailable) "unknown" else "unsupported"
        torchAckedOnDuringAttempt = false
    }

    fun register() {
        if (registered) return
        cameraManager.registerTorchCallback(torchCallback, callbackHandler)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { cameraManager.unregisterTorchCallback(torchCallback) }
        registered = false
    }

    fun resetAttempt() {
        torchAckedOnDuringAttempt = false
        if (flashHardwareAvailable && torchLastAckState == "unsupported") {
            torchLastAckState = "unknown"
        }
    }

    fun markCommandError() {
        torchLastAckState = "command_error"
    }

    fun snapshot(): MetricsLogger.FlashTelemetry {
        return MetricsLogger.FlashTelemetry(
            flashHardwareAvailable = flashHardwareAvailable,
            torchLastAckState = torchLastAckState,
            torchAckedOnDuringAttempt = torchAckedOnDuringAttempt
        )
    }
}
