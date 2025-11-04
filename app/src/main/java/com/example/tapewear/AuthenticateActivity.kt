package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.*
import android.os.*
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

class AuthenticateActivity : AppCompatActivity() {

    // Demo toggle preserved
    private var DEMO_MODE = false
    private var hasFlash = false

    // Views (IDs must match activity_authenticate.xml)
    private lateinit var textureView: TextureView            // @id/textureViewAuth
    private lateinit var overlayView: OverlayView            // @id/overlayViewAuth
    private lateinit var demoImage: ImageView                // @id/demoImageAuth

    private lateinit var btnCapture: Button                  // @id/btnCaptureAuth
    private lateinit var progressBar: ProgressBar            // @id/progressBarAuth
    private lateinit var progressLine: TextView              // @id/progressLineAuth

    private lateinit var spinnerPattern: Spinner             // @id/patternSpinner
    private lateinit var flashCheck: CheckBox                // @id/flashCheck (Night mode)

    private lateinit var verdictText: TextView               // @id/verdictText
    private lateinit var confidenceText: TextView            // @id/confidenceText
    private lateinit var resultCard: LinearLayout            // @id/resultCard

    // Camera2
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var previewSize = Size(640, 480)
    private var fpsRanges: Array<Range<Int>>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reqBuilder: CaptureRequest.Builder? = null

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

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

        spinnerPattern.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Pattern A", "Pattern B", "Pattern C")
        )

        // Set the default detector to overlay-based ROI now; swap to YOLO later
        ModelManager.detector = ModelManager.OverlayDetector(overlayView, textureView)

        btnCapture.setOnClickListener { startAuthBurst() }

        // Pick camera or fall back to demo
        val ids: Array<String> = try { cameraManager.cameraIdList } catch (_: Exception) { emptyArray() }
        if (ids.isEmpty()) { switchToDemo("No cameras reported by system"); return }

        val back = ids.firstOrNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) { false }
        }
        cameraId = back ?: ids.firstOrNull()
        if (cameraId == null) { switchToDemo("Could not choose a camera"); return }

        try {
            val ch = cameraManager.getCameraCharacteristics(cameraId!!)
            hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (e: Exception) {
            switchToDemo("Could not get camera characteristics: ${e.message}")
            return
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (!DEMO_MODE) startWhenReady()
            }
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

    // ---- Camera bring-up ----
    private fun startWhenReady() {
        if (DEMO_MODE) return
        if (!hasCamPerm()) {
            perms.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (!textureView.isAvailable) return
        if (cameraId == null) { switchToDemo("No camera id available"); return }
        openCamera()
    }

    private fun hasCamPerm() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        val id = cameraId ?: run { switchToDemo("Camera id missing"); return }
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { cameraDevice = device; startPreview() }
                override fun onDisconnected(device: CameraDevice) { device.close(); cameraDevice = null }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    switchToDemo("Camera error: $error")
                }
            }, mainHandler)
        } catch (_: SecurityException) {
            Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show()
            switchToDemo("Permission denied")
        } catch (e: Exception) {
            switchToDemo("Open camera failed: ${e.message}")
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

        // Prefer ~30fps if present
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
                    s.setRepeatingRequest(reqBuilder!!.build(), null, mainHandler)
                    overlayView.statusText = "Align your pattern and tap Authenticate"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Toast.makeText(this@AuthenticateActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                    switchToDemo("Preview config failed")
                }
            },
            mainHandler
        )
    }

    // ---- Auth burst (3–5s) with lighting policy ----
    private fun startAuthBurst() {
        btnCapture.isEnabled = false
        resultCard.visibility = View.GONE
        overlayView.statusText = "HOLD STEADY"
        showProgress("Preparing…", indeterminate = false)
        progressBar.progress = 0

        val night = flashCheck.isChecked
        if (!DEMO_MODE && night && hasFlash) setTorch(true)

        // Warm-up
        mainHandler.postDelayed({
            lockAeAwb(true)
            runAuthBurst(night)
        }, 600)
    }

    private fun runAuthBurst(night: Boolean) {
        val totalMs = 4_000L   // ~4s auth window
        val stepMs  = 220L     // ~4.5 Hz
        val started = SystemClock.elapsedRealtime()
        val frames  = ArrayList<Bitmap>()
        var prevSmall: Bitmap? = null

        val run = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started
                val pct = (elapsed.toFloat() / totalMs * 100).coerceIn(0f, 100f)
                progressBar.progress = pct.toInt()
                progressLine.text = "Authenticating… ${pct.toInt()}%"

                val sample = snapshotCurrent()
                if (sample != null) {
                    val luma = meanLuma(sample)
                    val blur = blurMetric(sample)
                    val motion = prevSmall?.let { meanAbsDiff(it, sample) } ?: 0.0
                    prevSmall?.recycle()
                    prevSmall = sample.copy(sample.config ?: Bitmap.Config.ARGB_8888, false)

                    val (lo, hi) = if (night) 80.0 to 220.0 else 60.0 to 200.0
                    val hint = when {
                        luma < lo   -> "Too dark"
                        luma > hi   -> "Too bright"
                        motion > 12 -> "Hold steady…"
                        else        -> "Good Lighting"
                    }
                    overlayView.statusText = "HOLD STEADY • $hint"

                    // Keep only decent frames
                    if (luma in lo..hi && blur >= 20.0 && motion <= 12) {
                        frames.add(sample)
                    } else {
                        sample.recycle()
                    }
                }

                if (elapsed < totalMs) {
                    mainHandler.postDelayed(this, stepMs)
                } else {
                    prevSmall?.recycle()
                    finishAuth(frames, night)
                }
            }
        }
        mainHandler.post(run)
    }

    private fun finishAuth(frames: MutableList<Bitmap>, night: Boolean) {
        // Unlocks & torch
        lockAeAwb(false)
        if (!DEMO_MODE && night) setTorch(false)

        // Pick up to 5 sharpest frames (reuse your blurMetric)
        val top = frames
            .map { it to blurMetric(it) }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        // Try real model first
        val verdict = try {
            ModelManager.scoreFromBitmaps(
                frames = if (top.isNotEmpty()) top else frames,
                overlay = overlayView,
                texture = textureView,
                take = (if (top.isNotEmpty()) top.size else frames.size).coerceAtLeast(1),
                expandBox = 1.10f
            )
        } catch (_: Throwable) {
            ModelManager.Verdict(-1f, false)
        }

        // Fallback to mock if no enrollment / invalid score
        val finalSimilarity: Float
        val usedN: Int
        val isMatch: Boolean

        if (verdict.similarity >= 0f) {
            finalSimilarity = verdict.similarity.coerceIn(0f, 1f)
            isMatch = verdict.isMatch
            usedN = if (top.isNotEmpty()) top.size else frames.size.coerceAtMost(5)
        } else {
            val scored = frames.map { it to blurMetric(it) }.sortedByDescending { it.second }.take(5)
            finalSimilarity = (if (scored.isEmpty()) 0.5f
            else scored.map { mockConfidence(it.first) }.average().toFloat()).coerceIn(0f, 1f)
            isMatch = finalSimilarity >= 0.65f
            usedN = scored.size
        }

        // Cleanup bitmaps
        frames.forEach { it.recycle() }

        hideProgress()
        confidenceText.text = "Similarity: ${(finalSimilarity * 100).toInt()}% (n=$usedN)"
        verdictText.text = if (isMatch) "Verdict: MATCH ✅" else "Verdict: NO MATCH ❌"
        resultCard.visibility = View.VISIBLE
        overlayView.statusText = "Done"
        btnCapture.isEnabled = true
    }


    // ---- Locks / Torch / Snapshot / Metrics ----
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

    private fun snapshotCurrent(): Bitmap? {
        return try {
            if (DEMO_MODE) {
                val d = demoImage.drawable as? BitmapDrawable ?: return null
                Bitmap.createScaledBitmap(d.bitmap, 320, max(1, 320 * d.bitmap.height / d.bitmap.width), true)
            } else {
                val vw = textureView.width
                val vh = textureView.height
                if (vw == 0 || vh == 0) return null
                val longEdge = max(vw, vh)
                val scaleDown = (longEdge / 640f).coerceAtLeast(1f)
                val w = (vw / scaleDown).toInt().coerceAtLeast(64)
                val h = (vh / scaleDown).toInt().coerceAtLeast(64)
                textureView.getBitmap(w, h)
            }
        } catch (_: Exception) { null }
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
                sum += kotlin.math.abs(pa - pb)
                cnt++
                x += 3
            }
            y += 3
        }
        return if (cnt == 0) 0.0 else sum.toDouble() / cnt
    }

    // Placeholder model score if real model not available
    private fun mockConfidence(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val step = max(1, (w * h) / 4000)
        var sum = 0L
        var sum2 = 0L
        var count = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = (r + g + b) / 3
                sum += l
                sum2 += (l * l)
                count++
                x += step
            }
            y += step
        }
        if (count == 0) return 0.5f
        val mean = sum.toDouble() / count
        val varc = (sum2.toDouble() / count) - mean * mean
        val norm = (varc / 6500.0).coerceIn(0.0, 1.0)
        return (0.4 + 0.55 * norm).toFloat()
    }

    // Progress UI
    private fun showProgress(line: String, indeterminate: Boolean) {
        progressLine.text = line
        progressLine.visibility = View.VISIBLE
        progressBar.isIndeterminate = indeterminate
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressLine.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    // DEMO fallback
    private fun switchToDemo(reason: String) {
        DEMO_MODE = true
        android.util.Log.w("AuthenticateActivity", "DEMO fallback: $reason")
        demoImage.visibility = View.VISIBLE
        textureView.visibility = View.GONE
        overlayView.statusText = "Align your pattern and tap Authenticate"
        try { setTorch(false) } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        // Detector can still use overlay-only in demo if needed
        ModelManager.detector = ModelManager.OverlayDetector(overlayView, null)
    }
}
