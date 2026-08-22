package com.example.tapewear.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tapewear.R
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.ExperimentStore
import com.example.tapewear.data.SettingsStore
import com.example.tapewear.ml.ModelManager
import com.example.tapewear.ml.TfLiteEmbedder
import com.example.tapewear.ui.auth.AuthenticateActivity
import com.example.tapewear.ui.register.RegisterActivity
import com.example.tapewear.ui.settings.SettingsActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val modelInitRunning = AtomicBoolean(false)

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Log.i("TapeWear_Main", "Camera permission granted")
        else Log.w("TapeWear_Main", "Camera permission denied by user")
    }

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
        if (ModelManager.isDetectorReady() &&
            ModelManager.detectorMatchesConfig() &&
            ModelManager.mlEmbedder != null
        ) {
            Log.i("TapeWear_Main", "Reusing existing AI model instances.")
            return
        }
        if (!modelInitRunning.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            val t0 = System.currentTimeMillis()
            val threads = modelThreads()
            try {
                synchronized(ModelManager) {
                    ModelManager.ensureConfiguredDetector(
                        context = applicationContext,
                        numThreads = threads
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "TapeWear_Main",
                    "Failed to init ${ModelManager.configuredDetectorLabel()}: ${e.message}"
                )
                ModelManager.resetDetector()
            }

            try {
                synchronized(ModelManager) {
                    if (ModelManager.mlEmbedder == null) {
                        ModelManager.mlEmbedder = TfLiteEmbedder(
                            context = applicationContext,
                            assetName = "tapewear_embedder.tflite",
                            numThreads = threads
                        )
                    }
                }
            } catch(e: Exception) {
                Log.e("TapeWear_Main", "Failed to init ML Siamese Embedder: ${e.message}")
            }

            val dt = System.currentTimeMillis() - t0
            Log.i(
                "TapeWear_Main",
                "AI models initialized in ${dt} ms (threads=$threads, detector=${ModelManager.activeDetectorName()})"
            )
            modelInitRunning.set(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load custom thresholds from SharedPreferences
        SettingsStore.load(this)

        setContentView(R.layout.activity_main)

        // --- AppBar setup ---
        val appBar = findViewById<MaterialToolbar>(R.id.appbarInc)
        if (appBar == null) {
            Log.e("TAWRing", "AppBar not found. Is include_appbar included in activity_main?")
        } else {
            appBar.title = "TAWRing"
            appBar.navigationIcon = null
        }

        // --- Pre-request camera permission so Register/Authenticate don't reload ---
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }

        // --- Global detector/embedder init (once, in background) ---
        ensureModelsInitializedIfNeeded()

        findViewById<Button>(R.id.btnGoRegister).setOnClickListener {
            Log.d("TapeWear_Main", "Navigate: RegisterActivity")
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<Button>(R.id.btnGoAuthenticate).setOnClickListener {
            Log.d("TapeWear_Main", "Navigate: AuthenticateActivity")
            startActivity(Intent(this, AuthenticateActivity::class.java))
        }

        findViewById<Button>(R.id.btnGoSettings).setOnClickListener {
            Log.d("TapeWear_Main", "Navigate: SettingsActivity")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        SettingsStore.load(this)
        ensureModelsInitializedIfNeeded()
        updateSlotsStatus()
        updateExperimentBanner()
    }

    private fun updateExperimentBanner() {
        val banner = findViewById<View>(R.id.experimentBanner) ?: return
        val summary = findViewById<TextView>(R.id.experimentConditionsSummary) ?: return
        
        if (AuthConfig.EXPERIMENT_MODE) {
            banner.visibility = View.VISIBLE
            val metadata = ExperimentStore.getStudyMetadata(this)
            val report = ExperimentStore.buildCoverageReport(this)
            val participantLine = if (metadata.participantId.isNotBlank()) {
                "Session: ${metadata.sessionId.ifBlank { "Unsaved" }}"
            } else {
                "Study metadata incomplete"
            }
            val nextLine = report.nextAction() ?: "Awaiting activity"
            summary.text = "$participantLine\n$nextLine"
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun updateSlotsStatus() {
        val tv = findViewById<TextView>(R.id.slotsStatusText) ?: return
        
        if (AuthConfig.EXPERIMENT_MODE) {
            val report = ExperimentStore.buildCoverageReport(this)
            if (report.sessionTags.isEmpty()) {
                tv.text = "No tags registered yet."
            } else {
                tv.text = "Registered Tags: " + report.sessionTags.joinToString(", ")
            }
        } else {
            val enrolled = mutableListOf<Int>()
            for (i in 1..50) {
                if (ModelManager.hasModel(this, i)) {
                    enrolled.add(i)
                }
            }
            
            if (enrolled.isEmpty()) {
                tv.text = "No patterns enrolled yet."
            } else {
                tv.text = "Enrolled: " + enrolled.joinToString(", ") { "Slot $it" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("TapeWear_Main", "MainActivity onDestroy()")
    }
}
