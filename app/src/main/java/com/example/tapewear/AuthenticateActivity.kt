package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.*
import android.os.*
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class AuthenticateActivity : AppCompatActivity() {

    // ------- Mode flags -------
    private var DEMO_MODE = false
    private var hasFlash = false

    // ------- Views (IDs from activity_authenticate.xml) -------
    private lateinit var textureView: TextureView            // @id/textureViewAuth
    private lateinit var overlayView: OverlayView            // @id/overlayViewAuth
    private lateinit var demoImage: ImageView                // @id/demoImageAuth

    private lateinit var btnCapture: Button                  // @id/btnCaptureAuth
    private lateinit var progressBar: ProgressBar            // @id/progressBarAuth
    private lateinit var progressLine: TextView              // @id/progressLineAuth

    private lateinit var spinnerPattern: Spinner             // @id/patternSpinner
    private lateinit var flashCheck: CheckBox                // @id/flashCheck

    private lateinit var verdictText: TextView               // @id/verdictText
    private lateinit var confidenceText: TextView            // @id/confidenceText
    private lateinit var resultCard: LinearLayout            // @id/resultCard

    // ------- Camera2 -------
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var previewSize = Size(640, 480)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_authenticate)

        // ---- Bind views ----
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

        // Patterns (placeholder list)
        spinnerPattern.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Pattern A", "Pattern B", "Pattern C")
        )

        btnCapture.setOnClickListener {
            btnCapture.isEnabled = false
            resultCard.visibility = android.view.View.GONE
            showProgress("Capturing…", indeterminate = true)

            val willUseTorch = flashCheck.isChecked && hasFlash && !DEMO_MODE
            if (willUseTorch) {
                setTorch(true)
                progressLine.postDelayed({
                    hideProgress()
                    doCaptureAndScore()
                    setTorch(false)
                    btnCapture.postDelayed({ btnCapture.isEnabled = true }, 300)
                }, 180)
            } else {
                hideProgress()
                doCaptureAndScore()
                btnCapture.postDelayed({ btnCapture.isEnabled = true }, 300)
            }
        }

        // ---- Pick camera or fall back to demo safely ----
        val ids: Array<String> = try { cameraManager.cameraIdList } catch (_: Exception) { emptyArray() }

        if (ids.isEmpty()) {
            switchToDemo("No cameras reported by system")
            return
        }

        // Prefer back camera if present
        val back = ids.firstOrNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) { false }
        }

        cameraId = back ?: ids.firstOrNull()
        if (cameraId == null) {
            switchToDemo("Could not choose a camera")
            return
        }

        hasFlash = (try {
            cameraManager.getCameraCharacteristics(cameraId!!)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (_: Exception) { false })

        // Attach preview listener only if we’re not in demo
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                if (!DEMO_MODE) startWhenReady()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
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

    // ---------------- Camera path ----------------
    private fun startWhenReady() {
        if (DEMO_MODE) return
        if (!hasCamPerm()) {
            perms.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (!textureView.isAvailable) return
        if (cameraId == null) {
            switchToDemo("No camera id available at start")
            return
        }
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

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 15))
        }.build()

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(req, null, mainHandler)
                    overlayView.statusText = "Align your pattern and tap Capture"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Toast.makeText(this@AuthenticateActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                    switchToDemo("Preview config failed")
                }
            },
            mainHandler
        )
    }

    // ---------------- Capture & Score ----------------
    private fun doCaptureAndScore() {
        val frameRect = overlayView.getFramingBox()

        val roi: Bitmap = try {
            if (DEMO_MODE) {
                val src = (demoImage.drawable as BitmapDrawable).bitmap
                cropFromImageViewCenterCrop(demoImage, src, frameRect)
            } else {
                val vw = textureView.width
                val vh = textureView.height
                if (vw == 0 || vh == 0) throw IllegalStateException("No camera frame")
                val longEdge = maxOf(vw, vh)
                val scaleDown = (longEdge / 960f).coerceAtLeast(1f)
                val bmpW = (vw / scaleDown).toInt().coerceAtLeast(1)
                val bmpH = (vh / scaleDown).toInt().coerceAtLeast(1)
                val full = textureView.getBitmap(bmpW, bmpH) ?: throw IllegalStateException("No camera frame")
                cropFromTextureView(full, vw, vh, frameRect)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        // Placeholder scoring
        showProgress("Scoring…", indeterminate = true)
        val conf = mockConfidence(roi)
        progressLine.postDelayed({
            hideProgress()
            confidenceText.text = "Confidence: ${(conf * 100).toInt()}%"
            verdictText.text = if (conf >= 0.65f) "Verdict: MATCH ✅" else "Verdict: NO MATCH ❌"
            resultCard.visibility = android.view.View.VISIBLE
        }, 400)
    }

    private fun mockConfidence(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val step = maxOf(1, (w * h) / 4000)
        var sum = 0L
        var sum2 = 0L
        var count = 0
        val row = IntArray(w)
        for (y in 0 until h step step) {
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
        }
        if (count == 0) return 0.5f
        val mean = sum.toDouble() / count
        val varc = (sum2.toDouble() / count) - mean * mean
        val norm = (varc / 6500.0).coerceIn(0.0, 1.0)
        return (0.4 + 0.55 * norm).toFloat()
    }

    private fun cropFromTextureView(full: Bitmap, viewW: Int, viewH: Int, viewRect: RectF): Bitmap {
        val scaleX = full.width.toFloat() / viewW
        val scaleY = full.height.toFloat() / viewH
        val left = (viewRect.left * scaleX).toInt().coerceIn(0, full.width - 1)
        val top = (viewRect.top * scaleY).toInt().coerceIn(0, full.height - 1)
        val right = (viewRect.right * scaleX).toInt().coerceIn(left + 1, full.width)
        val bottom = (viewRect.bottom * scaleY).toInt().coerceIn(top + 1, full.height)
        val cw = (right - left).coerceAtLeast(1)
        val ch = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(full, left, top, cw, ch)
    }

    private fun cropFromImageViewCenterCrop(iv: ImageView, src: Bitmap, viewRect: RectF): Bitmap {
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val bw = src.width.toFloat()
        val bh = src.height.toFloat()

        val viewAspect = vw / vh
        val bmpAspect = bw / bh
        val scale: Float
        val dx: Float
        val dy: Float
        if (bmpAspect > viewAspect) {
            scale = bh / vh
            val scaledBw = bw / scale
            dx = (scaledBw - vw) / 2f
            dy = 0f
        } else {
            scale = bw / vw
            dx = 0f
            val scaledBh = bh / scale
            dy = (scaledBh - vh) / 2f
        }

        val originX = ((viewRect.left + dx) * scale).toInt().coerceIn(0, bw.toInt() - 1)
        val originY = ((viewRect.top  + dy) * scale).toInt().coerceIn(0, bh.toInt() - 1)
        val cw = (viewRect.width()  * scale).toInt().coerceAtLeast(1)
        val ch = (viewRect.height() * scale).toInt().coerceAtLeast(1)
        val maxW = bw.toInt() - originX
        val maxH = bh.toInt() - originY
        val cropW = cw.coerceAtMost(maxW)
        val cropH = ch.coerceAtMost(maxH)
        return Bitmap.createBitmap(src, originX, originY, cropW, cropH)
    }

    private fun setTorch(on: Boolean) {
        if (DEMO_MODE || !hasFlash) return
        try { cameraManager.setTorchMode(cameraId ?: return, on) } catch (_: Exception) {}
    }

    private fun showProgress(line: String, indeterminate: Boolean) {
        progressLine.text = line
        progressLine.visibility = android.view.View.VISIBLE
        progressBar.isIndeterminate = indeterminate
        progressBar.progress = 0
        progressBar.visibility = android.view.View.VISIBLE
    }

    private fun hideProgress() {
        progressLine.visibility = android.view.View.GONE
        progressBar.visibility = android.view.View.GONE
    }

    private fun switchToDemo(reason: String) {
        DEMO_MODE = true
        android.util.Log.w("AuthenticateActivity", "DEMO fallback: $reason")
        demoImage.visibility = android.view.View.VISIBLE
        textureView.visibility = android.view.View.GONE
        overlayView.statusText = "Align your pattern and tap Capture"
        // IMPORTANT: stop any active camera
        try { setTorch(false) } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
    }
}
