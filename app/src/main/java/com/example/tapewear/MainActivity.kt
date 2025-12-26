package com.example.tapewear

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        // --- AppBar setup ---
        val appBar = findViewById<MaterialToolbar>(R.id.appbarInc)
        if (appBar == null) {
            Log.e("TAWRing", "AppBar not found. Is include_appbar included in activity_main?")
        } else {
            appBar.title = "TAWRing"
            appBar.navigationIcon = null
        }

        // --- Global YOLO detector init (once, in background) ---
        if (ModelManager.detector == null) {
            CoroutineScope(Dispatchers.IO).launch {
                val t0 = System.currentTimeMillis()
                try {
                    // This is your YOLO TFLite file in assets/
                    // Make sure the file name matches exactly.
                    ModelManager.detector = ModelManager.TFLiteYoloDetector(
                        context = applicationContext,
                        assetName = "best_float32.tflite",
                        numThreads = 2
                    )
                    val dt = System.currentTimeMillis() - t0
                    Log.i("TapeWear_Main", "YOLO detector ready in ${dt} ms")
                } catch (e: Exception) {
                    val dt = System.currentTimeMillis() - t0
                    Log.e(
                        "TapeWear_Main",
                        "Failed to init YOLO detector after ${dt} ms: ${e.message}",
                        e
                    )
                    // Fallback: overlay-based detector that just uses the framing box
                    ModelManager.detector = ModelManager.OverlayDetector(null, null)
                    Log.w("TapeWear_Main", "Using OverlayDetector fallback.")
                }
            }
        } else {
            Log.i("TapeWear_Main", "Reusing existing YOLO detector instance.")
        }

        // We deliberately do NOT touch ModelManager.embedder here.
        // It stays as ORBColorEmbedder defined in ModelManager.

        findViewById<Button>(R.id.btnGoRegister).setOnClickListener {
            Log.d("TapeWear_Main", "Navigate: RegisterActivity")
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        findViewById<Button>(R.id.btnGoAuthenticate).setOnClickListener {
            Log.d("TapeWear_Main", "Navigate: AuthenticateActivity")
            startActivity(Intent(this, AuthenticateActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("TapeWear_Main", "MainActivity onDestroy() → closing ModelManager.")
        ModelManager.close()
    }
}