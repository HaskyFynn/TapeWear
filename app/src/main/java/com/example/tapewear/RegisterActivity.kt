package com.example.tapewear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class RegisterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIGHT_MODE = "extra_night_mode"
    }

    private var nightMode = false

    // Set this to true to run with asset video instead of camera
    private val demoMode = AuthConfig.DEMO_MODE

    // Video demo source
    private val demoAssetName = "1.mp4"
    private var videoSource: VideoFrameSource? = null
    private var lastDemoFrame: Bitmap? = null
    @Volatile private var demoUiActive = false
    private var currentVideoTimeMs: Long = 0L
    private var videoFrameStepMs: Long = 66L

    // Views
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var demoImage: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnAuth: Button
    private lateinit var previewThumb: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var flashHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLine: TextView
    private lateinit var topMessage: TextView
    private lateinit var progressSlot: LinearLayout
    private lateinit var flashCheckRegister: CheckBox
    private lateinit var spnSlot: Spinner
    private lateinit var modeIndicator: TextView
    private lateinit var runtimeThresholds: TextView
    private lateinit var stagesTextReg: TextView

    // Session dirs
    private lateinit var sessionDir: File

    // Camera2
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String = "0"
    private var previewSize = Size(640, 480)
    private var sensorOrientation: Int = 90
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasFlash = false
    private val torchTelemetry by lazy { TorchTelemetryTracker(cameraManager) }
    private var reqBuilder: CaptureRequest.Builder? = null

    // Background thread for processing
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Prefs
    private val prefs by lazy { getSharedPreferences("tape_prefs", MODE_PRIVATE) }
    private var currentSlot: Int
        get() = prefs.getInt("last_slot", 1)
        set(v) { prefs.edit { putInt("last_slot", v) } }

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else toast(getString(R.string.err_camera_perm))
    }

    private val regRunning = AtomicBoolean(false)
    private val liveDetectRunning = AtomicBoolean(false)
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
    private var renderedExperimentMode = false
    private var interactionReadyAtMs: Long = 0L
    private var suppressExperimentConditionSignals = false

    // Registration timing
    private var regSessionStartMs: Long = 0L
    private var regOverhead: MetricsLogger.OverheadSnapshot? = null
    private var regSettleMs: Long = 0L
    private var regCaptureMs: Long = 0L
    private var regQualityMs: Long = 0L
    private var regEnrollMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        renderedExperimentMode = AuthConfig.EXPERIMENT_MODE
        setContentView(R.layout.activity_register)

        // Home button on toolbar
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.appbarInc)?.let { toolbar ->
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_revert)
            toolbar.setNavigationOnClickListener {
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(intent)
            }
        }

        // Bind
        textureView   = findViewById(R.id.textureView)
        overlayView   = findViewById(R.id.overlayView)
        demoImage     = findViewById(R.id.demoImage)
        btnCapture    = findViewById(R.id.btnCapture)
        btnAuth       = findViewById(R.id.btnExport)   // reuse old Export button as "Authenticate"
        previewThumb  = findViewById(R.id.previewThumb)
        previewLabel  = findViewById(R.id.previewLabel)
        flashHint     = findViewById(R.id.flashHint)
        progressBar   = findViewById(R.id.progressBar)
        progressLine  = findViewById(R.id.progressLine)
        topMessage    = findViewById(R.id.topMessage)


        progressSlot  = findViewById(R.id.progressSlot)
        flashCheckRegister = findViewById(R.id.flashCheckRegister)
        spnSlot       = findViewById(R.id.spnSlot)
        runtimeThresholds = findViewById(R.id.runtimeThresholdsReg)
        stagesTextReg = findViewById(R.id.stagesTextReg)
        val slotStatusText = findViewById<TextView>(R.id.slotStatusTextReg)

        modeIndicator  = findViewById(R.id.modeIndicator)
        refreshRuntimeConfigUi()
        stagesTextReg.visibility = View.GONE

        // Experiment views
        val normalSlotPickerView = findViewById<View>(R.id.normalSlotPickerView)
        val normalNightModeGroup = findViewById<View>(R.id.normalNightModeGroup)
        val experimentTagLayout = findViewById<View>(R.id.experimentTagLayout)
        val inputExperimentTag = findViewById<TextView>(R.id.inputExperimentTag)
        val experimentConditionsGroup = findViewById<View>(R.id.experimentConditionsGroup)
        val toggleIllumination = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIllumination)
        val toggleDistance = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistance)
        val btnCancelReg = findViewById<Button>(R.id.btnCancelReg)
        val experimentCompletionGroup = findViewById<View>(R.id.experimentCompletionGroup)
        val btnProceedToAuth = findViewById<Button>(R.id.btnProceedToAuth)
        val btnRetakeReg = findViewById<Button>(R.id.btnRetakeReg)

        if (AuthConfig.EXPERIMENT_MODE) {
            normalSlotPickerView.visibility = View.GONE
            normalNightModeGroup.visibility = View.GONE
            experimentTagLayout.visibility = View.VISIBLE
            experimentConditionsGroup.visibility = View.VISIBLE
            inputExperimentTag.doAfterTextChanged {
                updateCaptureReadyState()
            }
            
            // Set defaults matches config
            if (AuthConfig.EXPERIMENT_ILLUMINATION == "dim") {
                toggleIllumination.check(R.id.btnCondDim)
            } else {
                toggleIllumination.check(R.id.btnCondBright)
            }
            if (AuthConfig.EXPERIMENT_DISTANCE == "far") {
                toggleDistance.check(R.id.btnCondFar)
            } else {
                toggleDistance.check(R.id.btnCondNear)
            }

            toggleIllumination.addOnButtonCheckedListener { _, _, isChecked ->
                if (isChecked && !suppressExperimentConditionSignals) {
                    applyExperimentConditionSelection(showCooldown = true)
                }
            }
            toggleDistance.addOnButtonCheckedListener { _, _, isChecked ->
                if (isChecked && !suppressExperimentConditionSignals) {
                    applyExperimentConditionSelection(showCooldown = true)
                }
            }
            
            btnProceedToAuth.setOnClickListener {
                startActivity(Intent(this, AuthenticateActivity::class.java))
            }
            
            btnRetakeReg.setOnClickListener {
                // Return to clean state
                experimentCompletionGroup.visibility = View.GONE
                inputExperimentTag.isEnabled = true
                experimentConditionsGroup.visibility = View.VISIBLE
                btnCapture.visibility = View.VISIBLE
                progressSlot.visibility = View.GONE
                topMessage.text = "Retake Registration"
                overlayView.statusText = getString(R.string.align_in_box)
                updateCaptureReadyState()
            }
            
            btnCancelReg.setOnClickListener {
                if (regRunning.get()) {
                    logExperimentRegistrationStatus(
                        status = "cancelled",
                        failureReason = "user_cancelled",
                        tagNameOverride = ExperimentStore.getCurrentTagName(this)
                    )
                }
                ExperimentStore.resetSession(this)
                mainHandler.post {
                    regRunning.set(false)
                    progressSlot.visibility = View.GONE
                    btnCapture.visibility = View.VISIBLE
                    inputExperimentTag.text = null
                    inputExperimentTag.isEnabled = true
                    experimentConditionsGroup.visibility = View.VISIBLE
                    topMessage.text = "Ready"
                    overlayView.statusText = getString(R.string.align_in_box)
                    lockAeAwb(false)
                    if (!demoMode) setTorch(false)
                    updateCaptureReadyState()
                }
            }
        }

        nightMode = intent?.getBooleanExtra(EXTRA_NIGHT_MODE, false) == true
        flashCheckRegister.isChecked = nightMode

        // Slot spinner (1..50) + remember selection
        val slotLabels = (1..50).map { getString(R.string.pattern_n, it) }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_large, slotLabels)
        adapter.setDropDownViewResource(R.layout.spinner_item_large)
        spnSlot.adapter = adapter
        spnSlot.setSelection((currentSlot - 1).coerceIn(0, 49))
        spnSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSlot = position + 1
                ModelManager.setActiveSlot(currentSlot)
                val present = ModelManager.hasModel(this@RegisterActivity, currentSlot)
                if (present) {
                    slotStatusText.text = "✓ Enrolled"
                    slotStatusText.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                } else {
                    slotStatusText.text = "— Empty"
                    slotStatusText.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                }
                Log.d("TapeWear_Reg", "Active registration slot set to $currentSlot")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        progressSlot.visibility = View.GONE
        btnAuth.visibility      = View.GONE
        overlayView.statusText  = getString(R.string.align_in_box)
        refreshHeader()
        flashCheckRegister.setOnCheckedChangeListener { _, b ->
            nightMode = b
            refreshHeader()
        }

        // Dirs
        sessionDir = File(cacheDir, "session_${System.currentTimeMillis()}").apply { mkdirs() }

        btnCapture.setOnClickListener {
            // Replaced logic inside startRegistrationCapture to handle setting night mode and validation.
            boostLiveDetectionWindow()
            startRegistrationCapture()
        }

        // Authenticate button: jump straight to AuthenticateActivity, using current slot
        btnAuth.text = getString(R.string.authenticate_at_register)
        btnAuth.setOnClickListener {
            // Make sure active slot is consistent
            ModelManager.setActiveSlot(currentSlot)
            startActivity(Intent(this, AuthenticateActivity::class.java))
        }

        if (demoMode) {
            Log.d("TapeWear_Reg", "RegisterActivity in DEMO mode using asset video")
            textureView.visibility = View.GONE
            demoImage.visibility = View.VISIBLE
            flashHint.visibility = View.GONE
        } else {
            demoImage.visibility = View.GONE
        }

        if (demoMode && ModelManager.detector == null) {

            Log.w("TapeWear_Reg", "DEMO mode active but YOLO detector is not initialized.")
        }

        // Camera choice (only if not in demo)
        if (!demoMode) {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.first()

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            torchTelemetry.configure(cameraId, hasFlash)

            // Choose optimal preview size from camera capabilities
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map != null) {
                val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
                if (outputSizes != null && outputSizes.isNotEmpty()) {
                    previewSize = chooseOptimalSize(outputSizes, textureView.width, textureView.height)
                    Log.d("TapeWear_Reg", "Selected preview size: ${previewSize.width}x${previewSize.height}")
                }
            }

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    configureTransform(w, h)
                    startWhenReady()
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

        // Reset experiment UI to fresh state when returning from auth
        if (AuthConfig.EXPERIMENT_MODE) {
            syncExperimentConditionUiFromConfig()
            applyExperimentConditionSelection(showCooldown = false)
            val inputTag = findViewById<android.widget.EditText>(R.id.inputExperimentTag)
            val conditionsGroup = findViewById<View>(R.id.experimentConditionsGroup)
            val completionGroup = findViewById<View>(R.id.experimentCompletionGroup)

            inputTag?.setText("")
            inputTag?.isEnabled = true
            conditionsGroup?.visibility = View.VISIBLE
            completionGroup?.visibility = View.GONE
            findViewById<View>(R.id.btnCancelReg)?.visibility = View.GONE
            btnCapture.visibility = View.VISIBLE
            progressSlot.visibility = View.GONE
            stagesTextReg.visibility = View.GONE
            topMessage.text = "Ready"
            overlayView.statusText = getString(R.string.align_in_box)
            regRunning.set(false)
            beginInteractionCooldown("Applying updated settings... Please wait.", 300L)
            updateCaptureReadyState()
        }

        if (demoMode) {
            try {
                videoSource = VideoFrameSource(this, demoAssetName)
                currentVideoTimeMs = 0L
                Log.d("TapeWear_Reg", "DEMO: video source created ($demoAssetName)")
            } catch (e: Exception) {
                videoSource = null
                Log.e("TapeWear_Reg", "DEMO video open failed: ${e.message}")
                overlayView.statusText = "Demo video missing"
                toast("Demo video not found")
            }
        }

        backgroundThread = HandlerThread("ImageProcessor").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (!demoMode && textureView.isAvailable) startWhenReady()

        if (demoMode) {
            primeDemoFrameAsync()
            startDemoLiveDetectionLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        demoUiActive = false
        stopDemoLiveDetectionLoop()

        if (demoMode) {
            val toClose = videoSource
            videoSource = null
            Thread {
                try {
                    toClose?.close()
                } catch (e: Exception) {
                    Log.w("TapeWear_Reg", "Failed to close demo video source: ${e.message}")
                }
            }.start()
        }

        releaseDemoImage()

        if (!demoMode) {
            setTorch(false)
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
            torchTelemetry.unregister()
        }

        shutdownBackgroundThreadAsync()
        overlayView.clearLiveDetections()
        liveDetectRunning.set(false)
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
                Log.w("TapeWear_Reg", "Interrupted while stopping background thread", e)
            }
        }.start()
    }

    // --- Camera bring-up ---
    private fun startWhenReady() {
        if (demoMode) return
        if (!hasCamPerm()) {
            perms.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (!textureView.isAvailable) return
        openCamera()
    }

    private fun hasCamPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; startPreview() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) { device.close(); cameraDevice = null }
            }, backgroundHandler)
        } catch (_: SecurityException) {
            toast(getString(R.string.err_perm_missing))
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

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    reqBuilder?.build()?.let { built ->
                        s.setRepeatingRequest(built, null, mainHandler)
                    }
                    overlayView.statusText = getString(R.string.align_and_tap)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast(getString(R.string.err_preview))
                }
            },
            backgroundHandler
        )
    }

    // --- Header ---
    private fun refreshHeader() {
        val torchEnabledForDim = !AuthConfig.EXPERIMENT_MODE || AuthConfig.EXPERIMENT_FLASH_ENABLED
        topMessage.text = when {
            nightMode && AuthConfig.EXPERIMENT_MODE && !AuthConfig.EXPERIMENT_FLASH_ENABLED ->
                "Night registration • Flash OFF"
            nightMode -> getString(R.string.register_night_torch)
            else -> getString(R.string.register_day)
        }
        val showTorchHint = !demoMode && nightMode && torchEnabledForDim
        flashHint.visibility = if (showTorchHint) View.VISIBLE else View.GONE
        flashHint.text = if (showTorchHint) getString(R.string.torch_on) else ""
        Log.d("TapeWear_Reg", "Header refreshed: nightMode=$nightMode, demoMode=$demoMode")
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
        updateCaptureReadyState()
    }

    private fun syncExperimentConditionUiFromConfig() {
        suppressExperimentConditionSignals = true
        try {
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIllumination)
                ?.check(if (AuthConfig.EXPERIMENT_ILLUMINATION == "dim") R.id.btnCondDim else R.id.btnCondBright)
            findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistance)
                ?.check(if (AuthConfig.EXPERIMENT_DISTANCE == "far") R.id.btnCondFar else R.id.btnCondNear)
        } finally {
            suppressExperimentConditionSignals = false
        }
    }

    private fun selectedExperimentIllumination(): String {
        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIllumination)
        return if (toggle?.checkedButtonId == R.id.btnCondDim) "dim" else "bright"
    }

    private fun selectedExperimentDistance(): String {
        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistance)
        return if (toggle?.checkedButtonId == R.id.btnCondFar) "far" else "near"
    }

    private fun isInteractionCoolingDown(): Boolean =
        SystemClock.elapsedRealtime() < interactionReadyAtMs

    private fun beginInteractionCooldown(message: String, durationMs: Long = 350L) {
        interactionReadyAtMs = SystemClock.elapsedRealtime() + durationMs
        if (!regRunning.get()) {
            overlayView.statusText = message
            topMessage.text = message
        }
        updateCaptureReadyState()
        mainHandler.postDelayed({
            if (!regRunning.get() && !isInteractionCoolingDown()) {
                topMessage.text = "Ready"
                overlayView.statusText = getString(R.string.align_in_box)
                updateCaptureReadyState()
            }
        }, durationMs)
    }

    private fun applyExperimentConditionSelection(showCooldown: Boolean) {
        val illumination = selectedExperimentIllumination()
        val distance = selectedExperimentDistance()
        val changed =
            AuthConfig.EXPERIMENT_ILLUMINATION != illumination ||
                AuthConfig.EXPERIMENT_DISTANCE != distance
        AuthConfig.EXPERIMENT_ILLUMINATION = illumination
        AuthConfig.EXPERIMENT_DISTANCE = distance
        nightMode = illumination == "dim"
        if (changed) {
            SettingsStore.save(this)
        }
        refreshHeader()
        refreshRuntimeConfigUi()
        if (showCooldown) {
            beginInteractionCooldown("Applying condition... Please wait.", 300L)
        }
    }

    private fun experimentTagInput(): String {
        val input = findViewById<TextView>(R.id.inputExperimentTag) ?: return ""
        return ExperimentStore.normalizeTag(input.text?.toString().orEmpty())
    }

    private fun updateCaptureReadyState() {
        if (!::btnCapture.isInitialized || regRunning.get()) return
        btnCapture.isEnabled = if (AuthConfig.EXPERIMENT_MODE) {
            !isInteractionCoolingDown() && isYoloReady() && experimentTagInput().isNotEmpty()
        } else {
            !isInteractionCoolingDown() && isYoloReady()
        }
    }

    private fun ensureExperimentStudyReady(): Boolean {
        if (!AuthConfig.EXPERIMENT_MODE) return true
        val error = ExperimentStore.studySetupError(this) ?: return true
        toast(error)
        overlayView.statusText = "Set study metadata in Settings"
        return false
    }

    private fun logExperimentRegistrationStatus(
        status: String,
        failureReason: String,
        keptSamples: Int = 0,
        usedForEnroll: Int = 0,
        tagNameOverride: String? = null
    ) {
        if (!AuthConfig.EXPERIMENT_MODE) return
        val now = SystemClock.elapsedRealtime()
        val totalMs = if (regSessionStartMs > 0L) now - regSessionStartMs else 0L
        MetricsLogger.logRegistration(
            ctx = this,
            slot = currentSlot,
            regTotalMs = totalMs,
            keptSamples = keptSamples,
            usedForEnroll = usedForEnroll,
            nightMode = nightMode,
            demoMode = demoMode,
            overhead = regOverhead,
            flashTelemetry = torchTelemetry.snapshot(),
            trialStatus = status,
            failureReason = failureReason,
            tagNameOverride = tagNameOverride
        )
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
                Log.e("TapeWear_Reg", "Model init in Register failed: ${e.message}")
            } finally {
                val dt = SystemClock.elapsedRealtime() - t0
                Log.i("TapeWear_Reg", "Model warmup complete in ${dt}ms (threads=$threads)")
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

        if (regRunning.get()) return

        // Hands-free auto-trigger: start registration when enough consecutive detections
        if (AuthConfig.HANDS_FREE_ENABLED
            && seenInGuide
            && intentHitStreak >= AuthConfig.HANDS_FREE_CONSECUTIVE_HITS
            && !regRunning.get()
            && isYoloReady()
            && (!AuthConfig.EXPERIMENT_MODE || experimentTagInput().isNotEmpty())
        ) {
            Log.d("TapeWear_Reg", "Hands-free: auto-triggering registration (streak=$intentHitStreak)")
            mainHandler.post {
                if (!regRunning.get()) {
                    nightMode = flashCheckRegister.isChecked
                    boostLiveDetectionWindow()
                    startRegistrationCapture()
                }
            }
            return
        }

        mainHandler.post {
            if (regRunning.get()) return@post
            overlayView.statusText = when {
                AuthConfig.HANDS_FREE_ENABLED && seenInGuide -> "Pattern detected ($intentHitStreak/${AuthConfig.HANDS_FREE_CONSECUTIVE_HITS})…"
                seenInGuide -> "Pattern seen in guide. Tap Capture"
                intentActive -> "Hold pattern in guide"
                else -> getString(R.string.align_in_box)
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

    // --- Registration flow ---
    private fun startRegistrationCapture() {
        torchTelemetry.resetAttempt()
        if (regRunning.getAndSet(true)) return
        if (!isYoloReady()) {
            regRunning.set(false)
            overlayView.statusText = "YOLO unavailable"
            topMessage.text = "YOLO model not loaded"
            toast("YOLO detector is required")
            return
        }

        if (!ensureExperimentStudyReady()) {
            regRunning.set(false)
            updateCaptureReadyState()
            return
        }

        val inputExperimentTag = findViewById<TextView>(R.id.inputExperimentTag)
        val experimentConditionsGroup = findViewById<View>(R.id.experimentConditionsGroup)
        var tagName = ""

        if (AuthConfig.EXPERIMENT_MODE) {
            tagName = experimentTagInput()
            if (tagName.isEmpty()) {
                regRunning.set(false)
                logExperimentRegistrationStatus(
                    status = "rejected",
                    failureReason = "missing_tag_name"
                )
                toast("Please enter a Pattern Tag Name")
                inputExperimentTag.requestFocus()
                updateCaptureReadyState()
                return
            }
            // Check uniqueness only if it's the very first time (it hasn't already been mapped in this session)
            if (ExperimentStore.getCurrentTagName(this) != tagName && ExperimentStore.isTagRegistered(this, tagName)) {
                regRunning.set(false)
                logExperimentRegistrationStatus(
                    status = "rejected",
                    failureReason = "duplicate_tag_name",
                    tagNameOverride = tagName
                )
                toast("Tag '$tagName' is already registered. Use a unique name.")
                inputExperimentTag.requestFocus()
                updateCaptureReadyState()
                return
            }
            
            val toggleIllumination = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleIllumination)
            val toggleDistance = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDistance)
            
            AuthConfig.EXPERIMENT_ILLUMINATION = if (toggleIllumination.checkedButtonId == R.id.btnCondDim) "dim" else "bright"
            AuthConfig.EXPERIMENT_DISTANCE = if (toggleDistance.checkedButtonId == R.id.btnCondFar) "far" else "near"
            SettingsStore.save(this) // Persist condition choice
            // Dim mode is our "night mode" flag for camera logic
            nightMode = AuthConfig.EXPERIMENT_ILLUMINATION == "dim"
            
            ExperimentStore.setCurrentTagName(this, tagName)
            try {
                currentSlot = ExperimentStore.getSlotForTag(this, tagName)
            } catch (e: IllegalStateException) {
                regRunning.set(false)
                logExperimentRegistrationStatus(
                    status = "rejected",
                    failureReason = "slot_limit_reached",
                    tagNameOverride = tagName
                )
                toast(e.message ?: "No experiment slots remaining.")
                updateCaptureReadyState()
                return
            }
            ModelManager.setActiveSlot(currentSlot)
            
            // Clean UI
            inputExperimentTag.isEnabled = false
            experimentConditionsGroup.visibility = View.GONE
            findViewById<Button>(R.id.btnCancelReg).visibility = View.VISIBLE
        } else {
            nightMode = flashCheckRegister.isChecked
            findViewById<Button>(R.id.btnCancelReg).visibility = View.GONE
        }

        // regSessionStartMs initialized safely in background thread
        regSessionStartMs = 0L
        regSettleMs = 0L
        regCaptureMs = 0L
        regQualityMs = 0L
        regEnrollMs = 0L

        btnCapture.isEnabled = false
        btnCapture.visibility = View.GONE
        progressSlot.visibility = View.VISIBLE
        btnAuth.visibility = View.GONE
        findViewById<View>(R.id.experimentCompletionGroup)?.visibility = View.GONE
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressLine.text = getString(R.string.preparing)
        progressLine.visibility = View.VISIBLE
        topMessage.text = "Need ${AuthConfig.REG_TARGET_FRAMES} good frames"
        overlayView.statusText = getString(R.string.hold_steady)
        flashCheckRegister.visibility = View.GONE
        stagesTextReg.visibility = View.GONE

        val useFlash = if (AuthConfig.EXPERIMENT_MODE) nightMode && AuthConfig.EXPERIMENT_FLASH_ENABLED else nightMode
        if (!demoMode && useFlash && hasFlash) setTorch(true)


        Log.d("TapeWear_Reg", "Starting registration capture (slot=$currentSlot, night=$nightMode)")
        
        regOverhead = MetricsLogger.OverheadSnapshot()

        val settleMs = if (demoMode) 0L else 100L
        val settleStart = SystemClock.elapsedRealtime()
        mainHandler.postDelayed({
            regSettleMs = SystemClock.elapsedRealtime() - settleStart
            lockAeAwb(true)
            runRegistrationBurst()
        }, settleMs)
    }

    private fun runRegistrationBurst() {
        val totalMs = AuthConfig.REG_BURST_MS
        val stepMs  = 200L
        val kept = ArrayList<Sample>()
        var prevFrame: Bitmap? = null
        currentVideoTimeMs = 0L

        Log.d("TapeWear_Reg", "Starting registration burst (night=$nightMode, demo=$demoMode)")

        val bg = backgroundHandler
        if (bg == null) {
            logExperimentRegistrationStatus(
                status = "failed",
                failureReason = "background_thread_unavailable",
                keptSamples = kept.size,
                tagNameOverride = ExperimentStore.getCurrentTagName(this)
            )
            regRunning.set(false)
            overlayView.statusText = "Processing unavailable"
            btnCapture.isEnabled = isYoloReady()
            btnCapture.visibility = View.VISIBLE
            progressSlot.visibility = View.GONE
            flashCheckRegister.visibility = View.VISIBLE
            stagesTextReg.visibility = View.GONE
            return
        }

        val run = object : Runnable {
            var firstRun = true
            
            override fun run() {
                if (firstRun) {
                    regSessionStartMs = SystemClock.elapsedRealtime()
                    firstRun = false
                }
                
                val elapsed = SystemClock.elapsedRealtime() - regSessionStartMs

                val tCap0 = SystemClock.elapsedRealtime()
                val frame = snapshotCurrent()
                val tCap1 = SystemClock.elapsedRealtime()
                regCaptureMs += (tCap1 - tCap0)
                if (frame != null) {
                    val tQual0 = SystemClock.elapsedRealtime()
                    val detOutcome = detectFrame(frame, updateOverlay = true)
                    if (!detOutcome.hasDetection) {
                        mainHandler.post {
                            topMessage.text = "No YOLO detection above ${"%.2f".format(Locale.US, AuthConfig.YOLO_CONF_THRESHOLD)}"
                        }
                        frame.recycle()
                    } else {
                        val luma = ImageUtils.meanLuma(frame)
                        val blur = ImageUtils.blurMetric(frame)
                        val motion = prevFrame?.let { ImageUtils.meanAbsDiff(it, frame) } ?: 0.0

                        prevFrame?.recycle()
                        prevFrame = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)

                        val assessment = Quality.assess(luma, blur, motion, nightMode)
                        Log.d(
                            "TapeWear_Reg",
                            "YOLO(found)->Quality(full-frame): det=${detOutcome.detections.size}, luma=%.1f, blur=%.1f, motion=%.1f -> pass=${assessment.pass}"
                                .format(luma, blur, motion)
                        )

                        mainHandler.post { topMessage.text = assessment.hint }

                        if (assessment.pass) {
                            kept.add(Sample(frame, blur, luma, SystemClock.elapsedRealtime()))
                        } else {
                            frame.recycle()
                        }
                    }
                    val tQual1 = SystemClock.elapsedRealtime()
                    regQualityMs += (tQual1 - tQual0)
                }

                mainHandler.post {
                    val pct = (elapsed.toFloat() / totalMs * 100).coerceIn(0f, 100f).toInt()
                    progressBar.progress = pct
                    progressLine.text = "${getString(R.string.registering_percent, pct)} (${kept.size}/${AuthConfig.REG_TARGET_FRAMES} good)"
                }

                // Early stop once we have enough good frames
                if (kept.size >= AuthConfig.REG_TARGET_FRAMES) {
                    Log.d(
                        "TapeWear_Reg",
                        "Reached target frames=${AuthConfig.REG_TARGET_FRAMES}, stopping burst early (kept=${kept.size})"
                    )
                    prevFrame?.recycle()
                    finishRegistration(kept)
                    return
                }

                if (elapsed < totalMs) {
                    backgroundHandler?.postDelayed(this, stepMs)
                } else {
                    prevFrame?.recycle()
                    Log.d("TapeWear_Reg", "Registration burst finished, collected ${kept.size} samples.")
                    finishRegistration(kept)
                }
            }
        }
        bg.post(run)
    }

    private fun saveDetectionsDebug(frames: List<Bitmap>) {
        val det = ModelManager.detector ?: return

        val outDir = File(sessionDir, "slot_${"%02d".format(currentSlot)}_bboxes").apply { mkdirs() }

        val boxPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        frames.forEachIndexed { idx, bmp ->
            try {
                val dets = det.detect(bmp)
                if (dets.isEmpty()) return@forEachIndexed

                val boxed = bmp.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(boxed)

                dets.forEach { d ->
                    canvas.drawRect(d.box, boxPaint)
                }

                val f = File(outDir, "slot${"%02d".format(currentSlot)}_frame${"%03d".format(idx)}.jpg")
                FileOutputStream(f).use { out ->
                    boxed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                boxed.recycle()
            } catch (e: Exception) {
                Log.w("TapeWear_Reg", "saveDetectionsDebug failed for one frame: ${e.message}")


            }
        }
    }

    private fun finishRegistration(kept: List<Sample>) {
        Log.d("TapeWear_Reg", "finishRegistration: processing ${kept.size} samples for slot $currentSlot")
        lockAeAwb(false)
        if (!demoMode) setTorch(false)

        val targetFrames = AuthConfig.REG_TARGET_FRAMES
        if (kept.size < targetFrames) {
            Log.w("TapeWear_Reg", "Registration failed: only collected ${kept.size} / $targetFrames frames")
            logExperimentRegistrationStatus(
                status = "failed",
                failureReason = "insufficient_good_frames",
                keptSamples = kept.size,
                tagNameOverride = ExperimentStore.getCurrentTagName(this)
            )
            mainHandler.post {
                kept.forEach { it.bmp.recycle() }

                overlayView.statusText = getString(R.string.saved_frames_no_model, kept.size)
                topMessage.text = "Failed: Need $targetFrames good frames"
                progressBar.progress = 100
                progressBar.visibility = View.GONE
                progressLine.visibility = View.GONE
                stagesTextReg.visibility = View.GONE

                if (AuthConfig.EXPERIMENT_MODE) {
                    // Failed reg: release the reserved tag so it can be re-used
                    val failedTag = ExperimentStore.getCurrentTagName(this@RegisterActivity)
                    if (failedTag != null) {
                        ExperimentStore.releaseUnconfirmedTag(this@RegisterActivity, failedTag)
                    }
                    // Show completion group with only Retake visible (no Proceed)
                    findViewById<View>(R.id.experimentCompletionGroup).visibility = View.VISIBLE
                    findViewById<Button>(R.id.btnProceedToAuth).visibility = View.GONE
                } else {
                    btnAuth.visibility = View.VISIBLE
                    btnCapture.visibility = View.VISIBLE
                    flashCheckRegister.visibility = View.VISIBLE
                }
                
                btnCapture.isEnabled = isYoloReady()
                resetLiveDetectionPacing()
                regRunning.set(false)
            }
            return
        }

        // Run heavy work on the processing thread so UI scheduling does not inflate core latency.
        val bg = backgroundHandler
        if (bg == null) {
            logExperimentRegistrationStatus(
                status = "failed",
                failureReason = "background_thread_unavailable",
                keptSamples = kept.size,
                tagNameOverride = ExperimentStore.getCurrentTagName(this)
            )
            mainHandler.post {
                kept.forEach { it.bmp.recycle() }
                overlayView.statusText = "Processing unavailable"
                topMessage.text = "Registration failed"
                progressBar.visibility = View.GONE
                progressLine.visibility = View.GONE
                stagesTextReg.visibility = View.GONE
                btnAuth.visibility = View.VISIBLE
                btnCapture.visibility = View.VISIBLE
                flashCheckRegister.visibility = View.VISIBLE
                btnCapture.isEnabled = isYoloReady()
                resetLiveDetectionPacing()
                regRunning.set(false)
            }
            return
        }

        val work = Runnable {
            val frames = kept.map { it.bmp }
            var usedForEnroll = 0

            try {
                ModelManager.setActiveSlot(currentSlot)

                // [LATENCY FIX] Disabled redundant YOLO box rendering used for offline debugging
                // saveDetectionsDebug(frames)

                val t0 = SystemClock.elapsedRealtime()
                usedForEnroll = ModelManager.enrollFromBitmaps(
                    context = this,
                    frames = frames,
                    maxEmbeds = 32,
                    slot = currentSlot
                )
                val t1 = SystemClock.elapsedRealtime()
                regEnrollMs = t1 - t0
                Log.d(
                    "TapeWear_Reg",
                    "Enrollment created from $usedForEnroll frames in ${t1 - t0} ms."
                )
            } catch (e: Exception) {
                mainHandler.post {
                    overlayView.statusText = getString(R.string.err_enroll, e.message ?: "")
                }
                Log.e("TapeWear_Reg", "Enrollment failed: ${e.message}", e)
            }

            // Raw registration crop dumps stay disabled to avoid extra I/O during capture.

            // Metrics
            val now = SystemClock.elapsedRealtime()
            val regTotalMs = if (regSessionStartMs > 0L) now - regSessionStartMs else 0L

            if (usedForEnroll > 0) {
                MetricsLogger.logRegistration(
                    ctx = this,
                    slot = currentSlot,
                    regTotalMs = regTotalMs,
                    keptSamples = kept.size,
                    usedForEnroll = usedForEnroll,
                    nightMode = nightMode,
                    demoMode = demoMode,
                    overhead = regOverhead,
                    flashTelemetry = torchTelemetry.snapshot()
                )
            } else {
                MetricsLogger.logRegistration(
                    ctx = this,
                    slot = currentSlot,
                    regTotalMs = regTotalMs,
                    keptSamples = kept.size,
                    usedForEnroll = usedForEnroll,
                    nightMode = nightMode,
                    demoMode = demoMode,
                    overhead = regOverhead,
                    flashTelemetry = torchTelemetry.snapshot(),
                    trialStatus = "failed",
                    failureReason = "enrollment_not_completed",
                    tagNameOverride = ExperimentStore.getCurrentTagName(this)
                )
                Log.w("TapeWear_Reg", "Registration metrics logged as failed: enrollment did not complete.")
            }

            // Post all UI updates back to main thread
            val finalUsed = usedForEnroll
            val framesCollectedCount = kept.size
            val finalRegTotalMs = regTotalMs
            mainHandler.post {
                kept.forEach { it.bmp.recycle() }

                overlayView.statusText = if (finalUsed > 0)
                    getString(R.string.saved_frames_and_model, framesCollectedCount, finalUsed)
                else
                    getString(R.string.saved_frames_no_model, framesCollectedCount)

                topMessage.text = if (finalUsed > 0) getString(R.string.registration_complete) else "Registration failed"
                progressBar.progress = 100
                progressBar.visibility = View.GONE
                progressLine.visibility = View.GONE
                if (finalUsed > 0) {
                    stagesTextReg.text =
                        "settle: ${regSettleMs}ms | capture: ${regCaptureMs}ms | quality: ${regQualityMs}ms\n" +
                        "enroll: ${regEnrollMs}ms | total: ${finalRegTotalMs}ms"
                    stagesTextReg.visibility = View.VISIBLE
                } else {
                    stagesTextReg.visibility = View.GONE
                }

                if (finalUsed > 0 && AuthConfig.EXPERIMENT_MODE) {
                    val tagName = ExperimentStore.getCurrentTagName(this@RegisterActivity) ?: "unknown"
                    // NOW persist the tag-to-slot mapping since enrollment succeeded
                    ExperimentStore.confirmTagRegistration(this@RegisterActivity, tagName)
                    val encStore = EnrollmentStore.load(this@RegisterActivity, currentSlot)
                    if (encStore != null) {
                        ExperimentStore.saveEnrollmentEmbeddingForCrossAuth(this@RegisterActivity, tagName, encStore.mean)
                    }
                } else if (finalUsed == 0 && AuthConfig.EXPERIMENT_MODE) {
                    // Enrollment did not produce a valid model — release the reserved tag
                    val failedTag = ExperimentStore.getCurrentTagName(this@RegisterActivity)
                    if (failedTag != null) {
                        ExperimentStore.releaseUnconfirmedTag(this@RegisterActivity, failedTag)
                    }
                }

                if (AuthConfig.EXPERIMENT_MODE) {
                    findViewById<View>(R.id.experimentCompletionGroup).visibility = View.VISIBLE
                    findViewById<Button>(R.id.btnProceedToAuth).visibility = if (finalUsed > 0) View.VISIBLE else View.GONE
                } else {
                    btnAuth.visibility = View.VISIBLE
                    btnCapture.visibility = View.VISIBLE
                    flashCheckRegister.visibility = View.VISIBLE
                }
                btnCapture.isEnabled = isYoloReady()
                resetLiveDetectionPacing()
                regRunning.set(false)

                // Provide a backup screenshot of the results
                if (AuthConfig.EXPERIMENT_MODE) {
                    mainHandler.postDelayed({
                        try {
                            val tagRaw = ExperimentStore.getCurrentTagName(this@RegisterActivity) ?: "unknown"
                            val tag = tagRaw.replace(Regex("[^a-zA-Z0-9_]"), "")
                            val ts = System.currentTimeMillis()
                            val filename = "reg_${tag}_${ts}.jpg"
                            ScreenshotUtils.takeScreenshot(this@RegisterActivity, filename)
                        } catch(e: Exception) {
                            Log.e("TapeWear_Reg", "Failed to init screenshot: ${e.message}")
                        }
                    }, 250) // Wait for UI updates to paint
                }
            }
        }

        if (Looper.myLooper() == bg.looper) {
            work.run()
        } else {
            bg.post(work)
        }
    }

    private data class FrameDetectionOutcome(
        val detections: List<ModelManager.Detection>,
        val hasDetection: Boolean,
        val insideOverlayCount: Int
    )

    private fun maybeRunLiveDetectionFromPreview() {
        val now = SystemClock.elapsedRealtime()
        if (regRunning.get()) return
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
                Log.w("TapeWear_Reg", "Live YOLO failed: ${e.message}")
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

                if (!regRunning.get() && liveDetectRunning.compareAndSet(false, true)) {
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
                                Log.w("TapeWear_Reg", "Demo live YOLO failed: ${e.message}")
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
                Log.w("TapeWear_Reg", "Failed to prime demo frame: ${e.message}")
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

    // --- Helpers ---
    private fun setTorch(on: Boolean) {
        if (hasFlash) try {
            cameraManager.setTorchMode(cameraId, on)
        } catch (_: Exception) {
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
            val vs = videoSource ?: return null

            val raw = vs.frameAt(currentVideoTimeMs) ?: return null
            currentVideoTimeMs += videoFrameStepMs

            // Prevent huge 8MB bitmaps from piling up and triggering slow GCs
            val scaledPipeline = raw.scale(640, 640, filter = true)
            val scaledUi = scaledPipeline.copy(Bitmap.Config.ARGB_8888, false)
            raw.recycle() // Free heavy native memory immediately

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

    /**
     * Choose the largest camera preview size whose width ≤ maxW
     * that best matches the display aspect ratio, capped at 1280px.
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

    private fun currentDisplayRotation(): Int = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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

        // Rotate the buffer rect to match the display orientation
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
            // For portrait (ROTATION_0) and upside-down (ROTATION_180)
            // Camera sensor is landscape; we need to scale to fill portrait view
            val scaleX = viewWidth.toFloat() / previewSize.height.toFloat()
            val scaleY = viewHeight.toFloat() / previewSize.width.toFloat()
            val scale = minOf(scaleX, scaleY)
            matrix.setScale(scale, scale, centerX, centerY)
            // Compensate the offset to center the scaled image
            val scaledW = previewSize.height * scale
            val scaledH = previewSize.width * scale
            matrix.postTranslate((viewWidth - scaledW) / 2f - (centerX - scaledW / 2f),
                                 (viewHeight - scaledH) / 2f - (centerY - scaledH / 2f))
        }

        textureView.setTransform(matrix)
        overlayView.setPreviewContentRect(getPreviewContentRect(viewWidth, viewHeight))
        Log.d("TapeWear_Reg", "Camera transform applied: preview=${previewSize.width}x${previewSize.height}, view=${viewWidth}x${viewHeight}")
    }

    private data class Sample(val bmp: Bitmap, val blur: Double, val luma: Double, val ts: Long)

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
