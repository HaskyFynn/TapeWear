package com.example.tapewear.ui.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
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
import androidx.core.widget.doAfterTextChanged
import com.example.tapewear.MetricsLogger
import com.example.tapewear.Quality
import com.example.tapewear.R
import com.example.tapewear.camera.TorchTelemetryTracker
import com.example.tapewear.camera.VideoFrameSource
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.ExperimentStore
import com.example.tapewear.data.SettingsStore
import com.example.tapewear.ml.ModelManager
import com.example.tapewear.ml.TfLiteEmbedder
import com.example.tapewear.ui.camera.OverlayView
import com.example.tapewear.ui.main.MainActivity
import com.example.tapewear.util.ImageUtils
import com.example.tapewear.util.ScreenshotUtils
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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
    private val torchTelemetry by lazy { TorchTelemetryTracker(cameraManager) }
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
    private var suppressExperimentSelectionSync = false
    private var renderedExperimentMode = false
    private var interactionReadyAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        renderedExperimentMode = AuthConfig.EXPERIMENT_MODE
        setContentView(R.layout.activity_authenticate)

        // Home button on toolbar
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.appbarInc)?.let { toolbar ->
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
            toolbar.setNavigationOnClickListener {
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            }
        }

        textureView     = findViewById(R.id.textureViewAuth)
        overlayView     = findViewById(R.id.overlayViewAuth)
        demoImage       = findViewById(R.id.demoImageAuth)

        btnCapture      = findViewById(R.id.btnCaptureAuth)
        progressBar     = findViewById(R.id.progressBarAuth)
        progressLine    = findViewById(R.id.progressLineAuth)

        spinnerPattern  = findViewById(R.id.patternSpinner)
        flashCheck      = findViewById(R.id.flashCheck)
        flashCheck.setOnCheckedChangeListener { _, _ ->
            refreshRuntimeConfigUi()
        }

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

        // Experiment Views
        val normalPatternSelectorView = findViewById<View>(R.id.normalPatternSelectorView)
        val normalNightModeGroupAuth = findViewById<View>(R.id.normalNightModeGroupAuth)
        val experimentTagLayoutAuth = findViewById<View>(R.id.experimentTagLayoutAuth)
        val inputExperimentTagAuth = findViewById<AutoCompleteTextView>(R.id.inputExperimentTagAuth)
        val experimentConditionsGroupAuth = findViewById<View>(R.id.experimentConditionsGroupAuth)
        val toggleIlluminationAuth = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIlluminationAuth)
        val toggleDistanceAuth = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistanceAuth)
        val experimentTrialCounter = findViewById<TextView>(R.id.experimentTrialCounter)
        val btnCancelAuth = findViewById<Button>(R.id.btnCancelAuth)
        val experimentAuthActionsGroup = findViewById<View>(R.id.experimentAuthActionsGroup)
        val btnRetakeAuth = findViewById<Button>(R.id.btnRetakeAuth)
        val btnNextTrialAuth = findViewById<Button>(R.id.btnNextTrialAuth)
        val sessionCompleteCard = findViewById<View>(R.id.sessionCompleteCard)
        val btnDoneAuth = findViewById<Button>(R.id.btnDoneAuth)

        if (AuthConfig.EXPERIMENT_MODE) {
            normalPatternSelectorView.visibility = View.GONE
            normalNightModeGroupAuth.visibility = View.GONE
            experimentTagLayoutAuth.visibility = View.VISIBLE
            experimentConditionsGroupAuth.visibility = View.VISIBLE
            historyContainer.visibility = View.GONE
            
            // Set up Autocomplete with known tags
            val tagMap = ExperimentStore.getTagMap(this)
            val tagArray = tagMap.keys.toTypedArray()
            val autoAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tagArray)
            inputExperimentTagAuth.setAdapter(autoAdapter)
            inputExperimentTagAuth.doAfterTextChanged {
                val tag = ExperimentStore.normalizeTag(it?.toString().orEmpty())
                if (tag.isBlank()) {
                    ExperimentStore.setCurrentTagName(this, "")
                    ExperimentStore.setAuthTrialCount(this, 1)
                    ExperimentStore.setAuthAttempt(this, 1)
                }
                syncExperimentStudySelection()
            }
            
            // Auto-fill from registration if available
            val currentTag = ExperimentStore.getCurrentTagName(this)
            if (currentTag != null && tagMap.containsKey(currentTag)) {
                inputExperimentTagAuth.setText(currentTag, false)
                ModelManager.setActiveSlot(tagMap[currentTag]!!)
            }
            
            // Sync toggles with defaults from config (which was likely just set in registration)
            if (AuthConfig.EXPERIMENT_ILLUMINATION == "dim") {
                toggleIlluminationAuth.check(R.id.btnCondDimAuth)
            } else {
                toggleIlluminationAuth.check(R.id.btnCondBrightAuth)
            }
            if (AuthConfig.EXPERIMENT_DISTANCE == "far") {
                toggleDistanceAuth.check(R.id.btnCondFarAuth)
            } else {
                toggleDistanceAuth.check(R.id.btnCondNearAuth)
            }

            toggleIlluminationAuth.addOnButtonCheckedListener { _, _, isChecked ->
                if (isChecked && !suppressExperimentSelectionSync) {
                    syncExperimentStudySelection(preserveAttempt = false, showCooldown = true)
                }
            }
            toggleDistanceAuth.addOnButtonCheckedListener { _, _, isChecked ->
                if (isChecked && !suppressExperimentSelectionSync) {
                    syncExperimentStudySelection(preserveAttempt = false, showCooldown = true)
                }
            }
            
            // Auto update slot on tag selection
            inputExperimentTagAuth.setOnItemClickListener { _, _, position, _ ->
                val selectedTag = autoAdapter.getItem(position) ?: ""
                val mappedSlot = tagMap[selectedTag]
                if (mappedSlot != null) {
                    ModelManager.setActiveSlot(mappedSlot)
                    ExperimentStore.setCurrentTagName(this@AuthenticateActivity, selectedTag)
                    resultCard.visibility = View.GONE
                    syncExperimentStudySelection(preserveAttempt = false)
                }
            }
            
            btnCancelAuth.setOnClickListener {
                if (authRunning.get()) {
                    logExperimentAuthStatus(
                        status = "cancelled",
                        failureReason = "user_cancelled",
                        tagNameOverride = ExperimentStore.getCurrentTagName(this)
                    )
                }
                ExperimentStore.resetSession(this)
                mainHandler.post {
                    authRunning.set(false)
                    findViewById<View>(R.id.progressLayoutAuth)?.visibility = View.GONE
                    btnCapture.visibility = View.VISIBLE
                    inputExperimentTagAuth.setText("", false)
                    inputExperimentTagAuth.isEnabled = true
                    experimentConditionsGroupAuth.visibility = View.VISIBLE
                    overlayView.statusText = getString(R.string.auth_hint_align)
                    lockAeAwb(false)
                    if (!demoMode) setTorch(false)
                    updateExperimentTrialUi()
                    updateCaptureReadyState()
                }
            }
            
            btnRetakeAuth.setOnClickListener {
                // Increment attempt for the same trial — previous log stays but has lower attempt number
                ExperimentStore.incrementAuthAttempt(this)
                experimentAuthActionsGroup.visibility = View.GONE
                resultCard.visibility = View.GONE
                btnCapture.visibility = View.VISIBLE
                overlayView.statusText = getString(R.string.auth_hint_align)
                updateExperimentTrialUi() // Same trial, shows updated attempt
                updateCaptureReadyState()
            }
            
            btnNextTrialAuth.setOnClickListener {
                experimentAuthActionsGroup.visibility = View.GONE
                resultCard.visibility = View.GONE
                findViewById<View>(R.id.experimentConditionsGroupAuth)?.visibility = View.VISIBLE
                val nextCell = syncExperimentStudySelection(preserveAttempt = false)
                if (nextCell == null) {
                    findViewById<View>(R.id.actionSlotAuth).visibility = View.GONE
                    sessionCompleteCard.visibility = View.VISIBLE
                } else {
                    sessionCompleteCard.visibility = View.GONE
                    findViewById<View>(R.id.actionSlotAuth).visibility = View.VISIBLE
                    btnCapture.visibility = View.VISIBLE
                    overlayView.statusText = getString(R.string.auth_hint_align)
                }
            }
            
            // Initialize trial counter UI on launch
            syncExperimentStudySelection()

            btnDoneAuth.setOnClickListener {
                // Finish session, clean up, go back to Reg
                ExperimentStore.resetSession(this)
                finish() // returns to register activity
            }
        }

        // Slot spinner 1..50 (only used in normal mode)
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
                refreshRuntimeConfigUi()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnCapture.setOnClickListener {
            // Logic moved into startAuthBurst method entirely
            boostLiveDetectionWindow()
            startAuthBurst()
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
                torchTelemetry.configure(cameraId, hasFlash)

                // Choose optimal preview size from camera capabilities
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null) {
                    val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
                    if (outputSizes != null && outputSizes.isNotEmpty()) {
                        previewSize = chooseOptimalSize(outputSizes, textureView.width, textureView.height)
                        Log.d("TapeWear_Auth", "Selected preview size: ${previewSize.width}x${previewSize.height}")
                    }
                }
            } catch (e: Exception) {
                switchToDemo(getString(R.string.cam_chars_fail, e.message ?: ""))
                return
            }

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                    if (!demoMode) startWhenReady()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    maybeRunLiveDetectionFromPreview()
                }
            }
        } else {
            // Pure demo: camera UI hidden, detector can still run if present
            Log.d("TapeWear_Auth", "AuthenticateActivity in DEMO mode using asset video")
            textureView.visibility = View.GONE
            demoImage.visibility = View.VISIBLE
            overlayView.statusText = getString(R.string.auth_hint_align)
            if (ModelManager.detector == null) {
                Log.w("TapeWear_Auth", "DEMO mode active but detector is not initialized.")
            }
            torchTelemetry.configure(cameraId = null, flashAvailable = false)
        }
    }

    override fun onResume() {
        super.onResume()
        SettingsStore.load(this)
        if (renderedExperimentMode != AuthConfig.EXPERIMENT_MODE) {
            recreate()
            return
        }
        ensureModelsInitializedIfNeeded()
        refreshRuntimeConfigUi()
        if (!demoMode) {
            torchTelemetry.register()
        }
        demoUiActive = true
        liveDetectionEnabledAtMs = SystemClock.elapsedRealtime() + liveDetectStartDelayMs
        lastLiveDetectMs = 0L
        resetLiveDetectionPacing()

        // Reset experiment UI to clean state on resume
        if (AuthConfig.EXPERIMENT_MODE) {
            applyExperimentConditionSelection(
                AuthConfig.EXPERIMENT_ILLUMINATION,
                AuthConfig.EXPERIMENT_DISTANCE,
                showCooldown = false
            )
            val inputTag = findViewById<AutoCompleteTextView>(R.id.inputExperimentTagAuth)
            // Refresh autocomplete adapter with latest tags
            val tagMap = ExperimentStore.getTagMap(this)
            val tagArray = tagMap.keys.toTypedArray()
            val autoAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tagArray)
            inputTag?.setAdapter(autoAdapter)
            val currentTag = ExperimentStore.getCurrentTagName(this)
            if (currentTag != null && tagMap.containsKey(currentTag)) {
                inputTag?.setText(currentTag, false)
                ModelManager.setActiveSlot(tagMap[currentTag]!!)
            } else {
                inputTag?.setText("", false)
            }
            inputTag?.isEnabled = true

            findViewById<View>(R.id.experimentConditionsGroupAuth)?.visibility = View.VISIBLE
            findViewById<View>(R.id.sessionCompleteCard)?.visibility = View.GONE
            findViewById<View>(R.id.experimentAuthActionsGroup)?.visibility = View.GONE
            findViewById<View>(R.id.actionSlotAuth)?.visibility = View.VISIBLE
            resultCard.visibility = View.GONE
            btnCapture.visibility = View.VISIBLE
            authRunning.set(false)
            beginInteractionCooldown("Applying updated settings... Please wait.", 300L)
            syncExperimentStudySelection()
        }

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
            torchTelemetry.unregister()
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
        configureTransform(textureView.width, textureView.height)
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
        val detectorLabel = if (isYoloReady()) {
            String.format(
                Locale.US,
                "%s %.2f",
                ModelManager.activeDetectorLabel(),
                ModelManager.detectorConfidenceThreshold()
            )
        } else {
            "${ModelManager.configuredDetectorLabel()} missing"
        }
        val triggerLabel = if (AuthConfig.HANDS_FREE_ENABLED) {
            "Auto x${AuthConfig.HANDS_FREE_CONSECUTIVE_HITS}"
        } else {
            "Manual"
        }
        modeIndicator.text = if (AuthConfig.USE_ML_EMBEDDER) "ML Pipeline" else "CV Pipeline"
        runtimeThresholds.text = if (AuthConfig.EXPERIMENT_MODE) {
            val tag = selectedExperimentTag().ifBlank { "-" }
            val slot = selectedExperimentSlot()?.toString() ?: "-"
            val illumination = selectedExperimentIllumination().uppercase(Locale.US)
            val distance = selectedExperimentDistance().uppercase(Locale.US)
            val flashLabel = when {
                illumination != "DIM" -> "Flash OFF"
                !AuthConfig.EXPERIMENT_FLASH_ENABLED -> "Flash OFF"
                hasFlash -> "Flash ON"
                else -> "No flash hw"
            }
            String.format(
                Locale.US,
                "Tag %s | Slot %s | Match %.2f | %s\n%s / %s | %s | %s",
                tag,
                slot,
                AuthConfig.MATCH_THRESHOLD,
                detectorLabel,
                illumination,
                distance,
                flashLabel,
                triggerLabel
            )
        } else {
            val slot = ModelManager.getActiveSlot()
            val modelLabel = if (ModelManager.hasModel(this, slot)) "Model ready" else "No model"
            val modeLabel = if (flashCheck.isChecked) "Night" else "Day"
            val flashLabel = when {
                !flashCheck.isChecked -> "Torch OFF"
                hasFlash -> "Torch ON"
                else -> "No flash hw"
            }
            String.format(
                Locale.US,
                "Slot %d | %s | Match %.2f | %s\n%s | %s | %s",
                slot,
                modelLabel,
                AuthConfig.MATCH_THRESHOLD,
                detectorLabel,
                modeLabel,
                flashLabel,
                triggerLabel
            )
        }
        updateCaptureReadyState()
    }

    private fun updateExperimentTrialUi() {
        if (!AuthConfig.EXPERIMENT_MODE) return
        val tvTrial = findViewById<TextView>(R.id.experimentTrialCounter)
        val tag = selectedExperimentTag()
        val attempt = ExperimentStore.getAuthAttempt(this)
        val illumination = selectedExperimentIllumination().uppercase(Locale.US)
        val distance = selectedExperimentDistance().uppercase(Locale.US)
        val pendingCell = nextPendingCellForSelectedCondition()
        val pendingInIllumination = nextPendingCellForSelectedIllumination()
        val pendingAny = if (tag.isBlank()) null else ExperimentStore.nextPendingAuthCellAnyIllumination(this, tag)
        tvTrial.text = when {
            tag.isBlank() -> "Select a registered tag"
            pendingCell == null && pendingInIllumination != null ->
                "$illumination / $distance complete. Switch to ${pendingInIllumination.condition.distance.uppercase(Locale.US)}."
            pendingCell == null && pendingAny != null ->
                "$illumination block complete for $tag. ${pendingAny.condition.illumination.uppercase(Locale.US)} remains pending."
            pendingCell == null -> "$tag complete for this session"
            attempt > 1 ->
                "$illumination / $distance | Trial ${pendingCell.trialNumber}/${AuthConfig.EXPERIMENT_AUTH_TRIALS}  (Attempt $attempt)"
            else ->
                "$illumination / $distance | Trial ${pendingCell.trialNumber}/${AuthConfig.EXPERIMENT_AUTH_TRIALS}"
        }
        tvTrial.visibility = View.VISIBLE
        refreshRuntimeConfigUi()
    }

    private fun selectedExperimentTag(): String {
        val autoView = findViewById<AutoCompleteTextView>(R.id.inputExperimentTagAuth) ?: return ""
        return ExperimentStore.normalizeTag(autoView.text?.toString().orEmpty())
    }

    private fun selectedExperimentSlot(): Int? {
        val tag = selectedExperimentTag()
        if (tag.isEmpty()) return null
        return ExperimentStore.getRegisteredSlotForTag(this, tag)
    }

    private fun selectedExperimentIllumination(): String {
        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIlluminationAuth)
        return if (toggle?.checkedButtonId == R.id.btnCondDimAuth) "dim" else "bright"
    }

    private fun selectedExperimentDistance(): String {
        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistanceAuth)
        return if (toggle?.checkedButtonId == R.id.btnCondFarAuth) "far" else "near"
    }

    private fun nextPendingCellForSelectedCondition(): ExperimentStore.PendingAuthCell? {
        val tag = selectedExperimentTag()
        if (tag.isBlank()) return null
        return ExperimentStore.nextPendingAuthCell(
            context = this,
            tagName = tag,
            illumination = selectedExperimentIllumination(),
            distance = selectedExperimentDistance()
        )
    }

    private fun nextPendingCellForSelectedIllumination(): ExperimentStore.PendingAuthCell? {
        val tag = selectedExperimentTag()
        if (tag.isBlank()) return null
        return ExperimentStore.nextPendingAuthCell(
            context = this,
            tagName = tag,
            illumination = selectedExperimentIllumination()
        )
    }

    private fun isInteractionCoolingDown(): Boolean =
        SystemClock.elapsedRealtime() < interactionReadyAtMs

    private fun beginInteractionCooldown(message: String, durationMs: Long = 350L) {
        interactionReadyAtMs = SystemClock.elapsedRealtime() + durationMs
        if (!authRunning.get()) {
            overlayView.statusText = message
        }
        updateCaptureReadyState()
        mainHandler.postDelayed({
            if (!authRunning.get() && !isInteractionCoolingDown()) {
                overlayView.statusText = getString(R.string.auth_hint_align)
                updateCaptureReadyState()
            }
        }, durationMs)
    }

    private fun applyExperimentConditionSelection(
        illumination: String,
        distance: String,
        showCooldown: Boolean
    ) {
        suppressExperimentSelectionSync = true
        try {
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIlluminationAuth)
                ?.check(if (illumination == "dim") R.id.btnCondDimAuth else R.id.btnCondBrightAuth)
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistanceAuth)
                ?.check(if (distance == "far") R.id.btnCondFarAuth else R.id.btnCondNearAuth)
        } finally {
            suppressExperimentSelectionSync = false
        }

        val changed =
            AuthConfig.EXPERIMENT_ILLUMINATION != illumination ||
                AuthConfig.EXPERIMENT_DISTANCE != distance
        AuthConfig.EXPERIMENT_ILLUMINATION = illumination
        AuthConfig.EXPERIMENT_DISTANCE = distance
        if (changed) {
            SettingsStore.save(this)
        }
        if (showCooldown) {
            beginInteractionCooldown("Applying condition... Please wait.", 300L)
        }
    }

    private fun syncExperimentStudySelection(
        preserveAttempt: Boolean = true,
        showCooldown: Boolean = false
    ): ExperimentStore.PendingAuthCell? {
        if (!AuthConfig.EXPERIMENT_MODE) return null
        val tag = selectedExperimentTag()
        if (tag.isBlank()) {
            updateExperimentTrialUi()
            updateCaptureReadyState()
            return null
        }

        ExperimentStore.setCurrentTagName(this, tag)
        val illumination = selectedExperimentIllumination()
        var distance = selectedExperimentDistance()
        var pendingCell = ExperimentStore.nextPendingAuthCell(
            context = this,
            tagName = tag,
            illumination = illumination,
            distance = distance
        )
        if (pendingCell == null) {
            pendingCell = ExperimentStore.nextPendingAuthCell(
                context = this,
                tagName = tag,
                illumination = illumination
            )
            if (pendingCell != null) {
                distance = pendingCell.condition.distance
            }
        }

        applyExperimentConditionSelection(illumination, distance, showCooldown)

        if (pendingCell != null) {
            val sameCell =
                ExperimentStore.getCurrentTagName(this) == pendingCell.tagName &&
                    ExperimentStore.getAuthTrialCount(this) == pendingCell.trialNumber &&
                    AuthConfig.EXPERIMENT_ILLUMINATION == illumination &&
                    AuthConfig.EXPERIMENT_DISTANCE == distance
            ExperimentStore.setAuthTrialCount(this, pendingCell.trialNumber)
            if (!preserveAttempt || !sameCell) {
                ExperimentStore.setAuthAttempt(this, 1)
            }
        } else if (!preserveAttempt) {
            ExperimentStore.setAuthAttempt(this, 1)
        }
        updateExperimentTrialUi()
        updateCaptureReadyState()
        return pendingCell
    }

    private fun updateCaptureReadyState() {
        if (!::btnCapture.isInitialized) return
        btnCapture.isEnabled = if (AuthConfig.EXPERIMENT_MODE) {
            val slot = selectedExperimentSlot()
            slot != null &&
                !isInteractionCoolingDown() &&
                nextPendingCellForSelectedCondition() != null &&
                ModelManager.hasModel(this, slot) &&
                isYoloReady()
        } else if (::spinnerPattern.isInitialized) {
            val slot = ModelManager.getActiveSlot()
            !isInteractionCoolingDown() && ModelManager.hasModel(this, slot) && isYoloReady()
        } else {
            false
        }
    }

    private fun ensureExperimentStudyReady(): Boolean {
        if (!AuthConfig.EXPERIMENT_MODE) return true
        val error = ExperimentStore.studySetupError(this) ?: return true
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        overlayView.statusText = "Set study metadata in Settings"
        return false
    }

    private fun logExperimentAuthStatus(
        status: String,
        failureReason: String,
        framesCollected: Int = 0,
        framesScored: Int = 0,
        tagNameOverride: String? = null
    ) {
        if (!AuthConfig.EXPERIMENT_MODE) return
        val now = SystemClock.elapsedRealtime()
        val totalMs = if (authSessionStartMs > 0L) now - authSessionStartMs else 0L
        MetricsLogger.logAuth(
            ctx = this,
            slot = ModelManager.getActiveSlot(),
            similarity = 0f,
            isMatch = false,
            burstMs = totalMs,
            framesCollected = framesCollected,
            framesScored = framesScored,
            nightMode = AuthConfig.EXPERIMENT_ILLUMINATION == "dim",
            demoMode = demoMode,
            overhead = authOverhead,
            flashTelemetry = torchTelemetry.snapshot(),
            trialStatus = status,
            failureReason = failureReason,
            tagNameOverride = tagNameOverride
        )
    }

    private fun isYoloReady(): Boolean = ModelManager.isDetectorReady()

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
        if (isYoloReady() && ModelManager.detectorMatchesConfig() && ModelManager.mlEmbedder != null) return
        if (!modelInitRunning.compareAndSet(false, true)) return

        Thread {
            val threads = modelThreads()
            val t0 = SystemClock.elapsedRealtime()
            try {
                synchronized(ModelManager) {
                    ModelManager.ensureConfiguredDetector(
                        context = applicationContext,
                        numThreads = threads
                    )
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

        // Hands-free auto-trigger: start authentication only when a valid target is selected.
        val slot = if (AuthConfig.EXPERIMENT_MODE) selectedExperimentSlot() else ModelManager.getActiveSlot()
        if (AuthConfig.HANDS_FREE_ENABLED
            && seenInGuide
            && intentHitStreak >= AuthConfig.HANDS_FREE_CONSECUTIVE_HITS
            && !authRunning.get()
            && isYoloReady()
            && slot != null
            && ModelManager.hasModel(this, slot)
        ) {
            Log.d("TapeWear_Auth", "Hands-free: auto-triggering authentication (streak=$intentHitStreak)")
            mainHandler.post {
                if (!authRunning.get()) {
                    boostLiveDetectionWindow()
                    startAuthBurst()
                }
            }
            return
        }

        mainHandler.post {
            if (authRunning.get()) return@post
            overlayView.statusText = when {
                AuthConfig.HANDS_FREE_ENABLED && seenInGuide -> "Pattern detected ($intentHitStreak/${AuthConfig.HANDS_FREE_CONSECUTIVE_HITS})\u2026"
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
        torchTelemetry.resetAttempt()
        if (!isYoloReady()) {
            authRunning.set(false)
            overlayView.statusText = "Detector unavailable"
            Toast.makeText(this, "${ModelManager.configuredDetectorLabel()} detector is required", Toast.LENGTH_SHORT).show()
            return
        }

        if (!ensureExperimentStudyReady()) {
            authRunning.set(false)
            updateCaptureReadyState()
            return
        }

        if (AuthConfig.EXPERIMENT_MODE) {
            val autoView = findViewById<AutoCompleteTextView>(R.id.inputExperimentTagAuth)
            val tagName = ExperimentStore.normalizeTag(autoView.text.toString())
            val slot = ExperimentStore.getRegisteredSlotForTag(this, tagName)
            if (tagName.isEmpty() || slot == null || !ModelManager.hasModel(this, slot)) {
                logExperimentAuthStatus(
                    status = "rejected",
                    failureReason = "invalid_or_missing_tag",
                    tagNameOverride = tagName.ifBlank { null }
                )
                Toast.makeText(this, "Please select a registered tag", Toast.LENGTH_SHORT).show()
                updateCaptureReadyState()
                return
            }
            val pendingCell = syncExperimentStudySelection(preserveAttempt = true)
            if (pendingCell == null) {
                logExperimentAuthStatus(
                    status = "rejected",
                    failureReason = "illumination_block_complete",
                    tagNameOverride = tagName
                )
                val illumination = selectedExperimentIllumination().uppercase(Locale.US)
                Toast.makeText(
                    this,
                    "All scheduled $illumination auth trials are already complete for $tagName",
                    Toast.LENGTH_SHORT
                ).show()
                findViewById<View>(R.id.sessionCompleteCard)?.visibility = View.VISIBLE
                findViewById<View>(R.id.actionSlotAuth)?.visibility = View.GONE
                updateCaptureReadyState()
                return
            }
            // Ensure slot matches tag
            ModelManager.setActiveSlot(slot)
            
            // UI cleanup
            autoView.isEnabled = false
            findViewById<View>(R.id.experimentConditionsGroupAuth)?.visibility = View.GONE
            findViewById<View>(R.id.btnCancelAuth)?.visibility = View.VISIBLE
        } else {
            val slot = ModelManager.getActiveSlot()
            if (!ModelManager.hasModel(this, slot)) {
                Toast.makeText(this, getString(R.string.auth_no_model), Toast.LENGTH_SHORT).show()
                return
            }
            findViewById<View>(R.id.btnCancelAuth)?.visibility = View.GONE
        }

        authRunning.set(true)
        btnCapture.isEnabled = false
        btnCapture.visibility = View.GONE
        resultCard.visibility = View.GONE
        findViewById<View>(R.id.progressLayoutAuth)?.visibility = View.VISIBLE
        overlayView.statusText = getString(R.string.auth_holdsteady)
        progressLine.text = getString(R.string.auth_preparing)
        progressLine.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        val night = if (AuthConfig.EXPERIMENT_MODE) AuthConfig.EXPERIMENT_ILLUMINATION == "dim" else flashCheck.isChecked
        val useFlash = if (AuthConfig.EXPERIMENT_MODE) night && AuthConfig.EXPERIMENT_FLASH_ENABLED else night
        if (!demoMode && useFlash && hasFlash) setTorch(true)

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
        // Single-frame capture with detector-first pipeline.
        val bg = backgroundHandler
        if (bg == null) {
            logExperimentAuthStatus(
                status = "failed",
                failureReason = "background_thread_unavailable",
                tagNameOverride = ExperimentStore.getCurrentTagName(this)
            )
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
                logExperimentAuthStatus(
                    status = "failed",
                    failureReason = "frame_capture_failed",
                    tagNameOverride = ExperimentStore.getCurrentTagName(this)
                )
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

            // Stage: detect + quality gate (quality only on detector-positive frames)
            val tQual0 = SystemClock.elapsedRealtime()
            val detOutcome = detectFrame(sample, updateOverlay = true)
            if (!detOutcome.hasDetection) {
                preScoreHint = "No ${ModelManager.configuredDetectorLabel()} detection above ${"%.2f".format(Locale.US, ModelManager.detectorConfidenceThreshold())}"
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
        if (!demoMode) setTorch(false)

        val bg = backgroundHandler
        if (bg == null) {
            logExperimentAuthStatus(
                status = "failed",
                failureReason = "background_thread_unavailable",
                framesCollected = frames.size,
                tagNameOverride = ExperimentStore.getCurrentTagName(this)
            )
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
                    Log.w("TapeWear_Auth", "No valid detector-scored frame; returning no-pattern result")
                }

                val totalFrames = frames.size
                val now = SystemClock.elapsedRealtime()
                val burstMs = if (authSessionStartMs > 0L) now - authSessionStartMs else 0L
                val slot = ModelManager.getActiveSlot()

                if (scoringFailed) {
                    logExperimentAuthStatus(
                        status = "failed",
                        failureReason = "scoring_failed",
                        framesCollected = totalFrames,
                        tagNameOverride = ExperimentStore.getCurrentTagName(this)
                    )
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
                    overhead = authOverhead,
                    flashTelemetry = torchTelemetry.snapshot()
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
                    nightMode = night,
                    demoMode = demoMode,
                    flashTelemetry = torchTelemetry.snapshot()
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

                    updateCaptureReadyState()
                    stagesText.text =
                        "settle: ${settleMs}ms | capture: ${captureMs}ms | quality: ${qualityMs}ms\n" +
                        "detect: ${scored.detectMs}ms | embed: ${scored.embedMs}ms | cosine: ${scored.cosineMs}ms | total: ${burstMs}ms"

                    val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    val timeStr = timeFormat.format(java.util.Date())
                    val matchStr = if (isMatch) "MATCH ${(finalSim * 100).toInt()}%" else "NO_MATCH ${(finalSim * 100).toInt()}%"
                    authHistory.add(0, "$timeStr - Slot $slot: $matchStr")
                    if (authHistory.size > 3) authHistory.removeAt(authHistory.size - 1)
                    historyText.text = authHistory.joinToString("\n")
                    
                    if (AuthConfig.EXPERIMENT_MODE) {
                        historyContainer.visibility = View.GONE
                        val nextCell =
                            ExperimentStore.nextPendingAuthCell(
                                context = this@AuthenticateActivity,
                                tagName = ExperimentStore.getCurrentTagName(this@AuthenticateActivity),
                                illumination = selectedExperimentIllumination()
                            )
                        if (nextCell == null) {
                            findViewById<View>(R.id.experimentAuthActionsGroup).visibility = View.GONE
                            findViewById<View>(R.id.sessionCompleteCard).visibility = View.VISIBLE
                            findViewById<View>(R.id.actionSlotAuth).visibility = View.GONE
                        } else {
                            findViewById<View>(R.id.experimentAuthActionsGroup).visibility = View.VISIBLE
                            btnCapture.visibility = View.GONE
                        }
                    } else {
                        historyContainer.visibility = View.VISIBLE
                    }
                    
                    resetLiveDetectionPacing()
                    authRunning.set(false)

                    // Provide a backup screenshot of the results
                    if (AuthConfig.EXPERIMENT_MODE) {
                        mainHandler.postDelayed({
                            try {
                                val tagRaw = ExperimentStore.getCurrentTagName(this@AuthenticateActivity) ?: "unknown"
                                val tag = tagRaw.replace(Regex("[^a-zA-Z0-9_]"), "")
                                val t = ExperimentStore.getAuthTrialCount(this@AuthenticateActivity)
                                val a = ExperimentStore.getAuthAttempt(this@AuthenticateActivity)
                                val ts = System.currentTimeMillis()
                                val filename = "auth_${tag}_trial${t}_attempt${a}_${ts}.jpg"
                                ScreenshotUtils.takeScreenshot(this@AuthenticateActivity, filename)
                            } catch(e: Exception) {
                                Log.e("TapeWear_Auth", "Failed to init screenshot: ${e.message}")
                            }
                        }, 250) // Wait for UI / resultCard animation to settle
                    }
                }
            } catch (e: Throwable) {
                Log.e("TapeWear_Auth", "finishAuth failed: ${e.message}", e)
                logExperimentAuthStatus(
                    status = "failed",
                    failureReason = "unexpected_finish_auth_error",
                    framesCollected = frames.size,
                    tagNameOverride = ExperimentStore.getCurrentTagName(this)
                )
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

        val frame = capturePreviewFrame()

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
                Log.w("TapeWear_Auth", "Live detector failed: ${e.message}")
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
                                Log.w("TapeWear_Auth", "Demo live detector failed: ${e.message}")
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

    private fun capturePreviewFrame(maxLongSide: Int = 960): Bitmap? {
        val viewW = textureView.width
        val viewH = textureView.height
        if (viewW <= 0 || viewH <= 0) return null

        val longSide = maxOf(viewW, viewH)
        val scale = if (longSide > maxLongSide) {
            maxLongSide.toFloat() / longSide.toFloat()
        } else {
            1f
        }
        val targetW = (viewW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (viewH * scale).roundToInt().coerceAtLeast(1)
        val viewBitmap = try {
            textureView.getBitmap(targetW, targetH)
        } catch (_: Exception) {
            null
        } ?: return null

        val previewRect = getPreviewContentRect(viewW, viewH)
        if (previewRect.width() <= 1f || previewRect.height() <= 1f) {
            return viewBitmap
        }

        val sx = targetW.toFloat() / viewW.toFloat()
        val sy = targetH.toFloat() / viewH.toFloat()
        val left = (previewRect.left * sx).roundToInt().coerceIn(0, targetW - 1)
        val top = (previewRect.top * sy).roundToInt().coerceIn(0, targetH - 1)
        val width = (previewRect.width() * sx).roundToInt().coerceAtLeast(1).coerceAtMost(targetW - left)
        val height = (previewRect.height() * sy).roundToInt().coerceAtLeast(1).coerceAtMost(targetH - top)
        val cropped = Bitmap.createBitmap(viewBitmap, left, top, width, height)
        viewBitmap.recycle()
        return cropped
    }

    private fun detectFrame(frame: Bitmap, updateOverlay: Boolean): FrameDetectionOutcome {
        val det = ModelManager.detector
        if (det == null || !ModelManager.isDetectorReady()) {
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
                score = it.score,
                quad = it.supportQuad?.map { point -> mapFramePointToOverlay(point, frameW, frameH) }
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
        val previewRect = getPreviewContentRect(overlayView.width, overlayView.height)
        val targetRect = if (previewRect.width() > 0f && previewRect.height() > 0f) {
            previewRect
        } else {
            RectF(0f, 0f, overlayView.width.toFloat(), overlayView.height.toFloat())
        }
        val scale = minOf(
            targetRect.width() / frameW.toFloat(),
            targetRect.height() / frameH.toFloat()
        )
        val offsetX = targetRect.left + (targetRect.width() - frameW * scale) / 2f
        val offsetY = targetRect.top + (targetRect.height() - frameH * scale) / 2f
        return RectF(
            box.left * scale + offsetX,
            box.top * scale + offsetY,
            box.right * scale + offsetX,
            box.bottom * scale + offsetY
        )
    }

    private fun mapFramePointToOverlay(point: PointF, frameW: Int, frameH: Int): PointF {
        val previewRect = getPreviewContentRect(overlayView.width, overlayView.height)
        val targetRect = if (previewRect.width() > 0f && previewRect.height() > 0f) {
            previewRect
        } else {
            RectF(0f, 0f, overlayView.width.toFloat(), overlayView.height.toFloat())
        }
        val scale = minOf(
            targetRect.width() / frameW.toFloat(),
            targetRect.height() / frameH.toFloat()
        )
        val offsetX = targetRect.left + (targetRect.width() - frameW * scale) / 2f
        val offsetY = targetRect.top + (targetRect.height() - frameH * scale) / 2f
        return PointF(point.x * scale + offsetX, point.y * scale + offsetY)
    }

    private fun setTorch(on: Boolean) {
        if (!hasFlash) return
        try { cameraManager.setTorchMode(cameraId ?: return, on) } catch (_: Exception) {
            torchTelemetry.markCommandError()
        }
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
            capturePreviewFrame()
        }
    } catch (e: Exception) {
        Log.e("TapeWear", "snapshotCurrent failed: ${e.message}")
        null
    }

    private fun hideProgress() {
        progressLine.visibility = View.GONE
        progressBar.visibility = View.GONE
        findViewById<View>(R.id.progressLayoutAuth)?.visibility = View.GONE
        btnCapture.visibility = View.VISIBLE
    }

    /**
     * Choose the largest camera preview size that best matches the display
     * aspect ratio, capped at 1280px.
     */
    private fun chooseOptimalSize(choices: Array<Size>, maxW: Int, maxH: Int): Size {
        val safeW = if (maxW > 0) maxW else resources.displayMetrics.widthPixels
        val safeH = if (maxH > 0) maxH else resources.displayMetrics.heightPixels
        val targetLong = maxOf(safeW, safeH).toDouble()
        val targetShort = minOf(safeW, safeH).coerceAtLeast(1).toDouble()
        val targetRatio = targetLong / targetShort
        val cap = 1280
        val suitable = choices.filter { maxOf(it.width, it.height) <= cap }
            .ifEmpty { choices.toList() }

        return suitable
            .sortedWith(
                compareBy<Size> {
                    val longSide = maxOf(it.width, it.height).toDouble()
                    val shortSide = minOf(it.width, it.height).coerceAtLeast(1).toDouble()
                    kotlin.math.abs((longSide / shortSide) - targetRatio)
                }.thenByDescending { it.width.toLong() * it.height.toLong() }
            )
            .firstOrNull()
            ?: choices.first()
    }

    private fun currentDisplayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.rotation
    }

    private fun getPreviewContentRect(viewWidth: Int, viewHeight: Int): RectF {
        if (viewWidth <= 0 || viewHeight <= 0) return RectF()
        val rotation = currentDisplayRotation()
        val bufferWidth: Float
        val bufferHeight: Float
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferWidth = previewSize.width.toFloat()
            bufferHeight = previewSize.height.toFloat()
        } else {
            bufferWidth = previewSize.height.toFloat()
            bufferHeight = previewSize.width.toFloat()
        }
        val scale = minOf(
            viewWidth.toFloat() / bufferWidth,
            viewHeight.toFloat() / bufferHeight
        )
        val scaledW = bufferWidth * scale
        val scaledH = bufferHeight * scale
        val left = (viewWidth - scaledW) / 2f
        val top = (viewHeight - scaledH) / 2f
        return RectF(left, top, left + scaledW, top + scaledH)
    }

    /**
     * Apply a fit-center transform so the user sees the full camera frame.
     * Extra space is letterboxed instead of cropping the sensor output.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val rotation = currentDisplayRotation()
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.CENTER)
            val scale = minOf(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90f * (rotation - 2)), centerX, centerY)
        } else {
            val scaleX = viewWidth.toFloat() / previewSize.height.toFloat()
            val scaleY = viewHeight.toFloat() / previewSize.width.toFloat()
            val scale = minOf(scaleX, scaleY)
            matrix.setScale(scale, scale, centerX, centerY)
            val scaledW = previewSize.height * scale
            val scaledH = previewSize.width * scale
            matrix.postTranslate((viewWidth - scaledW) / 2f - (centerX - scaledW / 2f),
                                 (viewHeight - scaledH) / 2f - (centerY - scaledH / 2f))
        }

        textureView.setTransform(matrix)
        overlayView.setPreviewContentRect(getPreviewContentRect(viewWidth, viewHeight))
        Log.d("TapeWear_Auth", "Camera transform applied: preview=${previewSize.width}x${previewSize.height}, view=${viewWidth}x${viewHeight}")
    }

    // DEMO fallback (camera failure or manual)
    private fun switchToDemo(reason: String) {
        demoMode = true
        Log.w("AuthenticateActivity", "DEMO fallback: $reason")

        demoImage.visibility = View.VISIBLE
        textureView.visibility = View.GONE
        overlayView.setPreviewContentRect(null)
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
