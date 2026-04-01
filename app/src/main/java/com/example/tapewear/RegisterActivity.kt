package com.example.tapewear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var cropsDir: File

    // Camera2
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String = "0"
    private var previewSize = Size(640, 480)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasFlash = false
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
        setContentView(R.layout.activity_register)

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
        cropsDir   = File(sessionDir, "crops").apply { mkdirs() }

        btnCapture.setOnClickListener {
            nightMode = flashCheckRegister.isChecked
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

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startWhenReady()
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    maybeRunLiveDetectionFromPreview()
                }
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

        if (!demoMode && textureView.
        isAvailable) startWhenReady()

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
        topMessage.text = if (nightMode) {
            getString(R.string.register_night_torch)
        } else {
            getString(R.string.register_day)
        }
        flashHint.visibility = if (!demoMode && nightMode) View.VISIBLE else View.GONE
        flashHint.text = if (nightMode && !demoMode) getString(R.string.torch_on) else ""
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
        if (::btnCapture.isInitialized && !regRunning.get()) {
            btnCapture.isEnabled = isYoloReady()
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
        mainHandler.post {
            if (regRunning.get()) return@post
            overlayView.statusText = when {
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
        if (regRunning.getAndSet(true)) return
        if (!isYoloReady()) {
            regRunning.set(false)
            overlayView.statusText = "YOLO unavailable"
            topMessage.text = "YOLO model not loaded"
            toast("YOLO detector is required")
            return
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
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressLine.text = getString(R.string.preparing)
        progressLine.visibility = View.VISIBLE
        topMessage.text = "Need ${AuthConfig.REG_TARGET_FRAMES} good frames"
        overlayView.statusText = getString(R.string.hold_steady)
        flashCheckRegister.visibility = View.GONE
        stagesTextReg.visibility = View.GONE

        if (!demoMode && nightMode && hasFlash) setTorch(true)


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
        if (!demoMode && nightMode) setTorch(false)

        val targetFrames = AuthConfig.REG_TARGET_FRAMES
        if (kept.size < targetFrames) {
            Log.w("TapeWear_Reg", "Registration failed: only collected ${kept.size} / $targetFrames frames")
            mainHandler.post {
                kept.forEach { it.bmp.recycle() }

                overlayView.statusText = getString(R.string.saved_frames_no_model, kept.size)
                topMessage.text = "Failed: Need $targetFrames good frames"
                progressBar.progress = 100
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

        // Run heavy work on the processing thread so UI scheduling does not inflate core latency.
        val bg = backgroundHandler
        if (bg == null) {
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

            // [LATENCY FIX] Disabled raw bitmap dumps to flash storage
            // val best = kept.sortedByDescending { it.blur }.take(48)
            // val toSave = if (best.size >= 32) best else kept
            // toSave.forEachIndexed { idx, s ->
            //     try {
            //         val f = File(cropsDir, "reg_${currentSlot}_${idx}_${s.ts}.jpg")
            //         FileOutputStream(f).use { out ->
            //             s.bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            //         }
            //         saved++
            //     } catch (e: Exception) {
            //         Log.w("TapeWear_Reg", "Failed to save one crop: ${e.message}")
            //     }
            // }
            // Log.d("TapeWear_Reg", "Saved $saved out of ${kept.size} crops for analysis.")

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
                    overhead = regOverhead
                )
            } else {
                Log.w("TapeWear_Reg", "Skipping registration metrics: enrollment did not complete.")
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

                btnAuth.visibility = View.VISIBLE
                btnCapture.visibility = View.VISIBLE
                flashCheckRegister.visibility = View.VISIBLE
                btnCapture.isEnabled = isYoloReady()
                resetLiveDetectionPacing()
                regRunning.set(false)
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

    // --- Helpers ---
    private fun setTorch(on: Boolean) {
        if (hasFlash) try {
            cameraManager.setTorchMode(cameraId, on)
        } catch (_: Exception) {}
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
            textureView.getBitmap(640, 640)
        }
    } catch (e: Exception) {
        Log.e("TapeWear", "snapshotCurrent failed: ${e.message}")
        null
    }

    private data class Sample(val bmp: Bitmap, val blur: Double, val luma: Double, val ts: Long)

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
