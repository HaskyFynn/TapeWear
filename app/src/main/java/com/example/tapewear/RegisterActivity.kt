package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegisterActivity : AppCompatActivity() {

    companion object {
        /** Torch-on policy for “Night registration.” */
        const val EXTRA_NIGHT_MODE = "extra_night_mode"
    }

    // ---- Runtime flags ----
    private var nightMode: Boolean = false
    private val DEMO_MODE = false

    // ---- Views (must exist in activity_register.xml) ----
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
    private lateinit var actionSlot: FrameLayout
    private lateinit var progressSlot: LinearLayout
    private lateinit var flashCheckRegister: CheckBox

    // ---- Session dir ----
    private lateinit var sessionDir: File
    private lateinit var imagesDir: File
    private lateinit var labelsDir: File
    private lateinit var cropsDir: File

    // ---- Camera2 ----
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String = "0"
    private var previewSize = Size(640, 480)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var hasFlash = false
    private var reqBuilder: CaptureRequest.Builder? = null

    // zoom / focus helpers
    private var sensorRect: Rect? = null
    private var maxDigitalZoom: Float = 1f
    private var currentZoom: Float = 1f

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // ----- Bind views FIRST -----
        textureView   = findViewById(R.id.textureView)
        overlayView   = findViewById(R.id.overlayView)
        demoImage     = findViewById(R.id.demoImage)

        btnCapture    = findViewById(R.id.btnCapture)
        btnExport     = findViewById(R.id.btnExport)
        previewThumb  = findViewById(R.id.previewThumb)
        previewLabel  = findViewById(R.id.previewLabel)

        flashHint     = findViewById(R.id.flashHint)
        progressBar   = findViewById(R.id.progressBar)
        progressLine  = findViewById(R.id.progressLine)

        topMessage    = findViewById(R.id.topMessage)
        actionSlot    = findViewById(R.id.actionSlot)
        progressSlot  = findViewById(R.id.progressSlot)
        flashCheckRegister = findViewById(R.id.flashCheckRegister)

        // ----- Then read intent flags & reflect into checkboxes -----
        nightMode = intent?.getBooleanExtra(EXTRA_NIGHT_MODE, false) == true
        flashCheckRegister.isChecked = nightMode

        // Initial UI
        btnCapture.visibility   = View.VISIBLE
        progressSlot.visibility = View.GONE
        btnExport.visibility    = View.GONE
        overlayView.statusText  = "Align the tag in the box"
        refreshHeader()

        flashCheckRegister.setOnCheckedChangeListener { _, isChecked ->
            nightMode = isChecked
            refreshHeader()
        }

        // Session + subfolders
        sessionDir = File(cacheDir, "session_${System.currentTimeMillis()}").apply { mkdirs() }
        imagesDir  = File(sessionDir, "images").apply { mkdirs() }
        labelsDir  = File(sessionDir, "labels").apply { mkdirs() }
        cropsDir   = File(sessionDir, "crops").apply { mkdirs() }

        // Capture
        btnCapture.setOnClickListener {
            nightMode = flashCheckRegister.isChecked
            startRegistrationCapture()
        }

        // Export
        btnExport.setOnClickListener { exportSession() }

        // DEMO short-circuit
        if (DEMO_MODE) {
            demoImage.visibility = View.VISIBLE
            textureView.visibility = View.GONE
            flashHint.visibility = if (nightMode) View.VISIBLE else View.GONE
            flashHint.text = if (nightMode) "Torch simulated (demo)" else ""
            ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
            return
        }

        // Choose camera (prefer back)
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        if (maxDigitalZoom < 1f) maxDigitalZoom = 1f

        // Default detector == overlay box mapped into bitmap coords (YOLO drop-in later)
        ModelManager.detector = ModelManager.OverlayDetector(overlayView, textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startWhenReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (!DEMO_MODE && textureView.isAvailable) startWhenReady()
    }

    override fun onPause() {
        super.onPause()
        if (!DEMO_MODE) {
            setTorch(false)
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
        }
    }

    // ------------------------------------------------------
    // Camera bring-up
    // ------------------------------------------------------
    private fun startWhenReady() {
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
            }, mainHandler)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show()
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
                    val req = reqBuilder?.build() ?: return
                    s.setRepeatingRequest(req, null, mainHandler)
                    overlayView.statusText = "Align your pattern in the box and tap Capture"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Toast.makeText(this@RegisterActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            },
            mainHandler
        )
    }

    // ------------------------------------------------------
    // Header / chips
    // ------------------------------------------------------
    private fun refreshHeader() {
        topMessage.text = if (nightMode) "Night registration • Torch ON" else "Day registration"
        flashHint.visibility = if (nightMode) View.VISIBLE else View.GONE
        flashHint.text = if (nightMode) "Torch ON" else ""
    }

    // ------------------------------------------------------
    // Registration flow
    // ------------------------------------------------------
    private val regRunning = AtomicBoolean(false)

    private fun startRegistrationCapture() {
        if (regRunning.getAndSet(true)) return

        // UI lock
        btnCapture.isEnabled = false
        btnCapture.visibility = View.GONE
        progressSlot.visibility = View.VISIBLE
        btnExport.visibility = View.GONE
        progressBar.progress = 0
        progressLine.text = "Preparing…"
        overlayView.statusText = "HOLD STEADY"
        flashCheckRegister.visibility = View.GONE

        // Torch policy
        if (!DEMO_MODE && nightMode && hasFlash) setTorch(true)

        // Warm-up (AE/AF/AWB settle) + zoom/focus
        mainHandler.postDelayed({
            lockAeAwb(true)
            applyZoomAndFocusToOverlay()
            runRegistrationBurst()
        }, 800)
    }

    /** Registration burst with quality gating & saving best frames. */
    private fun runRegistrationBurst() {
        val totalMs = 12_000L
        val stepMs  = 170L
        val started = SystemClock.elapsedRealtime()

        val kept = ArrayList<Sample>()
        var prevSmall: Bitmap? = null

        val run = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started
                val pct = (elapsed.toFloat() / totalMs * 100).coerceIn(0f, 100f)
                progressBar.progress = pct.toInt()
                progressLine.text = "Registering… ${pct.toInt()}%"

                val frame = snapshotCurrent()
                if (frame != null) {
                    val luma = meanLuma(frame)
                    val blur = blurMetric(frame)
                    val motion = prevSmall?.let { meanAbsDiff(it, frame) } ?: 0.0
                    prevSmall?.recycle()
                    prevSmall = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)

                    val (lo, hi) = if (nightMode) 80.0 to 220.0 else 60.0 to 200.0
                    topMessage.text = when {
                        luma < lo   -> "Too dark"
                        luma > hi   -> "Too bright"
                        motion > 12 -> "Hold steady…"
                        else        -> "Good Lighting"
                    }

                    val pass = (luma in lo..hi) && blur >= 20.0 && motion <= 12
                    if (pass && prevSmall != null) {
                        kept.add(Sample(prevSmall!!, blur, luma, SystemClock.elapsedRealtime()))
                    }
                }

                if (elapsed < totalMs) {
                    mainHandler.postDelayed(this, stepMs)
                } else {
                    prevSmall?.recycle()
                    finishRegistration(kept)
                }
            }
        }
        mainHandler.post(run)
    }

    private fun finishRegistration(kept: List<Sample>) {
        clearZoomAndFocus()
        lockAeAwb(false)
        if (!DEMO_MODE && nightMode) setTorch(false)

        // 1) Enroll BEFORE recycling
        var usedForEnroll = 0
        try {
            val keptBmps = kept.map { it.bmp }
            usedForEnroll = ModelManager.enrollFromBitmaps(
                frames = keptBmps,
                overlay = overlayView,
                texture = textureView,
                maxEmbeds = 32,
                expandBox = 1.10f,
                preferOverlayIoU = true
            )
        } catch (e: Exception) {
            overlayView.statusText = "Enroll error: ${e.message}"
        }

        // 2) Optional: persist best crops for your dataset
        val best = kept.sortedByDescending { it.blur }.take(48)
        val toSave = if (best.size >= 32) best else kept.take(kept.size)
        var saved = 0
        toSave.forEachIndexed { idx, s ->
            try {
                val file = File(cropsDir, "reg_${idx}_${s.ts}.jpg")
                FileOutputStream(file).use { out ->
                    s.bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                saved++
            } catch (_: Exception) { /* ignore */ }
        }

        // 3) Recycle AFTER enrollment & saving
        kept.forEach { it.bmp.recycle() }

        // 4) UI
        val enrolledMsg = if (usedForEnroll > 0) " • model saved ($usedForEnroll samples)" else " • model not saved"
        overlayView.statusText = "saved $saved frames$enrolledMsg"
        topMessage.text = "Registration complete"
        progressBar.progress = 100
        progressBar.visibility = View.GONE
        progressLine.visibility = View.GONE
        btnExport.visibility = View.VISIBLE
        btnCapture.visibility = View.VISIBLE
        flashCheckRegister.visibility = View.VISIBLE
        btnCapture.isEnabled = true
        regRunning.set(false)
    }

    // ------------------------------------------------------
    // Zoom + focus helpers
    // ------------------------------------------------------
    private fun overlayToSensorRect(vw: Int, vh: Int): Rect? {
        val sRect = sensorRect ?: return null
        val box = overlayView.getFramingBox()
        if (vw <= 0 || vh <= 0 || box.width() <= 0 || box.height() <= 0) return null

        val scaleX = sRect.width().toFloat() / vw.toFloat()
        val scaleY = sRect.height().toFloat() / vh.toFloat()

        val left = (box.left * scaleX).toInt().coerceIn(sRect.left, sRect.right - 1)
        val top  = (box.top  * scaleY).toInt().coerceIn(sRect.top,  sRect.bottom - 1)
        val right  = (box.right * scaleX).toInt().coerceIn(left + 1, sRect.right)
        val bottom = (box.bottom * scaleY).toInt().coerceIn(top + 1,  sRect.bottom)
        return Rect(left, top, right, bottom)
    }

    /** Compute a crop region so the overlay fills most of the frame. */
    private fun zoomCropToFitOverlay(margin: Float = 1.15f): Rect? {
        val sRect = sensorRect ?: return null
        val vw = textureView.width
        val vh = textureView.height
        val box = overlayView.getFramingBox()
        if (vw <= 0 || vh <= 0 || box.width() <= 0 || box.height() <= 0) return null

        val ovInSensor = overlayToSensorRect(vw, vh) ?: return null

        val zoomX: Float = vw.toFloat() / box.width().toFloat()
        val zoomY: Float = vh.toFloat() / box.height().toFloat()
        var desired: Float = (min(zoomX, zoomY) / margin.toFloat())
        desired = desired.coerceIn(1f, maxDigitalZoom)

        val cx: Int = ovInSensor.centerX()
        val cy: Int = ovInSensor.centerY()
        val cropW: Int = (sRect.width().toFloat() / desired).toInt().coerceAtLeast(16)
        val cropH: Int = (sRect.height().toFloat() / desired).toInt().coerceAtLeast(16)

        val left: Int = (cx - cropW / 2).coerceIn(sRect.left, sRect.right - cropW)
        val top: Int  = (cy - cropH / 2).coerceIn(sRect.top,  sRect.bottom - cropH)

        currentZoom = desired
        return Rect(left, top, left + cropW, top + cropH)
    }

    /** Apply SCALER_CROP_REGION + AF/AE metering rectangles centered on the overlay. */
    private fun applyZoomAndFocusToOverlay() {
        val s = session ?: return
        val b = reqBuilder ?: return
        val crop = zoomCropToFitOverlay() ?: return
        val ovInSensor = overlayToSensorRect(textureView.width, textureView.height) ?: return

        val weight = MeteringRectangle.METERING_WEIGHT_MAX - 1
        val metRect = MeteringRectangle(ovInSensor, weight)
        try {
            b.set(CaptureRequest.SCALER_CROP_REGION, crop)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf<MeteringRectangle>(metRect))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf<MeteringRectangle>(metRect))
            s.setRepeatingRequest(b.build(), null, mainHandler)
            overlayView.statusText = "Auto-zoomed for macro focus"
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun clearZoomAndFocus() {
        val s = session ?: return
        val b = reqBuilder ?: return
        try {
            b.set(CaptureRequest.SCALER_CROP_REGION, sensorRect) // full sensor again
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf())
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf())
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            s.setRepeatingRequest(b.build(), null, mainHandler)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------
    // Helpers: camera/torch/locks/snapshot/metrics/labels
    // ------------------------------------------------------
    private fun setTorch(on: Boolean) {
        if (!hasFlash) return
        try { cameraManager.setTorchMode(cameraId, on) } catch (_: Exception) {}
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

    /** Downscaled snapshot for fast metrics/gating. */
    private fun snapshotCurrent(): Bitmap? {
        return try {
            if (DEMO_MODE) {
                val d = demoImage.drawable as? BitmapDrawable ?: return null
                val bw = d.bitmap.width
                val bh = d.bitmap.height
                val w = 320
                val h = max(1, w * bh / bw)
                Bitmap.createScaledBitmap(d.bitmap, w, h, true)
            } else {
                val vw = textureView.width
                val vh = textureView.height
                if (vw == 0 || vh == 0) return null
                val longEdge = max(vw, vh)
                val scaleDown = (longEdge / 720f).coerceAtLeast(1f)
                val w = (vw / scaleDown).toInt().coerceAtLeast(64)
                val h = (vh / scaleDown).toInt().coerceAtLeast(64)
                textureView.getBitmap(w, h)
            }
        } catch (_: Exception) { null }
    }

    /** Full-view snapshot for saving training images (unused now but kept). */
    private fun snapshotFull(): Bitmap? {
        return try {
            if (DEMO_MODE) {
                val d = demoImage.drawable as? BitmapDrawable ?: return null
                d.bitmap.copy(d.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                val vw = textureView.width
                val vh = textureView.height
                if (vw == 0 || vh == 0) null else textureView.getBitmap(vw, vh)
            }
        } catch (_: Exception) { null }
    }

    private data class Sample(val bmp: Bitmap, val blur: Double, val luma: Double, val ts: Long)

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

    // Cheap sharpness estimate
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

    /** YOLO label helper (kept for future use). */
    private fun writeYoloLabelForOverlay(imgW: Int, imgH: Int, baseName: String) {
        val vr = overlayView.getFramingBox()
        val viewW = textureView.width.coerceAtLeast(1)
        val viewH = textureView.height.coerceAtLeast(1)

        val left   = (vr.left   * imgW / viewW).toInt().coerceIn(0, imgW - 1)
        val top    = (vr.top    * imgH / viewH).toInt().coerceIn(0, imgH - 1)
        val right  = (vr.right  * imgW / viewW).toInt().coerceIn(left + 1, imgW)
        val bottom = (vr.bottom * imgH / viewH).toInt().coerceIn(top + 1, imgH)

        val bx = (left + right) / 2.0
        val by = (top + bottom) / 2.0
        val bw = (right - left).toDouble()
        val bh = (bottom - top).toDouble()

        val cxN = (bx / imgW).coerceIn(0.0, 1.0)
        val cyN = (by / imgH).coerceIn(0.0, 1.0)
        val wN  = (bw / imgW).coerceIn(0.0, 1.0)
        val hN  = (bh / imgH).coerceIn(0.0, 1.0)

        val labelFile = File(labelsDir, "$baseName.txt")
        labelFile.writeText("0 $cxN $cyN $wN $hN\n")
    }

    // ------------------------------------------------------
    // Export helpers
    // ------------------------------------------------------
    private fun exportSession() {
        val hasAny =
            (imagesDir.listFiles()?.isNotEmpty() == true) ||
            (cropsDir.listFiles()?.isNotEmpty() == true)

        if (!hasAny) {
            Toast.makeText(this, "No captures yet", Toast.LENGTH_SHORT).show()
            return
        }
        overlayView.statusText = "Packaging session…"
        btnExport.isEnabled = false
        btnExport.post {
            try {
                val zip = zipSession(sessionDir)
                overlayView.statusText = "Session packaged"
                shareZip(zip)
            } catch (e: Exception) {
                overlayView.statusText = "Export failed"
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                btnExport.isEnabled = true
            }
        }
    }

    private fun zipSession(dir: File): File {
        val zipFile = File(cacheDir, "${dir.name}.zip")
        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            fun addFile(f: File, pathPrefix: String = "") {
                if (!f.isFile) return
                val entry = java.util.zip.ZipEntry("$pathPrefix${f.name}")
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            fun addDir(d: File, prefix: String) {
                d.listFiles()?.forEach { f ->
                    if (f.isDirectory) addDir(f, "$prefix${f.name}/")
                    else addFile(f, prefix)
                }
            }
            addDir(dir, "")
        }
        return zipFile
    }

    private fun shareZip(zip: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", zip
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("session_zip", uri)
        }
        val resInfoList = packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
        for (ri in resInfoList) {
            grantUriPermission(ri.activityInfo.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Share session zip").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(chooser)
    }
}
