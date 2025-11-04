package com.example.tapewear

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataCollectionActivity : AppCompatActivity() {

    // Night mode toggle
    private var nightMode = false
    private val DEMO_MODE = false

    // Views match activity_data_collection.xml
    private lateinit var textureViewDC: TextureView
    private lateinit var overlayViewDC: OverlayView
    private lateinit var demoImageDC: ImageView

    private lateinit var btnStartTagDC: Button
    private lateinit var btnExportDC: Button
    private lateinit var flashCheckDC: CheckBox

    private lateinit var progressSlotDC: LinearLayout
    private lateinit var progressBarDC: ProgressBar
    private lateinit var progressLineDC: TextView

    private lateinit var topMessageDC: TextView
    private lateinit var angleStatusDC: TextView
    private lateinit var angleHintDC: TextView
    private lateinit var previewThumbDC: ImageView
    private lateinit var previewLabelDC: TextView
    private lateinit var flashHintDC: TextView

    // Session and tag dirs
    private lateinit var sessionDir: File
    private lateinit var tagRootDir: File
    private lateinit var angleDirImages: File
    private lateinit var angleDirLabels: File

    // Camera2
    private val cameraManager by lazy { getSystemService(CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String = "0"
    private var previewSize = Size(640, 480)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reqBuilder: CaptureRequest.Builder? = null
    private var hasFlash = false

    // 3 angles
    private enum class AngleStage { FRONT, TILT_LEFT, TILT_RIGHT, DONE }
    private var currentAngle = AngleStage.FRONT
    private var currentTagIdx = 1

    // durations
    private val perAngleMs = 10_000L
    private val perAngleStepMs = 220L

    // permissions
    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) startWhenReady() else toast("Camera permission required")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_collection)

        // bind
        textureViewDC    = findViewById(R.id.textureViewDC)
        overlayViewDC    = findViewById(R.id.overlayViewDC)
        demoImageDC      = findViewById(R.id.demoImageDC)

        btnStartTagDC    = findViewById(R.id.btnStartTagDC)
        btnExportDC      = findViewById(R.id.btnExportDC)
        flashCheckDC     = findViewById(R.id.flashCheckDC)

        progressSlotDC   = findViewById(R.id.progressSlotDC)
        progressBarDC    = findViewById(R.id.progressBarDC)
        progressLineDC   = findViewById(R.id.progressLineDC)

        topMessageDC     = findViewById(R.id.topMessageDC)
        angleStatusDC    = findViewById(R.id.angleStatusDC)
        angleHintDC      = findViewById(R.id.angleHintDC)
        previewThumbDC   = findViewById(R.id.previewThumbDC)
        previewLabelDC   = findViewById(R.id.previewLabelDC)
        flashHintDC      = findViewById(R.id.flashHintDC)

        // initial UI
        progressSlotDC.visibility = View.GONE
        btnExportDC.visibility = View.GONE
        overlayViewDC.statusText = "Align the tag in the box"
        updateAngleHeader()
        refreshHeader()

        flashCheckDC.setOnCheckedChangeListener { _, b ->
            nightMode = b
            refreshHeader()
        }

        // session root
        sessionDir = File(
            cacheDir,
            "session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        ).apply { mkdirs() }

        btnStartTagDC.setOnClickListener { beginTagSession() }
        btnExportDC.setOnClickListener {
            overlayViewDC.statusText = "Packaging session"
            fadeDisable(btnExportDC)
            btnExportDC.post {
                try {
                    val zip = zipDirectoryReturn(sessionDir)
                    overlayViewDC.statusText = "Session packaged"
                    shareZip(zip)
                } catch (e: Exception) {
                    overlayViewDC.statusText = "Export failed"
                    toast("Export failed: ${e.message}")
                } finally {
                    fadeEnable(btnExportDC)
                }
            }
        }

        // demo or real camera
        if (DEMO_MODE) {
            demoImageDC.visibility = View.VISIBLE
            textureViewDC.visibility = View.GONE
            flashHintDC.visibility = if (nightMode) View.VISIBLE else View.GONE
            flashHintDC.text = if (nightMode) "Torch ON" else ""
            ModelManager.detector = ModelManager.OverlayDetector(overlayViewDC, null)
            return
        }

        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        hasFlash = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        ModelManager.detector = ModelManager.OverlayDetector(overlayViewDC, textureViewDC)

        textureViewDC.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startWhenReady()
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (!DEMO_MODE && textureViewDC.isAvailable) startWhenReady()
    }

    override fun onPause() {
        super.onPause()
        if (!DEMO_MODE) {
            setTorch(false)
            session?.close(); session = null
            cameraDevice?.close(); cameraDevice = null
        }
    }

    // camera bring up
    private fun startWhenReady() {
        if (!hasCamPerm()) {
            perms.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }
        if (!textureViewDC.isAvailable) return
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
            toast("Permission missing")
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val st = textureViewDC.surfaceTexture ?: return
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
                    s.setRepeatingRequest(reqBuilder!!.build(), null, mainHandler)
                    overlayViewDC.statusText = "Tap Start Tag to begin"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    toast("Preview failed")
                }
            },
            mainHandler
        )
    }

    // header
    private fun refreshHeader() {
        topMessageDC.text = if (nightMode) "Data Collection Night" else "Data Collection Day"
        flashHintDC.visibility = if (nightMode) View.VISIBLE else View.GONE
        flashHintDC.text = if (nightMode) "Torch ON" else ""
    }

    @SuppressLint("SetTextI18n")
    private fun updateAngleHeader() {
        val idx = when (currentAngle) {
            AngleStage.FRONT -> 1
            AngleStage.TILT_LEFT -> 2
            AngleStage.TILT_RIGHT -> 3
            AngleStage.DONE -> 3
        }
        angleStatusDC.text = "Angle $idx of 3"
        angleHintDC.text = when (currentAngle) {
            AngleStage.FRONT -> "Hold frontal"
            AngleStage.TILT_LEFT -> "Tilt left"
            AngleStage.TILT_RIGHT -> "Tilt right"
            AngleStage.DONE -> "Complete"
        }
    }

    // 3 angle flow
    private fun beginTagSession() {
        // create tag dir
        val tagStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tagName = "TAG%02d_%s".format(Locale.US, currentTagIdx, tagStamp)
        tagRootDir = File(sessionDir, "tag_$tagName").apply { mkdirs() }

        currentAngle = AngleStage.FRONT
        updateAngleHeader()

        fadeDisable(btnStartTagDC)
        fadeShow(progressSlotDC)
        overlayViewDC.statusText = "Starting tag session"

        if (!DEMO_MODE && nightMode && hasFlash) setTorch(true)

        mainHandler.postDelayed({
            lockAeAwb(true)
            startAngleCapture()
        }, 400)
    }

    @SuppressLint("SetTextI18n")
    private fun startAngleCapture() {
        progressBarDC.isIndeterminate = false
        progressBarDC.progress = 0
        progressLineDC.text = "Capturing angle 0 percent"
        overlayViewDC.statusText = "Capturing ${currentAngle.name.lowercase(Locale.ROOT)}"

        val angleName = when (currentAngle) {
            AngleStage.FRONT -> "angle_front"
            AngleStage.TILT_LEFT -> "angle_tilt_left"
            AngleStage.TILT_RIGHT -> "angle_tilt_right"
            else -> "angle_unknown"
        }
        val angleRoot = File(tagRootDir, angleName).apply { mkdirs() }
        angleDirImages = File(angleRoot, "images").apply { mkdirs() }
        angleDirLabels = File(angleRoot, "labels").apply { mkdirs() }

        runAngleBurst()
    }

    private fun runAngleBurst() {
        val started = SystemClock.elapsedRealtime()
        var saved = 0
        val loop = object : Runnable {
            @SuppressLint("SetTextI18n")
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - started
                val pct = (elapsed.toFloat() / perAngleMs * 100).coerceIn(0f, 100f)
                progressBarDC.progress = pct.toInt()
                progressLineDC.text = "Capturing angle ${pct.toInt()} percent"

                val full = snapshotFull()
                if (full != null) {
                    val base = "img_${System.currentTimeMillis()}"
                    val imgFile = File(angleDirImages, "$base.jpg")
                    FileOutputStream(imgFile).use { out ->
                        full.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    writeYoloLabelForOverlay(full.width, full.height, base, angleDirLabels)
                    showPreviewFrom(full, imgFile.name)
                    full.recycle()
                    saved++
                }

                if (elapsed < perAngleMs) mainHandler.postDelayed(this, perAngleStepMs)
                else finishAngle(saved)
            }
        }
        mainHandler.post(loop)
    }

    private fun finishAngle(saved: Int) {
        lockAeAwb(false)
        overlayViewDC.statusText = "Angle saved $saved frames"
        currentAngle = when (currentAngle) {
            AngleStage.FRONT -> AngleStage.TILT_LEFT
            AngleStage.TILT_LEFT -> AngleStage.TILT_RIGHT
            AngleStage.TILT_RIGHT -> AngleStage.DONE
            AngleStage.DONE -> AngleStage.DONE
        }
        updateAngleHeader()
        if (currentAngle == AngleStage.DONE) {
            packageTagZip()
        } else {
            mainHandler.postDelayed({
                lockAeAwb(true)
                startAngleCapture()
            }, 5000)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun packageTagZip() {
        overlayViewDC.statusText = "Packaging tag"
        progressLineDC.text = "Packaging tag"
        progressBarDC.isIndeterminate = true
        btnExportDC.visibility = View.VISIBLE
        fadeDisable(btnExportDC)
        btnExportDC.post {
            try {
                if (!DEMO_MODE && nightMode) setTorch(false)
                val zip = zipDirectoryReturn(tagRootDir)
                overlayViewDC.statusText = "Packaged ${zip.name}"
                shareZip(zip)
            } catch (e: Exception) {
                overlayViewDC.statusText = "Tag export failed"
                toast("Export failed: ${e.message}")
            } finally {
                progressBarDC.isIndeterminate = false
                fadeHide(progressSlotDC)
                fadeEnable(btnExportDC)
                fadeEnable(btnStartTagDC)
                currentTagIdx += 1
                currentAngle = AngleStage.FRONT
                updateAngleHeader()
            }
        }
    }

    // helpers
    private fun snapshotFull(): Bitmap? = try {
        if (DEMO_MODE) {
            val d = demoImageDC.drawable as? BitmapDrawable ?: return null
            d.bitmap.copy(d.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            val vw = textureViewDC.width
            val vh = textureViewDC.height
            if (vw == 0 || vh == 0) null else textureViewDC.getBitmap(vw, vh)
        }
    } catch (_: Exception) { null }

    @SuppressLint("UseKtx")
    private fun showPreviewFrom(full: Bitmap, name: String) {
        val w = 96
        val h = (full.height * (w.toFloat() / full.width)).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(full, w, h, true)
        previewThumbDC.setImageBitmap(thumb)
        previewLabelDC.text = name
        if (previewThumbDC.visibility != View.VISIBLE) fadeShow(previewThumbDC)
        if (previewLabelDC.visibility != View.VISIBLE) fadeShow(previewLabelDC)
    }

    private fun writeYoloLabelForOverlay(imgW: Int, imgH: Int, base: String, outDir: File) {
        val vr = overlayViewDC.getFramingBox()
        val vw = textureViewDC.width.coerceAtLeast(1)
        val vh = textureViewDC.height.coerceAtLeast(1)

        val left   = (vr.left   * imgW / vw).toInt().coerceIn(0, imgW - 1)
        val top    = (vr.top    * imgH / vh).toInt().coerceIn(0, imgH - 1)
        val right  = (vr.right  * imgW / vw).toInt().coerceIn(left + 1, imgW)
        val bottom = (vr.bottom * imgH / vh).toInt().coerceIn(top + 1, imgH)

        val bx = (left + right) / 2.0
        val by = (top + bottom) / 2.0
        val bw = (right - left).toDouble()
        val bh = (bottom - top).toDouble()

        val cxN = (bx / imgW).coerceIn(0.0, 1.0)
        val cyN = (by / imgH).coerceIn(0.0, 1.0)
        val wN  = (bw / imgW).coerceIn(0.0, 1.0)
        val hN  = (bh / imgH).coerceIn(0.0, 1.0)

        File(outDir, "$base.txt").writeText("0 $cxN $cyN $wN $hN\n")
    }

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

    private fun zipDirectoryReturn(dir: File): File {
        val outZip = File(cacheDir, "${dir.name}.zip")
        java.util.zip.ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            fun add(f: File, base: String) {
                if (f.isDirectory) f.listFiles()?.forEach { add(it, base + f.name + "/") }
                else {
                    val entry = java.util.zip.ZipEntry(base + f.name)
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            add(dir, "")
        }
        return outZip
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
        val chooser = android.content.Intent.createChooser(send, "Share tag zip").apply {
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(chooser)
    }

    // tiny UI helpers
    @SuppressLint("UseKtx")
    private fun fadeShow(v: View, dur: Long = 180) {
        if (v.visibility == View.VISIBLE) return
        v.alpha = 0f
        v.visibility = View.VISIBLE
        v.animate().alpha(1f).setDuration(dur).start()
    }

    private fun fadeHide(v: View, dur: Long = 180) {
        if (v.visibility != View.VISIBLE) return
        v.animate().alpha(0f).setDuration(dur).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun fadeDisable(v: View, dur: Long = 120) { v.isEnabled = false; v.animate().alpha(0.4f).setDuration(dur).start() }
    private fun fadeEnable(v: View, dur: Long = 120) { v.isEnabled = true; v.animate().alpha(1f).setDuration(dur).start() }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
