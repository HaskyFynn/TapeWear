package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import kotlin.math.abs
import kotlin.math.min

class AuthenticateActivity : AppCompatActivity() {

    // Demo video (used only when demoMode == true)
    private var videoSource: VideoFrameSource? = null
    private var lastDemoFrame: Bitmap? = null
    private var videoFrameStepMs: Long = 100L
    private var currentVideoTimeMs: Long = 0L

    // Manual demo flag (set true to force video-based demo)
    private var demoMode = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        verdictText.textSize = 24f
        verdictText.setTypeface(verdictText.typeface, Typeface.BOLD)
        verdictText.textAlignment = View.TEXT_ALIGNMENT_CENTER

        confidenceText.textSize = 18f
        confidenceText.textAlignment = View.TEXT_ALIGNMENT_CENTER

        // Slot spinner 1..10
        val slots = (1..10).map { getString(R.string.pattern_n, it) }
        spinnerPattern.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, slots)

        // Pre-select the active slot (last used in registration)
        val initialSlot = (ModelManager.
        getActiveSlot() - 1).coerceIn(0, 9)
        spinnerPattern.setSelection(initialSlot)

        spinnerPattern.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val slot = position + 1
                ModelManager.setActiveSlot(slot)
                val present = ModelManager.hasModel(this@AuthenticateActivity, slot)
                btnCapture.isEnabled = present
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
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        } else {
            // Pure demo: camera UI hidden, detector can still be YOLO if present
            Log.d("TapeWear_Auth", "AuthenticateActivity in DEMO mode using asset video")
            textureView.visibility = View.GONE
            demoImage.visibility = View.VISIBLE
            overlayView.statusText = getString(R.string.auth_hint_align)

            // If YOLO isn't available, fall back to overlay-only detector
            if (ModelManager.detector == null) {
                ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Background processing thread
        backgroundThread = HandlerThread("ImageProcessorAuth").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (!demoMode && textureView.isAvailable) {
            startWhenReady()
        }
    }

    override fun onPause() {
        super.onPause()

        // Release demo frames / video if used
        lastDemoFrame?.recycle()
        lastDemoFrame = null

        try {
            videoSource?.close()
        } catch (_: Exception) {}
        videoSource = null

        if (!demoMode) {
            setTorch(false)
            try { session?.close() } catch (_: Exception) {}
            session = null
            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null
        }

        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("TapeWear_Auth", "Failed to stop background thread", e)
        }
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

    // --- Auth burst ---
    private fun startAuthBurst() {
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

        authSessionStartMs = SystemClock.elapsedRealtime()
        val slot = ModelManager.getActiveSlot()
        MetricsLogger.logSystemSnapshot(this, "auth_start_slot_$slot")

        Log.d("TapeWear_Auth", "Starting auth burst (night=$night, demo=$demoMode)")

        mainHandler.postDelayed({
            lockAeAwb(true)
            runAuthBurst(night)
        }, 600)
    }

    private fun runAuthBurst(night: Boolean) {
        val totalMs = 17L
        val stepMs  = 220L
        val started = SystemClock.elapsedRealtime()
        val frames  = ArrayList<Bitmap>()
        var prevSmall: Bitmap? = null

        val run = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started

                val sample = snapshotCurrent()
                if (sample != null) {
                    val luma = meanLuma(sample)
                    val blur = blurMetric(sample)
                    val motion = prevSmall?.let { meanAbsDiff(it, sample) } ?: 0.0
                    prevSmall?.recycle()
                    prevSmall = sample.copy(sample.config ?: Bitmap.Config.ARGB_8888, false)

                    val assessment = Quality.assess(luma, blur, motion, night)
                    Log.d(
                        "TapeWear_Auth",
                        "Frame sample: luma=%.1f, blur=%.1f, motion=%.1f -> pass=${assessment.pass}"
                            .format(luma, blur, motion)
                    )

                    mainHandler.post {
                        overlayView.statusText =
                            "${getString(R.string.auth_holdsteady)} • ${assessment.hint}"
                    }

                    if (assessment.pass) {
                        frames.add(sample)
                    } else {
                        sample.recycle()
                    }
                }

                mainHandler.post {
                    val pct = (elapsed.toFloat() / totalMs * 100).coerceIn(0f, 100f).toInt()
                    progressBar.progress = pct
                    progressLine.text = getString(R.string.auth_authenticating_fmt, pct)
                }

                if (elapsed < totalMs) {
                    backgroundHandler?.postDelayed(this, stepMs)
                } else {
                    prevSmall?.recycle()
                    Log.d("TapeWear_Auth", "Auth burst finished, collected ${frames.size} frames.")
                    mainHandler.post { finishAuth(frames, night) }
                }
            }
        }
        backgroundHandler?.post(run)
    }

    private fun finishAuth(frames: MutableList<Bitmap>, night: Boolean) {
        Log.d("TapeWear_Auth", "finishAuth: processing ${frames.size} frames")
        lockAeAwb(false)
        if (!demoMode && night) setTorch(false)

        val top = frames
            .map { it to blurMetric(it) }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        val framesToScore = top.ifEmpty { frames }
        Log.d(
            "TapeWear_Auth",
            "Filtered to ${framesToScore.size} frames for scoring (totalFrames=${frames.size})"
        )

        val verdict = try {
            ModelManager.scoreFromBitmaps(
                context = this,
                frames = framesToScore,
                take = framesToScore.size.coerceAtLeast(1),
                slot = ModelManager.getActiveSlot()
            )
        } catch (e: Throwable) {
            Log.e("TapeWear_Auth", "Scoring failed: ${e.message}", e)
            ModelManager.Verdict(-1f, false)
        }

        val finalSim: Float
        val usedN: Int
        val isMatch: Boolean

        if (verdict.similarity >= 0f) {
            finalSim = verdict.similarity.coerceIn(0f, 1f)
            isMatch = verdict.isMatch
            usedN = framesToScore.size.coerceAtMost(5)

        } else {
            // Fallback – should not normally happen now
            finalSim = 0.5f
            isMatch = false
            usedN = 0
            Log.w("TapeWear_Auth", "Using fallback similarity")
        }

        // Compute burstMs and fps for logging
        val totalFrames = frames.size
        val now = SystemClock.elapsedRealtime()
        val burstMs = if (authSessionStartMs > 0L) now - authSessionStartMs else 0L
        val fps = if (burstMs > 0L && totalFrames > 0)
            totalFrames.toFloat() / (burstMs / 1000f)
        else
            0f

        Log.d(
            "TapeWear_Auth",
            "Auth verdict: match=$isMatch, similarity=%.3f, usedN=$usedN, burstMs=$burstMs, fps=%.1f"
                .format(finalSim, fps)
        )

        frames.forEach { it.recycle() }

        hideProgress()

        confidenceText.text = getString(
            R.string.auth_similarity_fmt,
            (finalSim * 100).toInt(),
            usedN,
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
            verdictText.text = getString(R.string.auth_verdict_nomatch)
            overlayView.statusText = getString(R.string.done)

            resultCard.visibility = View.VISIBLE
            resultCard.alpha = 0f
            resultCard.animate()
                .alpha(1f)
                .setDuration(180)
                .start()
        }

        val slot = ModelManager.getActiveSlot()

        // Metrics logging
        MetricsLogger.logAuthAttempt(
            ctx = this,
            slot = slot,
            similarity = finalSim,
            isMatch = isMatch,
            burstMs = burstMs,
            fps = fps
        )
        if (slot == 1) {
            MetricsLogger.logAuthSlot1Repeat(
                ctx = this,
                similarity = finalSim,
                isMatch = isMatch,
                burstMs = burstMs,
                fps = fps
            )
        }
        MetricsLogger.updateBestAuth(this, slot, finalSim)
        MetricsLogger.logSystemSnapshot(this, "auth_end_slot_$slot")

        btnCapture.isEnabled = ModelManager.hasModel(this, slot)
    }

    // --- Locks / Torch / Snapshot / Metrics ---
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
                    val src = VideoFrameSource(this, "demo_ring.mp4")
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

            // Show the current frame in the UI
            mainHandler.post {
                lastDemoFrame?.recycle()
                lastDemoFrame = raw
                demoImage.setImageBitmap(raw)
            }

            raw.scale(640, 640, filter = true)
        } else {
            textureView.getBitmap(640, 640)
        }
    } catch (e: Exception) {
        Log.e("TapeWear", "snapshotCurrent failed: ${e.message}")
        null
    }

    private fun meanLuma(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var sum = 0L; var cnt = 0
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (r + g + b) / 3
                cnt++
                x += 2
            }
            y += 2
        }
        return if (cnt == 0) 128.0 else sum.toDouble() / cnt
    }

    private fun blurMetric(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var acc = 0.0; var cnt = 0
        var y = 1
        while (y < h - 1) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 1
            while (x < w - 1) {
                val l = row[x - 1] and 0xFF
                val r = row[x + 1] and 0xFF
                val dx = r - l
                acc += (dx * dx).toDouble()
                cnt++
                x += 2
            }
            y += 2
        }
        return if (cnt == 0) 0.0 else acc / cnt
    }

    private fun meanAbsDiff(a: Bitmap, b: Bitmap): Double {
        val w = min(a.width, b.width)
        val h = min(a.height, b.height)
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        var sum = 0L; var cnt = 0
        var y = 0
        while (y < h) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val pa = rowA[x] and 0xFF
                val pb = rowB[x] and 0xFF
                sum += abs(pa - pb)
                cnt++
                x += 3
            }
            y += 3
        }
        return if (cnt == 0) 0.0 else sum.toDouble() / cnt
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

        // Keep YOLO if it exists; only fall back if there's no detector
        if (ModelManager.detector == null) {
            ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
        }
    }
}