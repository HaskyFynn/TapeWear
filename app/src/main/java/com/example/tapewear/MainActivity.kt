package com.example.tapewear

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

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

        // --- Global YOLO detector init (once, in background) ---
        if (ModelManager.detector == null) {
            CoroutineScope(Dispatchers.IO).launch {
                val t0 = System.currentTimeMillis()
                val threads = modelThreads()
                try {
                    ModelManager.detector = ModelManager.TFLiteYoloDetector(
                        context = applicationContext,
                        assetName = "best_float32.tflite",
                        numThreads = threads
                    )
                } catch(e: Exception) {
                    Log.e("TapeWear_Main", "Failed to init YOLO: ${e.message}")
                    ModelManager.detector = null
                }
                
                try {
                    ModelManager.mlEmbedder = TfLiteEmbedder(
                        context = applicationContext,
                        assetName = "tapewear_embedder.tflite",
                        numThreads = threads
                    )
                } catch(e: Exception) {
                    Log.e("TapeWear_Main", "Failed to init ML Siamese Embedder: ${e.message}")
                }
                
                val dt = System.currentTimeMillis() - t0
                Log.i("TapeWear_Main", "AI Models initialized in ${dt} ms (threads=$threads)")
            }
        } else {
            Log.i("TapeWear_Main", "Reusing existing AI model instances.")
        }

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
        updateSlotsStatus()
    }

    private fun updateSlotsStatus() {
        val tv = findViewById<TextView>(R.id.slotsStatusText) ?: return
        
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

    override fun onDestroy() {
        super.onDestroy()
        Log.i("TapeWear_Main", "MainActivity onDestroy()")
    }
}

