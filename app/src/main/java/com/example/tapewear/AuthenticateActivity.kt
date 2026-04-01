package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.*
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AuthenticateActivity : AppCompatActivity() {

    // Demo video (used only when demoMode == true)
    private val demoAssetName = "1.mp4"
    private var videoSource: VideoFrameSource? = null
    private var lastDemoFrame: Bitmap? = null
    @Volatile private var demoUiActive = false
    private var videoFrameStepMs: Long = 100L
    private var currentVideoTimeMs: Long = 0L

    // Manual demo flag (set true to force video-based demo)
    private var demoMode = AuthConfig.DEMO_MODE
    private var hasFlash = false

    // Views
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var demoImage: ImageView

    private lateinit var btnCapture: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLine: TextView

    private lateinit var spinnerPattern: Spinner
    private lateinit var flashCheck: CheckBox

    private lateinit var verdictText: TextView
    private lateinit var confidenceText: TextView
    private lateinit var resultCard: LinearLayout

    private lateinit var stagesText: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyText: TextView
    private lateinit var modeIndicator: TextView
    private lateinit var runtimeThresholds: TextView
    private val authHistory = ArrayList<String>()

    // Camera2
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var previewSize = Size(640, 480)
    private var fpsRanges: Array<Range<Int>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reqBuilder: CaptureRequest.Builder? = null

    // Background thread for processing
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else Toast.makeText(this, getString(R.string.err_camera_perm), Toast.LENGTH_SHORT).show()
    }

    // Timing for auth burst
    private var authSessionStartMs: Long = 0L
    private var authOverhead: MetricsLogger.OverheadSnapshot? = null
    private val liveDetectRunning = AtomicBoolean(false)
    private val authRunning = AtomicBoolean(false)
    private val modelInitRunning = AtomicBoolean(false)
    private var lastLiveDetectMs: Long = 0L
    private var liveDetectionEnabledAtMs: Long = 0L
    private var liveDetectBoostUntilMs: Long = 0L
    private val liveDetectStartDelayMs = 1200L
    private val liveDetectIntervalIdleMs = 3000L
    private val liveDetectIntervalIntentMs = 700L
    private val liveDetectIntervalBoostMs = 220L
    private val liveDetectBoostWindowMs = 3000L
    private var liveDetectGapMs = liveDetectIntervalIdleMs
    private var intentActive = false
    private var intentHitStreak = 0
    private var intentMissStreak = 0
    private val intentHitsToActivate = 2
    private val intentMissesToDeactivate = 3
    private var demoLoopEnabled = false
    private var demoLoopRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        setContentView(R.layout.activity_authenticate)

        textureView     = findViewById(R.id.textureViewAuth)
        overlayView     = findViewById(R.id.overlayViewAuth)
        demoImage       = findViewById(R.id.demoImageAuth)

        btnCapture      = findViewById(R.id.btnCaptureAuth)
        progressBar     = findViewById(R.id.progressBarAuth)
        progressLine    = findViewById(R.id.progressLineAuth)

        spinnerPattern  = findViewById(R.id.patternSpinner)
        flashCheck      = findViewById(R.id.flashCheck)

        verdictText     = findViewById(R.id.verdictText)
        confidenceText  = findViewById(R.id.confidenceText)
        resultCard      = findViewById(R.id.resultCard)
        stagesText      = findViewById(R.id.stagesText)
        historyContainer= findViewById(R.id.historyContainer)
        historyText     = findViewById(R.id.historyText)
        runtimeThresholds = findViewById(R.id.runtimeThresholdsAuth)
        val slotStatusText = findViewById<TextView>(R.id.slotStatusTextAuth)

        modeIndicator = findViewById(R.id.modeIndicatorAuth)
        refreshRuntimeConfigUi()

        verdictText.textSize = 24f
        verdictText.setTypeface(verdictText.typeface, Typeface.BOLD)
        verdictText.textAlignment = View.TEXT_ALIGNMENT_CENTER

        confidenceText.textSize = 18f
        confidenceText.textAlignment = View.TEXT_ALIGNMENT_CENTER

        // Slot spinner 1..50
        val slots = (1..50).map { getString(R.string.pattern_n, it) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_large, slots)
        adapter.setDropDownViewResource(R.layout.spinner_item_large)
        spinnerPattern.adapter = adapter

        // Pre-select the active slot (last used in registration)
        val initialSlot = (ModelManager.getActiveSlot() - 1).coerceIn(0, 49)
        spinnerPattern.setSelection(initialSlot)

        spinnerPattern.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val slot = position + 1
                ModelManager.setActiveSlot(slot)
                val present = ModelManager.hasModel(this@AuthenticateActivity, slot)
                if (present) {
                    slotStatusText.text = "✓ Enrolled"
                    slotStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    slotStatusText.text = "— Empty"
                    slotStatusText.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                }
                btnCapture.isEnabled = present && isYoloReady()
                resultCard.visibility = View.GONE
                verdictText.text = if (present)
                    getString(R.string.auth_model_present_fmt, slot)
                else
                    getString(R.string.auth_model_missing_fmt, slot)
                confidenceText.text = ""
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnCapture.setOnClickListener {
            val slot = ModelManager.getActiveSlot()
            if (!ModelManager.hasModel(this, slot)) {
                Toast.makeText(this, getString(R.string.auth_no_model), Toast.LENGTH_SHORT).show()
            } else {
                boostLiveDetectionWindow()
                startAuthBurst()
            }
        }

        if (!demoMode) {
            // Normal camera path
            val ids: Array<String> = try { cameraManager.cameraIdList } catch (_: Exception) { emptyArray() }
            if (ids.isEmpty()) {
                switchToDemo(getString(R.string.cam_none))
                return
            }

            val back = ids.firstOrNull {
                try {
                    cameraManager.getCameraCharacteristics(it)
                        .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } catch (_: Exception) { false }
            }
            cameraId = back ?: ids.firstOrNull()
            if (cameraId == null) {
                switchToDemo(getString(R.string.cam_choose_fail))
                return
            }

            try {
                val ch = cameraManager.getCameraCharacteristics(cameraId!!)
                hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            } catch (e: Exception) {
                switchToDemo(getString(R.string.cam_chars_fail, e.message ?: ""))
                return
            }

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (!demoMode) startWhenReady()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    maybeRunLiveDetectionFromPreview()
                }
            }
        } else {
            // Pure demo: camera UI hidden, detector can still be YOLO if present
            Log.d("TapeWear_Auth", "AuthenticateActivity in DEMO mode using asset video")
            textureView.visibility = View.GONE
            demoImage.visibility = View.VISIBLE
            overlayView.statusText = getString(R.string.auth_hint_align)
            if (ModelManager.detector == null) {
                Log.w("TapeWear_Auth", "DEMO mode active but YOLO detector is not initialized.")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SettingsStore.load(this)
        ensureModelsInitializedIfNeeded()
        refreshRuntimeConfigUi()
        demoUiActive = true
        liveDetectionEnabledAtMs = SystemClock.elapsedRealtime() + liveDetectStartDelayMs
        lastLiveDetectMs = 0L
        resetLiveDetectionPacing()

        // Background processing thread
        backgroundThread = HandlerThread("ImageProcessorAuth").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (demoMode) {
            try {
                videoSource = VideoFrameSource(this, demoAssetName)
                currentVideoTimeMs = 0L
                startDemoLiveDetectionLoop()
            } catch (e: Exception) {
                videoSource = null
                Log.e("TapeWear_Auth", "DEMO video open failed: ${e.message}")
                overlayView.statusText = "Demo video missing"
                Toast.makeText(this, "Demo video not found", Toast.LENGTH_SHORT).show()
            }
        }

        if (!demoMode && textureView.isAvailable) {
            startWhenReady()
        }

        if (demoMode) {
            primeDemoFrameAsync()
        }
    }

    override fun onPause() {
        super.onPause()
        demoUiActive = false
        stopDemoLiveDetectionLoop()

        // Release demo frames / video if used
        releaseDemoImage()

        val toClose = videoSource
        videoSource = null
        Thread {
            try {
                toClose?.close()
            } catch (e: Exception) {
                Log.w("TapeWear_Auth", "Failed to close demo video source: ${e.message}")
            }
        }.start()

        if (!demoMode) {
            setTorch(false)
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null
        }

        shutdownBackgroundThreadAsync()
        overlayView.clearLiveDetections()
        liveDetectRunning.set(false)
        authRunning.set(false)
        resetLiveDetectionPacing()
    }

    private fun shutdownBackgroundThreadAsync() {
        val thread = backgroundThread
        backgroundThread = null
        backgroundHandler = null
        if (thread == null) return

        thread.quitSafely()
        Thread {
            try {
                thread.join(1500L)
                if (thread.isAlive) {
                    thread.quit()
                }
            } catch (e: InterruptedException) {
                Log.w("TapeWear_Auth", "Interrupted while stopping background thread", e)
            }
        }.start()
    }

    // --- Camera bring-up (normal mode only) ---
    private fun startWhenReady() {
        if (demoMode) return
        if (!hasCamPerm()) {
            perms.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (!textureView.isAvailable) return
        if (cameraId == null) {
            switchToDemo(getString(R.string.cam_id_missing))
            return
        }
        openCamera()
    }

    private fun hasCamPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        val id = cameraId ?: run {
            switchToDemo(getString(R.string.cam_id_missing))
            return
        }
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; startPreview() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    switchToDemo(getString(R.string.cam_error, error))
                }
            }, backgroundHandler)
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.err_perm_missing), Toast.LENGTH_SHORT).show()
            switchToDemo(getString(R.string.perm_denied))
        } catch (e: Exception) {
            switchToDemo(getString(R.string.cam_open_fail, e.message ?: ""))
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        fpsRanges?.let { ranges ->
            val best = ranges.firstOrNull { it.contains(30) } ?: ranges.firstOrNull()
            best?.let { reqBuilder?.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(reqBuilder!!.build(), null, backgroundHandler)
                    overlayView.statusText = getString(R.string.auth_hint_align)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Toast.makeText(this@AuthenticateActivity, getString(R.string.err_preview), Toast.LENGTH_SHORT).show()
                    switchToDemo(getString(R.string.preview_fail))
                }
            },
            backgroundHandler
        )
    }

    private fun refreshRuntimeConfigUi() {
        val detName = if (isYoloReady()) "YOLO" else "YOLO_MISSING"
        modeIndicator.text = if (AuthConfig.USE_ML_EMBEDDER) "ML Pipeline" else "CV Pipeline"
        runtimeThresholds.text = String.format(
            Locale.US,
            "Match %.2f | YOLO %.2f | Frames %d | Burst %dms | Detector: %s",
            AuthConfig.MATCH_THRESHOLD,
            AuthConfig.YOLO_CONF_THRESHOLD,
            AuthConfig.REG_TARGET_FRAMES,
            AuthConfig.REG_BURST_MS,
            detName
        )
        if (::btnCapture.isInitialized && ::spinnerPattern.isInitialized) {
            val slot = (spinnerPattern.selectedItemPosition + 1).coerceAtLeast(1)
            btnCapture.isEnabled = ModelManager.hasModel(this, slot) && isYoloReady()
        }
    }

    private fun isYoloReady(): Boolean = ModelManager.detector is ModelManager.TFLiteYoloDetector

    private fun modelThreads(): Int {
        val fp = android.os.Build.FINGERPRINT.lowercase(Locale.US)
        val model = android.os.Build.MODEL.lowercase(Locale.US)
        val product = android.os.Build.PRODUCT.lowercase(Locale.US)
        val isEmulator = fp.contains("generic") ||
            fp.contains("emulator") ||
            model.contains("emulator") ||
            model.contains("sdk") ||
            product.contains("sdk")
        return if (isEmulator) 1 else 2
    }

    private fun ensureModelsInitializedIfNeeded() {
        if (isYoloReady() && ModelManager.mlEmbedder != null) return
        if (!modelInitRunning.compareAndSet(false, true)) return

        Thread {
            val threads = modelThreads()
            val t0 = SystemClock.elapsedRealtime()
            try {
                synchronized(ModelManager) {
                    if (ModelManager.detector == null) {
                        ModelManager.detector = ModelManager.TFLiteYoloDetector(
                            context = applicationContext,
                            assetName = "best_float32.tflite",
                            numThreads = threads
                        )
                    }
                    if (ModelManager.mlEmbedder == null) {
                        ModelManager.mlEmbedder = TfLiteEmbedder(
                            context = applicationContext,
                            assetName = "tapewear_embedder.tflite",
                            numThreads = threads
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TapeWear_Auth", "Model init in Authenticate failed: ${e.message}")
            } finally {
                val dt = SystemClock.elapsedRealtime() - t0
                Log.i("TapeWear_Auth", "Model warmup complete in ${dt}ms (threads=$threads)")
                modelInitRunning.set(false)
                mainHandler.post { refreshRuntimeConfigUi() }
            }
        }.start()
    }

    private fun currentLiveDetectIntervalMs(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val base = if (intentActive) liveDetectIntervalIntentMs else liveDetectIntervalIdleMs
        return if (nowMs < liveDetectBoostUntilMs) minOf(liveDetectIntervalBoostMs, base) else base
    }

    private fun boostLiveDetectionWindow() {
        liveDetectBoostUntilMs = SystemClock.elapsedRealtime() + liveDetectBoostWindowMs
        liveDetectGapMs = liveDetectIntervalBoostMs
    }

    private fun resetLiveDetectionPacing() {
        liveDetectBoostUntilMs = 0L
        liveDetectGapMs = liveDetectIntervalIdleMs
        intentActive = false
        intentHitStreak = 0
        intentMissStreak = 0
    }

    private fun updateLiveDetectGap(lastInferenceMs: Long) {
        val base = currentLiveDetectIntervalMs()
        liveDetectGapMs = when {
            lastInferenceMs >= 4000L -> (lastInferenceMs * 2L).coerceAtMost(12000L)
            lastInferenceMs >= 2000L -> (lastInferenceMs + 1200L).coerceAtMost(8000L)
            else -> maxOf(base, lastInferenceMs + 180L)
        }
    }

    private fun onLiveDetectionOutcome(outcome: FrameDetectionOutcome) {
        val seenInGuide = outcome.insideOverlayCount > 0
        if (seenInGuide) {
            intentHitStreak++
            intentMissStreak = 0
            if (intentHitStreak >= intentHitsToActivate) {
                intentActive = true
            }
        } else {
            intentMissStreak++
            intentHitStreak = 0
            if (intentMissStreak >= intentMissesToDeactivate) {
                intentActive = false
            }
        }

        if (authRunning.get()) return
        mainHandler.post {
            if (authRunning.get()) return@post
            overlayView.statusText = when {
                seenInGuide -> "Pattern seen in guide. Tap Authenticate"
                intentActive -> "Hold pattern in guide"
                else -> getString(R.string.auth_hint_align)
            }
        }
    }

    private fun releaseDemoImage() {
        if (::demoImage.isInitialized) {
            demoImage.setImageDrawable(null)
        }
        lastDemoFrame?.let {
            if (!it.isRecycled) it.recycle()
        }
        lastDemoFrame = null
    }

    // --- Auth burst ---
    private fun startAuthBurst() {
        if (!isYoloReady()) {
            authRunning.set(false)
            overlayView.statusText = "YOLO unavailable"
            Toast.makeText(this, "YOLO detector is required", Toast.LENGTH_SHORT).show()
            return
        }
        authRunning.set(true)
        btnCapture.isEnabled = false
        resultCard.visibility = View.GONE
        overlayView.statusText = getString(R.string.auth_holdsteady)
        progressLine.text = getString(R.string.auth_preparing)
        progressLine.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        val night = flashCheck.isChecked
        if (!demoMode && night && hasFlash) setTorch(true)

        val slot = ModelManager.getActiveSlot()

        Log.d("TapeWear_Auth", "Starting auth burst (slot=$slot, night=$night, demo=$demoMode)")

        authOverhead = MetricsLogger.OverheadSnapshot()

        val settleMs = 0L // Removed 100ms artificial delay to improve latency
        val settleStart = SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            val settleActual = SystemClock.elapsedRealtime() - settleStart
            lockAeAwb(true)
            runAuthBurst(night, settleActual)
        }, settleMs)
    }

    private fun runAuthBurst(night: Boolean, settleActualMs: Long) {
        // Single-frame capture with YOLO-first pipeline.
        val bg = backgroundHandler
        if (bg == null) {
            authRunning.set(false)
            hideProgress()
            btnCapture.isEnabled = ModelManager.hasModel(this, ModelManager.getActiveSlot())
            overlayView.statusText = "Processing unavailable"
            resetLiveDetectionPacing()
            return
        }
        bg.post {
            // [LATENCY FIX] Start clock only when the background thread actually executes, skipping button-tap lag
            authSessionStartMs = SystemClock.elapsedRealtime()
            
            val frames = ArrayList<Bitmap>()
            var preScoreHint: String? = null

            // Stage: capture
            val tCap0 = SystemClock.elapsedRealtime()
            val sample = snapshotCurrent()
            val tCap1 = SystemClock.elapsedRealtime()
            val captureMs = tCap1 - tCap0
            if (sample == null) {
                mainHandler.post {
                    hideProgress()
                    overlayView.statusText = "Frame capture failed"
                    verdictText.text = "Verdict: ERROR"
                    confidenceText.text = ""
                    btnCapture.isEnabled = ModelManager.hasModel(this, ModelManager.getActiveSlot()) && isYoloReady()
                    resetLiveDetectionPacing()
                    authRunning.set(false)
                }
                return@post
            }

            // Stage: detect + quality gate (quality only on YOLO-positive frames)
            val tQual0 = SystemClock.elapsedRealtime()
            val detOutcome = detectFrame(sample, updateOverlay = true)
            if (!detOutcome.hasDetection) {
                preScoreHint = "No YOLO detection above ${"%.2f".format(Locale.US, AuthConfig.YOLO_CONF_THRESHOLD)}"
                sample.recycle()
            } else {
                val luma = ImageUtils.meanLuma(sample)
                val blur = ImageUtils.blurMetric(sample)
                val assessment = Quality.assess(luma, blur, motion = 0.0, night = night)
                if (assessment.pass) {
                    frames.add(sample)
                } else {
                    preScoreHint = assessment.hint
                    sample.recycle()
                }
            }
            val tQual1 = SystemClock.elapsedRealtime()
            val qualityMs = tQual1 - tQual0

            mainHandler.post {
                progressBar.progress = 100
                progressLine.text = getString(R.string.auth_authenticating_fmt, 100)
            }

            Log.d("TapeWear_Auth", "Auth burst finished, collected ${frames.size} frames.")
            finishAuth(frames, night, settleActualMs, captureMs, qualityMs, preScoreHint)
        }
    }

    private fun finishAuth(
        frames: MutableList<Bitmap>,
        night: Boolean,
        settleMs: Long,
        captureMs: Long,
        qualityMs: Long,
        preScoreHint: String?
    ) {
        Log.d("TapeWear_Auth", "finishAuth: processing ${frames.size} frames")
        lockAeAwb(false)
        if (!demoMode && night) setTorch(false)

        val bg = backgroundHandler
        if (bg == null) {
            mainHandler.post {
                frames.forEach { it.recycle() }
                hideProgress()
                resetLiveDetectionPacing()
                authRunning.set(false)
                btnCapture.isEnabled = ModelManager.hasModel(this, ModelManager.getActiveSlot()) && isYoloReady()
            }
            return
        }

        val work = Runnable {
            try {
                val framesToScore = if (frames.size <= 5) {
                    frames
                } else {
                    frames.map { it to ImageUtils.blurMetric(it) }
                        .sortedByDescending { it.second }
                        .take(5)
                        .map { it.first }
                }
                Log.d(
                    "TapeWear_Auth",
                    "Filtered to ${framesToScore.size} frames for scoring (totalFrames=${frames.size})"
                )

                var scoringFailed = false
                val scored = try {
                    ModelManager.scoreFromBitmaps(
                        context = this,
                        frames = framesToScore,
                        take = framesToScore.size.coerceAtLeast(1),
                        slot = ModelManager.getActiveSlot()
                    )
                } catch (e: Throwable) {
                    scoringFailed = true
                    Log.e("TapeWear_Auth", "Scoring failed: ${e.message}", e)
                    ModelManager.ScoredResult(
                        verdict = ModelManager.Verdict(-1f, false),
                        scoredFrames = 0,
                        detectMs = 0,
                        embedMs = 0,
                        cosineMs = 0
                    )
                }

                val verdict = scored.verdict
                val finalSim: Float
                val usedN: Int
                val isMatch: Boolean

                if (verdict.similarity >= 0f) {
                    finalSim = verdict.similarity.coerceIn(0f, 1f)
                    isMatch = verdict.isMatch
                    usedN = scored.scoredFrames
                } else {
                    finalSim = 0f
                    isMatch = false
                    usedN = 0
                    Log.w("TapeWear_Auth", "No valid YOLO-scored frame; returning no-pattern result")
                }

                val totalFrames = frames.size
                val now = SystemClock.elapsedRealtime()
                val burstMs = if (authSessionStartMs > 0L) now - authSessionStartMs else 0L
                val slot = ModelManager.getActiveSlot()

                if (scoringFailed) {
                    mainHandler.post {
                        frames.forEach { it.recycle() }
                        hideProgress()
                        overlayView.statusText = "Authentication error"
                        verdictText.text = "Verdict: ERROR"
                        confidenceText.text = ""
                        btnCapture.isEnabled = ModelManager.hasModel(this, slot) && isYoloReady()
                        resetLiveDetectionPacing()
                        authRunning.set(false)
                    }
                    return@Runnable
                }

                Log.d(
                    "TapeWear_Auth",
                    "Auth verdict: match=$isMatch, similarity=%.3f, usedN=$usedN, burstMs=$burstMs"
                        .format(finalSim)
                )

                MetricsLogger.logAuth(
                    ctx = this,
                    slot = slot,
                    similarity = finalSim,
                    isMatch = isMatch,
                    burstMs = burstMs,
                    framesCollected = totalFrames,
                    framesScored = usedN,
                    nightMode = night,
                    demoMode = demoMode,
                    overhead = authOverhead
                )

                MetricsLogger.logAuthStages(
                    ctx = this,
                    slot = slot,
                    settleMs = settleMs,
                    captureMs = captureMs,
                    qualityMs = qualityMs,
                    detectMs = scored.detectMs,
                    embedMs = scored.embedMs,
                    cosineMs = scored.cosineMs,
                    totalMs = burstMs,
                    nightMode = night
                )

                var authHint: String? = null
                if (!isMatch && frames.isNotEmpty()) {
                    val f = frames[0]
                    val luma = ImageUtils.meanLuma(f)
                    val blur = ImageUtils.blurMetric(f)
                    val assessment = Quality.assess(luma, blur, 0.0, night)
                    if (!assessment.isIdeal) authHint = assessment.hint
                }
                if (!isMatch && authHint == null && preScoreHint != null) {
                    authHint = preScoreHint
                }

                mainHandler.post {
                    frames.forEach { it.recycle() }
                    hideProgress()

                    confidenceText.text = getString(
                        R.string.auth_similarity_fmt,
                        (finalSim * 100).toInt(),
                        (AuthConfig.MATCH_THRESHOLD * 100).toInt(),
                        ModelManager.getActiveSlot()
                    )

                    if (isMatch) {
                        verdictText.text = getString(R.string.auth_verdict_match)
                        overlayView.statusText = getString(R.string.auth_unlocked)
                        resultCard.visibility = View.VISIBLE
                        resultCard.scaleX = 0.90f
                        resultCard.scaleY = 0.90f
                        resultCard.alpha = 0f
                        resultCard.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220)
                            .start()
                    } else {
                        verdictText.text = if (usedN == 0) "Verdict: NO PATTERN DETECTED" else getString(R.string.auth_verdict_nomatch)
                        overlayView.statusText = if (usedN == 0) "No pattern detected" else (authHint ?: getString(R.string.done))
                        resultCard.visibility = View.VISIBLE
                        resultCard.alpha = 0f
                        resultCard.animate()
                            .alpha(1f)
                            .setDuration(180)
                            .start()
                    }

                    btnCapture.isEnabled = ModelManager.hasModel(this, slot) && isYoloReady()
                    stagesText.text =
                        "settle: ${settleMs}ms | capture: ${captureMs}ms | quality: ${qualityMs}ms\n" +
                        "detect: ${scored.detectMs}ms | embed: ${scored.embedMs}ms | cosine: ${scored.cosineMs}ms | total: ${burstMs}ms"

                    val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    val timeStr = timeFormat.format(java.util.Date())
                    val matchStr = if (isMatch) "MATCH ${(finalSim * 100).toInt()}%" else "NO_MATCH ${(finalSim * 100).toInt()}%"
                    authHistory.add(0, "$timeStr - Slot $slot: $matchStr")
                    if (authHistory.size > 3) authHistory.removeAt(authHistory.size - 1)
                    historyText.text = authHistory.joinToString("\n")
                    historyContainer.visibility = View.VISIBLE
                    resetLiveDetectionPacing()
                    authRunning.set(false)
                }
            } catch (e: Throwable) {
                Log.e("TapeWear_Auth", "finishAuth failed: ${e.message}", e)
                mainHandler.post {
                    frames.forEach { it.recycle() }
                    hideProgress()
                    overlayView.statusText = "Authentication error"
                    btnCapture.isEnabled = ModelManager.hasModel(this, ModelManager.getActiveSlot()) && isYoloReady()
                    resetLiveDetectionPacing()
                    authRunning.set(false)
                }
            }
        }

        if (Looper.myLooper() == bg.looper) {
            work.run()
        } else {
            bg.post(work)
        }
    }
    // --- Locks / Torch / Snapshot / Metrics ---
    private data class FrameDetectionOutcome(
        val detections: List<ModelManager.Detection>,
        val hasDetection: Boolean,
        val insideOverlayCount: Int
    )

    private fun maybeRunLiveDetectionFromPreview() {
        val now = SystemClock.elapsedRealtime()
        if (authRunning.get()) return
        if (now < liveDetectionEnabledAtMs) return
        if (!isYoloReady()) {
            overlayView.clearLiveDetections()
            return
        }
        if (now - lastLiveDetectMs < liveDetectGapMs) return
        if (!liveDetectRunning.compareAndSet(false, true)) return

        val frame = try {
            textureView.getBitmap(640, 640)
        } catch (_: Exception) {
            null
        }

        if (frame == null) {
            liveDetectRunning.set(false)
            return
        }
        lastLiveDetectMs = now

        backgroundHandler?.post {
            try {
                val outcome = detectFrame(frame, updateOverlay = true)
                onLiveDetectionOutcome(outcome)
            } catch (e: Exception) {
                Log.w("TapeWear_Auth", "Live YOLO failed: ${e.message}")
            } finally {
                val inferMs = SystemClock.elapsedRealtime() - now
                updateLiveDetectGap(inferMs)
                frame.recycle()
                liveDetectRunning.set(false)
            }
        } ?: run {
            frame.recycle()
            liveDetectRunning.set(false)
        }
    }

    private fun startDemoLiveDetectionLoop() {
        if (!demoMode) return
        if (demoLoopEnabled) return
        demoLoopEnabled = true
        liveDetectionEnabledAtMs = SystemClock.elapsedRealtime() + liveDetectStartDelayMs
        lastLiveDetectMs = 0L

        val loop = object : Runnable {
            override fun run() {
                if (!demoLoopEnabled || !demoMode) return

                val now = SystemClock.elapsedRealtime()
                if (now < liveDetectionEnabledAtMs) {
                    val waitMs = (liveDetectionEnabledAtMs - now).coerceAtMost(250L)
                    mainHandler.postDelayed(this, waitMs)
                    return
                }

                if (!isYoloReady()) {
                    overlayView.clearLiveDetections()
                    mainHandler.postDelayed(this, liveDetectGapMs)
                    return
                }

                if (!authRunning.get() && liveDetectRunning.compareAndSet(false, true)) {
                    val bg = backgroundHandler
                    if (bg != null) {
                        bg.post {
                            val detectStart = SystemClock.elapsedRealtime()
                            try {
                                val frame = snapshotCurrent()
                                if (frame != null) {
                                    val outcome = detectFrame(frame, updateOverlay = true)
                                    onLiveDetectionOutcome(outcome)
                                    frame.recycle()
                                }
                            } catch (e: Exception) {
                                Log.w("TapeWear_Auth", "Demo live YOLO failed: ${e.message}")
                            } finally {
                                val inferMs = SystemClock.elapsedRealtime() - detectStart
                                updateLiveDetectGap(inferMs)
                                liveDetectRunning.set(false)
                            }
                        }
                    } else {
                        liveDetectRunning.set(false)
                    }
                }

                mainHandler.postDelayed(this, liveDetectGapMs)
            }
        }

        demoLoopRunnable = loop
        mainHandler.postDelayed(loop, liveDetectStartDelayMs)
    }

    private fun stopDemoLiveDetectionLoop() {
        demoLoopEnabled = false
        demoLoopRunnable?.let { mainHandler.removeCallbacks(it) }
        demoLoopRunnable = null
    }

    private fun detectFrame(frame: Bitmap, updateOverlay: Boolean): FrameDetectionOutcome {
        val det = ModelManager.detector
        if (det !is ModelManager.TFLiteYoloDetector) {
            if (updateOverlay) {
                mainHandler.post { overlayView.clearLiveDetections() }
            }
            return FrameDetectionOutcome(emptyList(), false, 0)
        }
        val frameW = frame.width
        val frameH = frame.height
        val detections = det.detect(frame)
        val mapped = detections.map {
            OverlayView.LiveDetection(
                box = mapFrameRectToOverlay(it.box, frameW, frameH),
                score = it.score
            )
        }
        val framing = overlayView.getFramingBox()
        val insideOverlayCount = mapped.count { d ->
            framing.contains(d.box.centerX(), d.box.centerY())
        }

        if (updateOverlay) {
            mainHandler.post {
                overlayView.setLiveDetections(mapped)
            }
        }

        return FrameDetectionOutcome(
            detections = detections,
            hasDetection = detections.isNotEmpty(),
            insideOverlayCount = insideOverlayCount
        )
    }

    private fun primeDemoFrameAsync() {
        if (!demoMode) return
        val bg = backgroundHandler ?: return
        val vs = videoSource ?: return
        bg.post {
            try {
                val raw = vs.frameAt(currentVideoTimeMs) ?: return@post
                val preview = raw.scale(640, 640, filter = true)
                raw.recycle()
                mainHandler.post {
                    if (!demoUiActive || isDestroyed || isFinishing) {
                        if (!preview.isRecycled) preview.recycle()
                        return@post
                    }
                    lastDemoFrame?.let {
                        if (!it.isRecycled) it.recycle()
                    }
                    lastDemoFrame = preview
                    demoImage.setImageBitmap(preview)
                }
            } catch (e: Exception) {
                Log.w("TapeWear_Auth", "Failed to prime demo frame: ${e.message}")
            }
        }
    }

    private fun mapFrameRectToOverlay(box: RectF, frameW: Int, frameH: Int): RectF {
        val vw = overlayView.width.coerceAtLeast(1).toFloat()
        val vh = overlayView.height.coerceAtLeast(1).toFloat()
        val sx = vw / frameW.toFloat()
        val sy = vh / frameH.toFloat()
        return RectF(
            box.left * sx,
            box.top * sy,
            box.right * sx,
            box.bottom * sy
        )
    }

    private fun setTorch(on: Boolean) {
        if (!hasFlash) return
        try { cameraManager.setTorchMode(cameraId ?: return, on) } catch (_: Exception) {}
    }

    private fun lockAeAwb(lock: Boolean) {
        val s = session ?: return
        val b = reqBuilder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, lock)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
            s.setRepeatingRequest(b.build(), null, mainHandler)
        } catch (_: Exception) {}
    }

    private fun snapshotCurrent(): Bitmap? = try {
        if (demoMode) {
            // Lazy-open demo video the first time we need a frame
            val vs = videoSource ?: run {
                try {
                    val src = VideoFrameSource(this, demoAssetName)
                    videoSource = src
                    currentVideoTimeMs = 0L
                    src
                } catch (e: Exception) {
                    Log.e("TapeWear_Auth", "Failed to open demo video: ${e.message}")
                    return null
                }
            }

            val raw = vs.frameAt(currentVideoTimeMs) ?: return null
            currentVideoTimeMs += videoFrameStepMs

            // Prevent huge bitmaps from triggering slow GCs
            val scaledPipeline = raw.scale(640, 640, filter = true)
            val scaledUi = scaledPipeline.copy(Bitmap.Config.ARGB_8888, false)
            raw.recycle() // Free native memory immediately

            mainHandler.post {
                if (!demoUiActive || isDestroyed || isFinishing) {
                    if (!scaledUi.isRecycled) scaledUi.recycle()
                    return@post
                }
                lastDemoFrame?.let {
                    if (!it.isRecycled) it.recycle()
                }
                lastDemoFrame = scaledUi
                demoImage.setImageBitmap(scaledUi)
            }

            scaledPipeline
        } else {
            textureView.getBitmap(640, 640)
        }
    } catch (e: Exception) {
        Log.e("TapeWear", "snapshotCurrent failed: ${e.message}")
        null
    }


    private fun hideProgress() {
        progressLine.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    // DEMO fallback (camera failure or manual)
    private fun switchToDemo(reason: String) {
        demoMode = true
        Log.w("AuthenticateActivity", "DEMO fallback: $reason")

        demoImage.visibility = View.VISIBLE
        textureView.visibility = View.GONE
        overlayView.statusText = getString(R.string.auth_hint_align)

        try { setTorch(false) } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        // Keep detector state as-is; no overlay fallback for research runs.
        if (backgroundHandler != null) {
            if (videoSource == null) {
                try {
                    videoSource = VideoFrameSource(this, demoAssetName)
                    currentVideoTimeMs = 0L
                } catch (e: Exception) {
                    Log.e("TapeWear_Auth", "Failed to open demo video after fallback: ${e.message}")
                }
            }
            startDemoLiveDetectionLoop()
        }
    }
}
