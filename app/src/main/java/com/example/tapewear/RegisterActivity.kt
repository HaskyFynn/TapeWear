package com.example.tapewear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.*
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import android.view.View





class RegisterActivity : AppCompatActivity() {

    // ---- Demo toggle ----
    private val DEMO_MODE = true

    // ---- Views (IDs from activity_register.xml) ----
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

    // New/renamed views for the cleaner UI
    private lateinit var topMessage: TextView
    private lateinit var actionSlot: FrameLayout
    private lateinit var progressSlot: LinearLayout



    // ---- Session dir for saved captures ----
    private lateinit var sessionDir: File

    // ---- Flash plan counters (target ~30%) ----
    private var shotsTaken = 0
    private var flashesUsed = 0
    private var shouldUseFlashNext = false
    private var hasFlash = false

    // ---- Camera (live mode only) ----
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String = "0"
    private var previewSize = Size(640, 480)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Lifecycle ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Bind to IDs from activity_register.xml
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

// Initial UI state
        btnCapture.visibility   = View.VISIBLE
        progressSlot.visibility = View.GONE
        btnExport.visibility    = View.GONE
        topMessage.text = "Position your PUF to fill the capture box, then tap Capture"


        // Session folder
        sessionDir = File(cacheDir, "session_${System.currentTimeMillis()}").apply { mkdirs() }

        // Capture button
        btnCapture.setOnClickListener {
            // Swap UI to “registering”
            btnCapture.visibility = View.GONE
            progressSlot.visibility = View.VISIBLE
            topMessage.text = "Please wait as we register your pattern…"

            btnCapture.isEnabled = false

            val finishShot = {
                shotsTaken++
                if (shouldUseFlashNext) flashesUsed++
                scheduleFlashHint()
                btnCapture.postDelayed({ btnCapture.isEnabled = true }, 400)
            }

            val willUseRealTorch = shouldUseFlashNext && !DEMO_MODE && hasFlash

            if (willUseRealTorch) {
                setTorch(true)
                btnCapture.postDelayed({
                    captureAndShowRoi()
                    setTorch(false)
                    runRegistrationSequence()
                    finishShot()
                }, 180)
            } else {
                captureAndShowRoi()
                runRegistrationSequence()
                finishShot()
            }
        }


        // Export button
        btnExport.setOnClickListener {
            if (sessionDir.listFiles()?.isEmpty() != false) {
                Toast.makeText(this, "No captures yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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



        // DEMO mode shows static image, skips camera entirely
        if (DEMO_MODE) {
            demoImage.visibility = android.view.View.VISIBLE
            textureView.visibility = android.view.View.GONE
            overlayView.statusText = "Align the tag in the box"
            scheduleFlashHint()
            return
        }

        // ---- LIVE MODE ONLY BELOW ----

        // Prefer back camera
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        // Flash availability on chosen camera
        hasFlash = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        // Start when surface ready
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startWhenReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        scheduleFlashHint()
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

    override fun onStop() {
        super.onStop()
        if (!DEMO_MODE) setTorch(false)
    }

    // ---------------- Camera (live) ----------------
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

        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(15, 15))
        }.build()

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(req, null, mainHandler)
                    overlayView.statusText = "Camera ready"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Toast.makeText(this@RegisterActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            },
            mainHandler
        )
    }

    // ---------------- Capture + Save ROI ----------------
    private fun captureAndShowRoi() {
        try {
            val frame = overlayView.getFramingBox()  // RectF in view coords

            val roiBitmap: Bitmap = if (DEMO_MODE) {
                // Crop original drawable using centerCrop math
                val srcBmp = (demoImage.drawable as BitmapDrawable).bitmap
                val vw = demoImage.width.toFloat()
                val vh = demoImage.height.toFloat()
                val bw = srcBmp.width.toFloat()
                val bh = srcBmp.height.toFloat()

                val viewAspect = vw / vh
                val bmpAspect  = bw / bh
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

                val originX = ((frame.left + dx) * scale).toInt().coerceIn(0, bw.toInt() - 1)
                val originY = ((frame.top  + dy) * scale).toInt().coerceIn(0, bh.toInt() - 1)
                val cw = (frame.width()  * scale).toInt().coerceAtLeast(1)
                val ch = (frame.height() * scale).toInt().coerceAtLeast(1)
                val maxW = bw.toInt() - originX
                val maxH = bh.toInt() - originY
                val cropW = cw.coerceAtMost(maxW)
                val cropH = ch.coerceAtMost(maxH)
                Bitmap.createBitmap(srcBmp, originX, originY, cropW, cropH)
            } else {
                // Live: safe-sized snapshot of TextureView
                val vw = textureView.width
                val vh = textureView.height
                if (vw == 0 || vh == 0) {
                    Toast.makeText(this, "No camera frame", Toast.LENGTH_SHORT).show()
                    return
                }
                val longEdge = maxOf(vw, vh)
                val scaleDown = (longEdge / 960f).coerceAtLeast(1f)
                val bmpW = (vw / scaleDown).toInt().coerceAtLeast(1)
                val bmpH = (vh / scaleDown).toInt().coerceAtLeast(1)
                val fullBmp = textureView.getBitmap(bmpW, bmpH) ?: run {
                    Toast.makeText(this, "No camera frame", Toast.LENGTH_SHORT).show(); return
                }

                val scaleX = fullBmp.width.toFloat() / vw
                val scaleY = fullBmp.height.toFloat() / vh
                val left   = (frame.left   * scaleX).toInt().coerceIn(0, fullBmp.width - 1)
                val top    = (frame.top    * scaleY).toInt().coerceIn(0, fullBmp.height - 1)
                val right  = (frame.right  * scaleX).toInt().coerceIn(left + 1, fullBmp.width)
                val bottom = (frame.bottom * scaleY).toInt().coerceIn(top + 1, fullBmp.height)
                val cropW  = (right - left).coerceAtLeast(1)
                val cropH  = (bottom - top).coerceAtLeast(1)
                Bitmap.createBitmap(fullBmp, left, top, cropW, cropH)
            }

            // Save to session folder
            val file = File(sessionDir, "roi_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                roiBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // UI feedback
            previewThumb.setImageBitmap(roiBitmap)
            previewThumb.visibility = android.view.View.VISIBLE
            previewLabel.text = file.name
            previewLabel.visibility = android.view.View.VISIBLE
            overlayView.statusText = "Captured" + if (shouldUseFlashNext) " • flash" else ""

        } catch (e: Exception) {
            overlayView.statusText = "Capture failed"
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Torch + Flash Planning ----------------
    private fun setTorch(on: Boolean) {
        if (DEMO_MODE) return
        if (!hasFlash) return
        try { cameraManager.setTorchMode(cameraId, on) } catch (_: Exception) { }
    }

    private fun scheduleFlashHint() {
        val target = 0.30
        val expected = target * (shotsTaken + 1)
        val deficit = expected - flashesUsed
        val baseP = 0.30
        val bonus = deficit.coerceIn(0.0, 0.9)
        val p = (baseP + bonus).coerceIn(0.0, 1.0)

        shouldUseFlashNext = Math.random() < p
        flashHint.visibility = if (shouldUseFlashNext) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---------------- Registration Progress Sequence ----------------
    private fun runRegistrationSequence() {
        progressBar.progress = 0
        progressLine.text = "Taking still photos…"

        progressBar.postDelayed({
            progressBar.progress = 35
            progressLine.text = "Taking videos…"
        }, 500)

        progressBar.postDelayed({
            progressBar.progress = 65
            progressLine.text = "Flash light on for 30%…"
        }, 1000)

        progressBar.postDelayed({
            progressBar.progress = 85
            progressLine.text = "Saving photos & videos…"
        }, 1500)

        progressBar.postDelayed({
            progressBar.progress = 100
            progressLine.text = "Registration complete"
            overlayView.statusText = "Data collection complete"

            // Show Export and keep progress visible
            btnExport.visibility = View.VISIBLE

            // (Optional) change the top message to “Registration complete”
            topMessage.text = "Registration complete"

            // If you want to bring the Capture button back for another sample, uncomment:
            // btnCapture.visibility = View.VISIBLE
            // progressSlot.visibility = View.GONE
        }, 2000)
    }


    // ---------------- Export helpers ----------------
    private fun zipSession(dir: File): File {
        val zipFile = File(cacheDir, "${dir.name}.zip")
        java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val entry = java.util.zip.ZipEntry(f.name)
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
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
            grantUriPermission(
                ri.activityInfo.packageName,
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val chooser = android.content.Intent.createChooser(send, "Share session zip").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(chooser)
    }
}
