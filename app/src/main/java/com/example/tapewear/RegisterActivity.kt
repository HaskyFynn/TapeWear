package com.example.tapewear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegisterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIGHT_MODE = "extra_night_mode"
        private const val GOOD_FRAME_LIMIT = 30
    }

    private var nightMode = false

    // Set this to true to run with asset video instead of camera
    private val demoMode = false

    // Video demo source
    private var videoSource: VideoFrameSource? = null
    private var currentVideoTimeMs: Long = 0L
    private var videoFrameStepMs: Long = 100L

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

    // Registration timing
    private var regSessionStartMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        nightMode = intent?.getBooleanExtra(EXTRA_NIGHT_MODE, false) == true
        flashCheckRegister.isChecked = nightMode

        // Slot spinner (1..10) + remember selection
        val slotLabels = (1..10).map { getString(R.string.pattern_n, it) }
        spnSlot.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, slotLabels)
        spnSlot.setSelection((currentSlot - 1).coerceIn(0, 9))
        spnSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSlot = position + 1
                ModelManager.setActiveSlot(currentSlot)
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
            // YOLO wasn’t ready, use simple overlay-based ROI
            ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
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
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (demoMode) {
            videoSource = VideoFrameSource(this, "demo_ring.mp4")
            currentVideoTimeMs = 0L
            Log.d("TapeWear_Reg", "DEMO: video source created")
        }

        backgroundThread = HandlerThread("ImageProcessor").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (!demoMode && textureView.
        isAvailable) startWhenReady()
    }

    override fun onPause() {
        super.onPause()

        if (demoMode) {
            videoSource?.close()
            videoSource = null
        }

        if (!demoMode) {
            setTorch(false)
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
        }

        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("TapeWear", "Failed to stop background thread", e)
        }
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

    // --- Registration flow ---
    private fun startRegistrationCapture() {
        if (regRunning.getAndSet(true)) return

        regSessionStartMs = SystemClock.elapsedRealtime()
        MetricsLogger.logSystemSnapshot(this, "reg_start_slot_${currentSlot}")

        btnCapture.isEnabled = false
        btnCapture.visibility = View.GONE
        progressSlot.visibility = View.VISIBLE
        btnAuth.visibility = View.GONE
        progressBar.progress = 0
        progressLine.text = getString(R.string.preparing)
        overlayView.statusText = getString(R.string.hold_steady)
        flashCheckRegister.visibility = View.GONE

        if (!demoMode && nightMode && hasFlash) setTorch(true)


        Log.d("TapeWear_Reg", "Starting registration capture (slot=$currentSlot, night=$nightMode)")
        mainHandler.postDelayed({
            lockAeAwb(true)
            runRegistrationBurst()
        }, 800)
    }

    private fun runRegistrationBurst() {
        val totalMs = 1_000L
        val stepMs  = 170L
        val started = SystemClock.elapsedRealtime()
        val kept = ArrayList<Sample>()
        var prevFrame: Bitmap? = null
        currentVideoTimeMs = 0L

        Log.d("TapeWear_Reg", "Starting registration burst (night=$nightMode, demo=$demoMode)")

        val run = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started

                val frame = snapshotCurrent()
                if (frame != null) {
                    val luma = meanLuma(frame)
                    val blur = blurMetric(frame)
                    val motion = prevFrame?.let { meanAbsDiff(it, frame) } ?: 0.0

                    prevFrame?.recycle()
                    prevFrame = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)

                    val assessment = Quality.assess(luma, blur, motion, nightMode)
                    Log.d(
                        "TapeWear_Reg",
                        "Frame sample: luma=%.1f, blur=%.1f, motion=%.1f -> pass=${assessment.pass}"
                            .format(luma, blur, motion)
                    )

                    mainHandler.post { topMessage.text = assessment.hint }

                    if (assessment.pass) {
                        kept.add(Sample(frame, blur, luma, SystemClock.elapsedRealtime()))
                    } else {
                        frame.recycle()
                    }
                }

                mainHandler.post {
                    val pct = (elapsed.toFloat() / totalMs * 100).coerceIn(0f, 100f).toInt()
                    progressBar.progress = pct
                    progressLine.text = getString(R.string.registering_percent, pct)
                }

                // Early stop once we have 30 good frames
                if (kept.size >= GOOD_FRAME_LIMIT) {
                    Log.d(
                        "TapeWear_Reg",
                        "Reached GOOD_FRAME_LIMIT=$GOOD_FRAME_LIMIT, stopping burst early (kept=${kept.size})"
                    )
                    prevFrame?.recycle()
                    mainHandler.post { finishRegistration(kept) }
                    return
                }

                if (elapsed < totalMs) {
                    backgroundHandler?.postDelayed(this, stepMs)
                } else {
                    prevFrame?.recycle()
                    Log.d("TapeWear_Reg", "Registration burst finished, collected ${kept.size} samples.")
                    mainHandler.post { finishRegistration(kept) }
                }
            }
        }
        backgroundHandler?.post(run)
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

        val frames = kept.map { it.bmp }
        var usedForEnroll = 0
        var saved = 0

        try {
            ModelManager.setActiveSlot(currentSlot)

            // Optional debug crops with YOLO boxes
            saveDetectionsDebug(frames)

            val t0 = SystemClock.elapsedRealtime()
            usedForEnroll = ModelManager.enrollFromBitmaps(
                context = this,
                frames = frames,
                maxEmbeds = 32,
                slot = currentSlot
            )
            val t1 = SystemClock.elapsedRealtime()
            Log.d(
                "TapeWear_Reg",
                "Enrollment created from $usedForEnroll frames in ${t1 - t0} ms."
            )
        } catch (e: Exception) {
            overlayView.statusText = getString(R.string.err_enroll, e.message ?: "")
            Log.e("TapeWear_Reg", "Enrollment failed: ${e.message}", e)
        }

        val best = kept.sortedByDescending { it.blur }.take(48)
        val toSave = if (best.size >= 32) best else kept
        toSave.forEachIndexed { idx, s ->
            try {
                val f = File(cropsDir, "reg_${currentSlot}_${idx}_${s.ts}.jpg")
                FileOutputStream(f).use { out ->
                    s.bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                saved++
            } catch (e: Exception) {
                Log.w("TapeWear_Reg", "Failed to save one crop: ${e.message}")
            }
        }
        Log.d("TapeWear_Reg", "Saved $saved out of ${toSave.size} crops for analysis.")
        kept.forEach { it.bmp.recycle() }

        // Metrics: registration latency and frames
        val now = SystemClock.elapsedRealtime()
        val regTotalMs = if (regSessionStartMs > 0L) now - regSessionStartMs else 0L
        MetricsLogger.logRegistrationSession(
            ctx = this,
            slot = currentSlot,
            regTotalMs = regTotalMs,
            keptSamples = kept.size,
            usedForEnroll = usedForEnroll,
            savedCrops = saved
        )
        MetricsLogger.logFramesPerRegistration(
            ctx = this,
            slot = currentSlot,
            keptSamples = kept.size,
            usedForEnroll = usedForEnroll,
            savedCrops = saved
        )
        MetricsLogger.logSystemSnapshot(this, "reg_end_slot_${currentSlot}")

        overlayView.statusText = if (usedForEnroll > 0)
            getString(R.string.saved_frames_and_model, saved, usedForEnroll)
        else
            getString(R.string.saved_frames_no_model, saved)

        topMessage.text = getString(R.string.registration_complete)
        progressBar.progress = 100
        progressBar.visibility = View.GONE
        progressLine.visibility = View.GONE

        // Show buttons: Capture again and Authenticate
        btnAuth.visibility = View.VISIBLE
        btnCapture.visibility = View.VISIBLE
        flashCheckRegister.visibility = View.VISIBLE
        btnCapture.isEnabled = true
        regRunning.set(false)
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

            mainHandler.post {
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

    private data class Sample(val bmp: Bitmap, val blur: Double, val luma: Double, val ts: Long)

    private fun meanLuma(bmp: Bitmap): Double {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w); var sum = 0L; var cnt = 0; var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                sum += (r + g + b) / 3; cnt++; x += 2
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

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}