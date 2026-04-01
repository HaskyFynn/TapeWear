package com.example.tapewear

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchUseML: MaterialSwitch
    private lateinit var inputMatchThreshold: TextInputEditText
    private lateinit var inputYoloConf: TextInputEditText
    private lateinit var inputRegBurstMs: TextInputEditText
    private lateinit var inputRegFrames: TextInputEditText
    private lateinit var inputDayMotionMax: TextInputEditText
    private lateinit var inputDayBlurMin: TextInputEditText
    private lateinit var inputNightMotionMax: TextInputEditText
    private lateinit var inputNightBlurMin: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.load(this)
        setContentView(R.layout.activity_settings)

        switchUseML = findViewById(R.id.switchUseML)
        inputMatchThreshold = findViewById(R.id.inputMatchThreshold)
        inputYoloConf = findViewById(R.id.inputYoloConf)
        inputRegBurstMs = findViewById(R.id.inputRegBurstMs)
        inputRegFrames = findViewById(R.id.inputRegFrames)
        inputDayMotionMax = findViewById(R.id.inputDayMotionMax)
        inputDayBlurMin = findViewById(R.id.inputDayBlurMin)
        inputNightMotionMax = findViewById(R.id.inputNightMotionMax)
        inputNightBlurMin = findViewById(R.id.inputNightBlurMin)

        populateFields()

        // Live-update blur input fields when ML toggle changes
        switchUseML.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) { // Only update if user physically clicked it
                updateBlurHints(isChecked)
            }
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            saveFields()
        }

        findViewById<Button>(R.id.btnResetSettings).setOnClickListener {
            SettingsStore.resetToDefaults(this)
            populateFields()
            Toast.makeText(this, "Restored defaults", Toast.LENGTH_SHORT).show()
        }
    }

    private fun populateFields() {
        switchUseML.isChecked = AuthConfig.USE_ML_EMBEDDER
        inputMatchThreshold.setText(AuthConfig.MATCH_THRESHOLD.toString())
        inputYoloConf.setText(AuthConfig.YOLO_CONF_THRESHOLD.toString())
        inputRegBurstMs.setText(AuthConfig.REG_BURST_MS.toString())
        inputRegFrames.setText(AuthConfig.REG_TARGET_FRAMES.toString())
        inputDayMotionMax.setText(Quality.DAY.motionMax.toString())
        inputDayBlurMin.setText(Quality.DAY.blurMin.toString())
        inputNightMotionMax.setText(Quality.NIGHT.motionMax.toString())
        inputNightBlurMin.setText(Quality.NIGHT.blurMin.toString())

        // We don't call updateBlurHints() here because we want to show
        // whatever was actually saved in Quality.DAY.blurMin.
        // It will only overwrite if the user actively hits the ML toggle.
    }

    private fun updateBlurHints(mlOn: Boolean) {
        val dayParent = inputDayBlurMin.parent?.parent as? TextInputLayout
        val nightParent = inputNightBlurMin.parent?.parent as? TextInputLayout

        if (mlOn) {
            dayParent?.hint = "Min Blur (ML Optimized)"
            nightParent?.hint = "Min Blur (ML Optimized)"
        } else {
            dayParent?.hint = "Min Blur (Lower = softer focus, Default: 10.0)"
            nightParent?.hint = "Min Blur (Lower = softer focus, Default: 10.0)"
        }
    }

    override fun onPause() {
        super.onPause()
        saveFieldsSilently(showErrors = false)
    }

    private fun saveFieldsSilently(showErrors: Boolean = true): Boolean {
        try {
            val parsed = parseInputs()

            AuthConfig.USE_ML_EMBEDDER = switchUseML.isChecked
            AuthConfig.MATCH_THRESHOLD = parsed.matchThreshold
            AuthConfig.YOLO_CONF_THRESHOLD = parsed.yoloConfThreshold
            AuthConfig.REG_BURST_MS = parsed.regBurstMs
            AuthConfig.REG_TARGET_FRAMES = parsed.regTargetFrames

            Quality.DAY.motionMax = parsed.dayMotionMax
            Quality.DAY.blurMin = parsed.dayBlurMin

            Quality.NIGHT.motionMax = parsed.nightMotionMax
            Quality.NIGHT.blurMin = parsed.nightBlurMin

            SettingsStore.save(this)
            populateFields()
            return true
        } catch (e: IllegalArgumentException) {
            if (showErrors) {
                Toast.makeText(this, e.message ?: "Invalid settings", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            if (showErrors) {
                Toast.makeText(this, "Could not save settings", Toast.LENGTH_SHORT).show()
            }
        }
        return false
    }

    private fun saveFields() {
        val before = Pair(AuthConfig.MATCH_THRESHOLD, AuthConfig.YOLO_CONF_THRESHOLD)
        val ok = saveFieldsSilently(showErrors = true)
        if (!ok) return
        val changed = before.first != AuthConfig.MATCH_THRESHOLD || before.second != AuthConfig.YOLO_CONF_THRESHOLD
        val msg = if (changed) {
            "Saved (match=${"%.2f".format(Locale.US, AuthConfig.MATCH_THRESHOLD)}, yolo=${"%.2f".format(Locale.US, AuthConfig.YOLO_CONF_THRESHOLD)})"
        } else {
            "Settings saved"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish() // Return to main screen strictly when the save button is clicked
    }

    private data class ParsedInputs(
        val matchThreshold: Float,
        val yoloConfThreshold: Float,
        val regBurstMs: Long,
        val regTargetFrames: Int,
        val dayMotionMax: Double,
        val dayBlurMin: Double,
        val nightMotionMax: Double,
        val nightBlurMin: Double
    )

    private fun parseInputs(): ParsedInputs {
        val match = parseUnitThreshold(inputMatchThreshold, "Match Threshold")
        val yolo = parseUnitThreshold(inputYoloConf, "YOLO Confidence")

        val burstMs = parseLong(inputRegBurstMs, "Burst Duration (ms)").coerceAtLeast(500L)
        val regFrames = parseInt(inputRegFrames, "Target Good Frames").coerceAtLeast(1)

        val dayMotion = parseDouble(inputDayMotionMax, "Day Max Motion").coerceAtLeast(0.0)
        val dayBlur = parseDouble(inputDayBlurMin, "Day Min Blur").coerceAtLeast(0.0)
        val nightMotion = parseDouble(inputNightMotionMax, "Night Max Motion").coerceAtLeast(0.0)
        val nightBlur = parseDouble(inputNightBlurMin, "Night Min Blur").coerceAtLeast(0.0)

        return ParsedInputs(
            matchThreshold = match,
            yoloConfThreshold = yolo,
            regBurstMs = burstMs,
            regTargetFrames = regFrames,
            dayMotionMax = dayMotion,
            dayBlurMin = dayBlur,
            nightMotionMax = nightMotion,
            nightBlurMin = nightBlur
        )
    }

    private fun parseUnitThreshold(field: TextInputEditText, label: String): Float {
        val raw = parseFloat(field, label)
        val normalized = if (raw > 1f && raw <= 100f) raw / 100f else raw
        if (normalized !in 0f..1f) {
            throw IllegalArgumentException("$label must be between 0 and 1 (or 0..100%).")
        }
        return normalized
    }

    private fun parseFloat(field: TextInputEditText, label: String): Float {
        val txt = field.text?.toString()?.trim().orEmpty()
        return txt.toFloatOrNull()
            ?: throw IllegalArgumentException("Invalid $label.")
    }

    private fun parseDouble(field: TextInputEditText, label: String): Double {
        val txt = field.text?.toString()?.trim().orEmpty()
        return txt.toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid $label.")
    }

    private fun parseLong(field: TextInputEditText, label: String): Long {
        val txt = field.text?.toString()?.trim().orEmpty()
        return txt.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid $label.")
    }

    private fun parseInt(field: TextInputEditText, label: String): Int {
        val txt = field.text?.toString()?.trim().orEmpty()
        return txt.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid $label.")
    }
}
