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
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class RegisterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIGHT_MODE = "extra_night_mode"
        private const val TAG = "TapeWear_Reg"
    }

    private var nightMode = false

    // Toggle this when you move from emulator demo to real device
    private val demoMode = true

    // Demo video source
    private var videoSource: VideoFrameSource? = null
    private var currentVideoTimeMs: Long = 0L
    private var lastDemoFrame: Bitmap? = null
    private var videoFrameStepMs: Long = 150L   // about 9 fps

    // Views
    private lateinit var textureView: TextureView
    private lateinit var overlayView: OverlayView
    private lateinit var demoImage: ImageView
    private lateinit var btnCapture: Button
    private lateinit var btnExport: Button
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

    // Background thread for capture and registration processing
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Bind views
        textureView         = findViewById(R.id.textureView)
        overlayView         = findViewById(R.id.overlayView)
        demoImage           = findViewById(R.id.demoImage)
        btnCapture          = findViewById(R.id.btnCapture)
        btnExport           = findViewById(R.id.btnExport)
        previewThumb        = findViewById(R.id.previewThumb)
        previewLabel        = findViewById(R.id.previewLabel)
        flashHint           = findViewById(R.id.flashHint)
        progressBar         = findViewById(R.id.progressBar)
        progressLine        = findViewById(R.id.


        progressLine)
        topMessage          = findViewById(R.id.topMessage)
        progressSlot        = findViewById(R.id.progressSlot)
        flashCheckRegister  = findViewById(R.id.flashCheckRegister)
        spnSlot             = findViewById(R.id.spnSlot)

        nightMode = intent?.getBooleanExtra(EXTRA_NIGHT_MODE, false) == true
        flashCheckRegister.isChecked = nightMode

        // Slot spinner 1 to 10
        val slotLabels = (1..10).map { getString(R.string.pattern_n, it) }
        spnSlot.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            slotLabels
        )
        spnSlot.setSelection((currentSlot - 1).coerceIn(0, 9))
        spnSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                currentSlot = position + 1
                ModelManager.setActiveSlot(currentSlot)
                Log.d(TAG, "Active registration slot set to $currentSlot")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        progressSlot.visibility = View.GONE
        btnExport.visibility    = View.GONE
        overlayView.statusText  = getString(R.string.align_in_box)
        refreshHeader()

        flashCheckRegister.setOnCheckedChangeListener { _, checked ->
            nightMode = checked
            refreshHeader()
        }

        // Session dirs
        sessionDir = File(cacheDir, "session_${System.currentTimeMillis()}").apply { mkdirs() }
        cropsDir   = File(sessionDir, "crops").apply { mkdirs() }

        btnCapture.setOnClickListener {
            nightMode = flashCheckRegister.isChecked
            startRegistrationCapture()
        }

        btnExport.setOnClickListener {
            exportSession()
        }

        if (demoMode) {
            Log.d(TAG, "RegisterActivity in DEMO mode using asset video")
            textureView.visibility = View.GONE
            demoImage.visibility   = View.VISIBLE
            flashHint.visibility   = View.GONE

            // If YOLO detector is not ready, fall back to overlay based detector
            if (ModelManager.detector == null) {
                Log.w(TAG, "ModelManager.detector was null, using OverlayDetector fallback (demo)")
                ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
            }
        } else {
            demoImage.visibility = View.GONE
        }

        // Camera choice only if not in demo
        if (!demoMode) {
            try {
                cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraManager.cameraIdList.first()

                val chars = cameraManager.getCameraCharacteristics(cameraId)
                hasFlash  = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                Log.d(TAG, "Using cameraId=$cameraId, hasFlash=$hasFlash")

                textureView.surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture, w: Int, h: Int
                        ) {
                            startWhenReady()
                        }
                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture, w: Int, h: Int
                        ) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure camera: ${e.message}", e)
                toast(getString(R.


                string.err_preview))
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (demoMode) {
            try {
                videoSource = VideoFrameSource(this, "7.mp4")
                currentVideoTimeMs = 0L
                Log.d(TAG, "DEMO: video source created")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open demo video: ${e.message}", e)
            }
        }

        backgroundThread = HandlerThread("ImageProcessor").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        if (!demoMode && textureView.isAvailable) {
            startWhenReady()
        }
    }

    override fun onPause() {
        super.onPause()

        if (demoMode) {
            videoSource?.close()
            videoSource = null
            lastDemoFrame?.recycle()
            lastDemoFrame = null
        }

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
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to stop background thread", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
        }
    }

    // Camera bring up

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
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        try {
            Log.d(TAG, "Opening camera: $cameraId")
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        Log.d(TAG, "Camera opened")
                        cameraDevice = device
                        startPreview()
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "Camera disconnected")
                        device.close()
                        cameraDevice = null
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error: $error")
                        device.close()
                        cameraDevice = null
                        toast(getString(R.string.err_preview))
                    }
                },
                backgroundHandler
            )
        } catch (e: SecurityException) {
            toast(getString(R.string.err_perm_missing))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            toast(getString(R.string.err_preview))
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        reqBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        val req = reqBuilder?.build()


                        if (req != null) {
                            s.setRepeatingRequest(req, null, backgroundHandler)
                        }
                        overlayView.statusText = getString(R.string.align_and_tap)
                        Log.d(TAG, "Preview configured")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start preview: ${e.message}", e)
                        toast(getString(R.string.err_preview))
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Preview configuration failed")
                    toast(getString(R.string.err_preview))
                }
            },
            backgroundHandler
        )
    }

    // Header

    private fun refreshHeader() {
        topMessage.text = if (nightMode) {
            getString(R.string.register_night_torch)
        } else {
            getString(R.string.register_day)
        }

        flashHint.visibility =
            if (!demoMode && nightMode) View.VISIBLE else View.GONE
        flashHint.text =
            if (nightMode && !demoMode) getString(R.string.torch_on) else ""

        Log.d(TAG, "Header refreshed: nightMode=$nightMode, demoMode=$demoMode")
    }

    // Registration flow

    private fun startRegistrationCapture() {
        if (regRunning.getAndSet(true)) {
            Log.w(TAG, "Registration already running, ignoring tap")
            return
        }

        Log.d(TAG, "Starting registration capture (slot=$currentSlot, night=$nightMode)")

        btnCapture.isEnabled = false
        btnCapture.visibility = View.GONE
        progressSlot.visibility = View.VISIBLE
        btnExport.visibility = View.GONE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressLine.visibility = View.VISIBLE
        progressLine.text = getString(R.string.preparing)
        overlayView.statusText = getString(R.string.hold_steady)
        flashCheckRegister.visibility = View.GONE

        if (!demoMode && nightMode && hasFlash) {
            setTorch(true)
        }

        mainHandler.postDelayed({
            lockAeAwb(true)
            runRegistrationBurst()
        }, 800)
    }

    private fun runRegistrationBurst() {
        val totalMs = 11_000L
        val stepMs  = 170L
        val started = SystemClock.elapsedRealtime()
        val kept = ArrayList<Sample>()
        var prevFrame: Bitmap? = null
        currentVideoTimeMs = 0L

        Log.d(TAG, "Starting registration burst (night=$nightMode, demo=$demoMode)")

        val run = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started

                val frame = snapshotCurrent()
                if (frame != null) {
                    val luma = meanLuma(frame)
                    val blur = blurMetric(frame)
                    val motion = prevFrame?.let { meanAbsDiff(it, frame) } ?: 0.0

                    prevFrame?.recycle()
                    prevFrame = frame.copy(
                        frame.config ?: Bitmap.Config.ARGB_8888,
                        false
                    )

                    val assessment = Quality.assess(luma, blur, motion, nightMode)
                    Log.d(
                        TAG,
                        "Frame sample: luma=%.1f, blur=%.1f, motion=%.1f -> pass=%s"
                            .format(luma, blur, motion, assessment.pass)
                    )

                    mainHandler.post {
                        topMessage.text = assessment.hint
                    }

                    if (assessment.pass) {
                        kept.add(
                            Sample(
                                bmp = frame,
                                blur = blur,
                                luma = luma,
                                ts = SystemClock.elapsedRealtime()
                            )
                        )
                    } else {


                        frame.recycle()
                    }
                }

                mainHandler.post {
                    val pct =
                        (elapsed.toFloat() / totalMs * 100)
                            .coerceIn(0f, 100f)
                            .toInt()
                    progressBar.progress = pct
                    progressLine.text =
                        getString(R.string.registering_percent, pct)
                }

                if (elapsed < totalMs) {
                    backgroundHandler?.postDelayed(this, stepMs)
                } else {
                    prevFrame?.recycle()
                    Log.d(TAG, "Registration burst finished, collected ${kept.size} samples.")
                    mainHandler.post { finishRegistration(kept) }
                }
            }
        }

        backgroundHandler?.post(run)
    }

    private fun saveDetectionsDebug(frames: List<Bitmap>) {
        val det = ModelManager.detector ?: run {
            Log.w(TAG, "saveDetectionsDebug: detector is null, skipping")
            return
        }

        val outDir = File(
            sessionDir,
            "slot_${"%02d".format(currentSlot)}_bboxes"
        ).apply { mkdirs() }

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

                val f = File(
                    outDir,
                    "slot${"%02d".format(currentSlot)}_frame${"%03d".format(idx)}.jpg"
                )
                FileOutputStream(f).use { out ->
                    boxed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                boxed.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "saveDetectionsDebug failed for frame $idx: ${e.message}")
            }
        }
    }

    private fun finishRegistration(kept: List<Sample>) {
        Log.d(TAG, "finishRegistration: processing ${kept.size} samples for slot $currentSlot")

        lockAeAwb(false)
        if (!demoMode && nightMode) setTorch(false)

        var usedForEnroll = 0
        val frames = kept.map { it.bmp }

        try {
            ModelManager.setActiveSlot(currentSlot)
            val t0 = SystemClock.elapsedRealtime()
            saveDetectionsDebug(frames)

            usedForEnroll = ModelManager.enrollFromBitmaps(
                context   = this,
                frames    = frames,
                maxEmbeds = 32,
                slot      = currentSlot
            )
            val t1 = SystemClock.elapsedRealtime()
            Log.d(
                TAG,
                "Enrollment created from $usedForEnroll frames in ${t1 - t0} ms."
            )
        } catch (e: Exception) {
            overlayView.statusText = getString(
                R.string.err_enroll,
                e.message ?: ""
            )
            Log.e(TAG, "Enrollment failed: ${e.message}", e)
        }

        val best = kept.sortedByDescending { it.blur }.take(48)
        val toSave = if (best.size >= 32) best else kept
        var saved = 0

        toSave.forEachIndexed { idx, s ->
            try {
                val f = File(
                    cropsDir,
                    "reg_${currentSlot}_${idx}_${s.ts}.jpg"
                )
                FileOutputStream(f).use { out ->
                    s.bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                saved++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save one crop: ${e.message}")
            }
        }
        Log.d(TAG, "Saved $saved out of ${toSave.size} crops for analysis.")

        kept.forEach { it.


            bmp.recycle() }

        overlayView.statusText = if (usedForEnroll > 0) {
            getString(R.string.saved_frames_and_model, saved, usedForEnroll)
        } else {
            getString(R.string.saved_frames_no_model, saved)
        }

        topMessage.text = getString(R.string.registration_complete)
        progressBar.progress = 100
        progressBar.visibility = View.GONE
        progressLine.visibility = View.GONE
        btnExport.visibility = View.VISIBLE
        btnCapture.visibility = View.VISIBLE
        flashCheckRegister.visibility = View.VISIBLE
        btnCapture.isEnabled = true
        regRunning.set(false)
    }

    // Snapshot and metrics helpers

    private fun setTorch(on: Boolean) {
        if (!hasFlash) return
        try {
            cameraManager.setTorchMode(cameraId, on)
            Log.d(TAG, "Torch set to $on")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set torch=$on: ${e.message}")
        }
    }

    private fun lockAeAwb(lock: Boolean) {
        val s = session ?: return
        val b = reqBuilder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, lock)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
            s.setRepeatingRequest(b.build(), null, mainHandler)
            Log.d(TAG, "AE/AWB lock=$lock")
        } catch (e: Exception) {
            Log.w(TAG, "lockAeAwb($lock) failed: ${e.message}")
        }
    }

    private fun snapshotCurrent(): Bitmap? = try {
        if (demoMode) {
            val vs = videoSource ?: return null

            val raw = vs.frameAt(currentVideoTimeMs) ?: return null
            currentVideoTimeMs += videoFrameStepMs

            // Update on UI
            mainHandler.post {
                lastDemoFrame?.recycle()
                lastDemoFrame = raw.copy(
                    raw.config ?: Bitmap.Config.ARGB_8888,
                    false
                )
                demoImage.setImageBitmap(lastDemoFrame)
            }

            raw.scale(640, 640, filter = true)
        } else {
            textureView.getBitmap(640, 640)
        }
    } catch (e: Exception) {
        Log.e(TAG, "snapshotCurrent failed: ${e.message}")
        null
    }

    private data class Sample(
        val bmp: Bitmap,
        val blur: Double,
        val luma: Double,
        val ts: Long
    )

    private fun meanLuma(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var sum = 0L
        var cnt = 0
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
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var acc = 0.0
        var cnt = 0
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
        var sum = 0L
        var cnt = 0
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

    // Export session

    private fun exportSession() {
        try {
            val files = cropsDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".jpg", true) ||
                        f.name.endsWith(".jpeg", true) ||
                        f.name.endsWith(".png", true))
            }?.toList().orEmpty()

            if (files.isEmpty()) {
                toast(getString(R.string.no_crops_to_export))
                Log.d(TAG, "exportSession: no image crops in $cropsDir")
                return
            }

            val uris = files.map { file ->
                FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            }

            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(uris)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(
                Intent.createChooser(
                    send,
                    getString(R.string.export_session_title)
                )
            )
            Log.d(TAG, "exportSession: exported ${files.size} files")
        } catch (e: Exception) {
            Log.e(TAG, "exportSession failed: ${e.message}", e)
            toast(getString(R.string.export_failed))
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}