package com.example.tapewear.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.tapewear.Quality
import com.example.tapewear.R
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.ExperimentStore
import com.example.tapewear.data.SettingsStore
import com.example.tapewear.ml.CrossAuthEngine
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
    private lateinit var switchHandsFree: MaterialSwitch
    private lateinit var inputHandsFreeHits: TextInputEditText
    private lateinit var switchExperimentMode: MaterialSwitch
    private lateinit var switchExperimentFlash: MaterialSwitch
    private lateinit var experimentActionsGroup: android.widget.LinearLayout

    private lateinit var inputOperatorId: TextInputEditText
    private lateinit var inputStudyBlock: TextInputEditText
    private lateinit var textStudySummary: TextView
    private lateinit var btnStartNewStudySession: Button
    private lateinit var btnExportMetrics: Button
    private lateinit var btnRunCrossAuth: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var btnResetSettings: Button
    private lateinit var settingsStatusRow: View
    private lateinit var settingsStatusSpinner: ProgressBar
    private lateinit var textSettingsStatus: TextView
    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveRunnable: Runnable? = null
    private var statusHideRunnable: Runnable? = null
    private var suppressAutosaveSignals = false
    private var autosavePending = false

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
        switchHandsFree = findViewById(R.id.switchHandsFree)
        inputHandsFreeHits = findViewById(R.id.inputHandsFreeHits)
        switchExperimentMode = findViewById(R.id.switchExperimentMode)
        switchExperimentFlash = findViewById(R.id.switchExperimentFlash)
        experimentActionsGroup = findViewById(R.id.experimentActionsGroup)

        inputOperatorId = findViewById(R.id.inputOperatorId)
        inputStudyBlock = findViewById(R.id.inputStudyBlock)
        textStudySummary = findViewById(R.id.textStudySummary)
        btnStartNewStudySession = findViewById(R.id.btnStartNewStudySession)
        btnExportMetrics = findViewById(R.id.btnExportMetrics)
        btnRunCrossAuth = findViewById(R.id.btnRunCrossAuth)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnResetSettings = findViewById(R.id.btnResetSettings)
        settingsStatusRow = findViewById(R.id.settingsStatusRow)
        settingsStatusSpinner = findViewById(R.id.settingsStatusSpinner)
        textSettingsStatus = findViewById(R.id.textSettingsStatus)

        populateFields()
        bindAutosaveListeners()

        btnSaveSettings.setOnClickListener {
            saveFields()
        }

        btnResetSettings.setOnClickListener {
            cancelPendingAutosave()
            SettingsStore.resetToDefaults(this)
            populateFields()
            showSettingsStatus("Defaults restored", showSpinner = false, autoHideMs = 1200L)
            Toast.makeText(this, "Restored defaults", Toast.LENGTH_SHORT).show()
        }
        
        btnExportMetrics.setOnClickListener {
            exportMetrics()
        }

        btnStartNewStudySession.setOnClickListener {
            startNewStudySession()
        }
        
        btnRunCrossAuth.setOnClickListener {
            runCrossAuth()
        }
    }

    private fun populateFields() {
        suppressAutosaveSignals = true
        switchUseML.isChecked = AuthConfig.USE_ML_EMBEDDER
        inputMatchThreshold.setText(AuthConfig.MATCH_THRESHOLD.toString())
        inputYoloConf.setText(AuthConfig.YOLO_CONF_THRESHOLD.toString())
        inputRegBurstMs.setText(AuthConfig.REG_BURST_MS.toString())
        inputRegFrames.setText(AuthConfig.REG_TARGET_FRAMES.toString())
        inputDayMotionMax.setText(Quality.DAY.motionMax.toString())
        inputDayBlurMin.setText(Quality.DAY.blurMin.toString())
        inputNightMotionMax.setText(Quality.NIGHT.motionMax.toString())
        inputNightBlurMin.setText(Quality.NIGHT.blurMin.toString())
        switchHandsFree.isChecked = AuthConfig.HANDS_FREE_ENABLED
        inputHandsFreeHits.setText(AuthConfig.HANDS_FREE_CONSECUTIVE_HITS.toString())
        
        switchExperimentMode.isChecked = AuthConfig.EXPERIMENT_MODE
        experimentActionsGroup.visibility = if (AuthConfig.EXPERIMENT_MODE) android.view.View.VISIBLE else android.view.View.GONE
        val metadata = ExperimentStore.getStudyMetadata(this)

        inputOperatorId.setText(metadata.operatorId)
        inputStudyBlock.setText(metadata.studyBlock)
        updateStudySummary()

        switchExperimentFlash.isChecked = AuthConfig.EXPERIMENT_FLASH_ENABLED

        // We don't call updateBlurHints() here because we want to show
        // whatever was actually saved in Quality.DAY.blurMin.
        // It will only overwrite if the user actively hits the ML toggle.
        suppressAutosaveSignals = false
    }

    private fun bindAutosaveListeners() {
        listOf(
            inputMatchThreshold,
            inputYoloConf,
            inputRegBurstMs,
            inputRegFrames,
            inputDayMotionMax,
            inputDayBlurMin,
            inputNightMotionMax,
            inputNightBlurMin,
            inputHandsFreeHits,

            inputOperatorId,
            inputStudyBlock
        ).forEach { field ->
            field.doAfterTextChanged {
                scheduleAutosave()
            }
        }

        listOf(
            switchUseML,
            switchHandsFree,
            switchExperimentMode,
            switchExperimentFlash
        ).forEach { toggle ->
            toggle.setOnCheckedChangeListener { buttonView, isChecked ->
                if (toggle === switchUseML && buttonView.isPressed) {
                    updateBlurHints(isChecked)
                }
                if (toggle === switchExperimentMode) {
                    experimentActionsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
                    updateStudySummary()
                }
                scheduleAutosave()
            }
        }
    }

    private fun scheduleAutosave(delayMs: Long = 650L) {
        if (suppressAutosaveSignals) return
        autosavePending = true
        showSettingsStatus("Saving settings...", showSpinner = true)
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        autosaveRunnable = Runnable {
            autosavePending = false
            val ok = saveFieldsSilently(showErrors = false, refreshUi = false)
            if (ok) {
                showSettingsStatus("Settings applied", showSpinner = false, autoHideMs = 900L)
            } else {
                showSettingsStatus("Settings not applied yet. Check invalid fields.", showSpinner = false, autoHideMs = 1800L)
            }
        }
        autosaveHandler.postDelayed(autosaveRunnable!!, delayMs)
    }

    private fun cancelPendingAutosave() {
        autosaveRunnable?.let { autosaveHandler.removeCallbacks(it) }
        autosaveRunnable = null
        autosavePending = false
    }

    private fun flushPendingAutosave(showErrors: Boolean): Boolean {
        cancelPendingAutosave()
        return saveFieldsSilently(showErrors = showErrors, refreshUi = false)
    }

    private fun showSettingsStatus(
        message: String,
        showSpinner: Boolean,
        autoHideMs: Long? = null
    ) {
        statusHideRunnable?.let { autosaveHandler.removeCallbacks(it) }
        settingsStatusRow.visibility = View.VISIBLE
        settingsStatusSpinner.visibility = if (showSpinner) View.VISIBLE else View.GONE
        textSettingsStatus.text = message
        if (autoHideMs != null) {
            statusHideRunnable = Runnable {
                settingsStatusRow.visibility = View.GONE
            }
            autosaveHandler.postDelayed(statusHideRunnable!!, autoHideMs)
        } else {
            statusHideRunnable = null
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        btnSaveSettings.isEnabled = enabled
        btnResetSettings.isEnabled = enabled
        btnStartNewStudySession.isEnabled = enabled
        btnExportMetrics.isEnabled = enabled
        btnRunCrossAuth.isEnabled = enabled
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
        flushPendingAutosave(showErrors = false)
    }

    private fun saveFieldsSilently(
        showErrors: Boolean = true,
        refreshUi: Boolean = true
    ): Boolean {
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

            AuthConfig.HANDS_FREE_ENABLED = switchHandsFree.isChecked
            AuthConfig.HANDS_FREE_CONSECUTIVE_HITS = parsed.handsFreeHits
            AuthConfig.EXPERIMENT_MODE = switchExperimentMode.isChecked
            AuthConfig.EXPERIMENT_FLASH_ENABLED = switchExperimentFlash.isChecked
            ExperimentStore.saveStudyMetadata(
                context = this,

                operatorId = parsed.operatorId,
                studyBlock = parsed.studyBlock
            )

            SettingsStore.save(this)
            if (refreshUi) {
                populateFields()
            } else {
                updateStudySummary()
            }
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
        cancelPendingAutosave()
        val before = Pair(AuthConfig.MATCH_THRESHOLD, AuthConfig.YOLO_CONF_THRESHOLD)
        showSettingsStatus("Saving settings... Please wait.", showSpinner = true)
        setActionButtonsEnabled(false)
        val ok = saveFieldsSilently(showErrors = true, refreshUi = true)
        if (!ok) {
            setActionButtonsEnabled(true)
            showSettingsStatus("Settings not saved", showSpinner = false, autoHideMs = 1500L)
            return
        }
        val changed = before.first != AuthConfig.MATCH_THRESHOLD || before.second != AuthConfig.YOLO_CONF_THRESHOLD
        val msg = if (changed) {
            "Saved (match=${"%.2f".format(Locale.US, AuthConfig.MATCH_THRESHOLD)}, yolo=${"%.2f".format(Locale.US, AuthConfig.YOLO_CONF_THRESHOLD)})"
        } else {
            "Settings saved"
        }
        autosaveHandler.postDelayed({
            setActionButtonsEnabled(true)
            showSettingsStatus(msg, showSpinner = false, autoHideMs = 1000L)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            finish() // Return to main screen strictly when the save button is clicked
        }, 350L)
    }

    private data class ParsedInputs(
        val matchThreshold: Float,
        val yoloConfThreshold: Float,
        val regBurstMs: Long,
        val regTargetFrames: Int,
        val dayMotionMax: Double,
        val dayBlurMin: Double,
        val nightMotionMax: Double,
        val nightBlurMin: Double,
        val handsFreeHits: Int,

        val operatorId: String,
        val studyBlock: String
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
        val handsFreeHits = parseInt(inputHandsFreeHits, "Consecutive Detections").coerceAtLeast(1)

        val operatorId = inputOperatorId.text?.toString().orEmpty().trim()
        val studyBlock = inputStudyBlock.text?.toString().orEmpty().trim()

        return ParsedInputs(
            matchThreshold = match,
            yoloConfThreshold = yolo,
            regBurstMs = burstMs,
            regTargetFrames = regFrames,
            dayMotionMax = dayMotion,
            dayBlurMin = dayBlur,
            nightMotionMax = nightMotion,
            nightBlurMin = nightBlur,
            handsFreeHits = handsFreeHits,

            operatorId = operatorId,
            studyBlock = studyBlock
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

    private fun updateStudySummary() {
        textStudySummary.text = ExperimentStore.buildCoverageReport(this).summaryText()
    }

    private fun startNewStudySession() {
        cancelPendingAutosave()
        if (!flushPendingAutosave(showErrors = true)) return
        try {
            showSettingsStatus("Starting study session... Please wait.", showSpinner = true)
            setActionButtonsEnabled(false)
            ExperimentStore.saveStudyMetadata(
                context = this,

                operatorId = inputOperatorId.text?.toString().orEmpty(),
                studyBlock = inputStudyBlock.text?.toString().orEmpty()
            )
            val metadata = ExperimentStore.startNewStudySession(this)
            autosaveHandler.postDelayed({
                setActionButtonsEnabled(true)
                updateStudySummary()
                showSettingsStatus("Started ${metadata.sessionId}", showSpinner = false, autoHideMs = 1200L)
                Toast.makeText(this, "Started ${metadata.sessionId}", Toast.LENGTH_SHORT).show()
            }, 350L)
        } catch (e: IllegalArgumentException) {
            setActionButtonsEnabled(true)
            showSettingsStatus(e.message ?: "Set study metadata first", showSpinner = false, autoHideMs = 1500L)
            Toast.makeText(this, e.message ?: "Set study metadata first", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun exportMetrics() {
        cancelPendingAutosave()
        if (!flushPendingAutosave(showErrors = true)) return
        val metricsDir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "TapeWear_Metrics")
        if (AuthConfig.EXPERIMENT_MODE) {
            val report = ExperimentStore.buildCoverageReport(this)
            if (!report.readyForExport) {
                updateStudySummary()
                Toast.makeText(this, report.nextAction() ?: "Current study session is incomplete.", Toast.LENGTH_LONG).show()
                return
            }
            ExperimentStore.writeStudySummaryFile(this)
        }
        val hasContent = metricsDir.exists() && metricsDir.walkTopDown().any { it.isFile }
        if (!hasContent) {
            Toast.makeText(this, "No metrics to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        showSettingsStatus("Preparing metrics export... Please wait.", showSpinner = true)
        Toast.makeText(this, "Zipping metrics...", Toast.LENGTH_SHORT).show()
        btnExportMetrics.isEnabled = false
        
        Thread {
            try {
                val zipFile = java.io.File(cacheDir, "TapeWearMetrics_${System.currentTimeMillis()}.zip")

                java.io.FileOutputStream(zipFile).use { fos ->
                    java.util.zip.ZipOutputStream(fos).use { zos ->
                        metricsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relativePath = file.toRelativeString(metricsDir)
                            zos.putNextEntry(java.util.zip.ZipEntry(relativePath))
                            java.io.FileInputStream(file).use { fis ->
                                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                                var length: Int
                                while (fis.read(bytes).also { length = it } != -1) {
                                    zos.write(bytes, 0, length)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
                
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    zipFile
                )
                
                runOnUiThread {
                    btnExportMetrics.isEnabled = true
                    showSettingsStatus("Metrics zip ready to share", showSpinner = false, autoHideMs = 1500L)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(intent, "Share Metrics Zip"))
                }
                
            } catch (e: Exception) {
                android.util.Log.e("TapeWear_Settings", "Export failed", e)
                runOnUiThread {
                    btnExportMetrics.isEnabled = true
                    showSettingsStatus("Export failed", showSpinner = false, autoHideMs = 1500L)
                    Toast.makeText(this@SettingsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun runCrossAuth() {
        if (!AuthConfig.EXPERIMENT_MODE) return
        cancelPendingAutosave()
        if (!flushPendingAutosave(showErrors = true)) return
        
        showSettingsStatus("Computing cross-auth matrix... Please wait.", showSpinner = true)
        Toast.makeText(this, "Starting Cross-Authentication Engine...", Toast.LENGTH_LONG).show()
        btnRunCrossAuth.isEnabled = false
        btnRunCrossAuth.text = "Computing Matrix... (Check Logcat)"
        
        Thread {
            try {
                val summary = CrossAuthEngine.computeSimilarityMatrix(this)
                runOnUiThread {
                    showSettingsStatus(
                        "Matrix generated for ${summary.tagCount} tags",
                        showSpinner = false,
                        autoHideMs = 1800L
                    )
                    Toast.makeText(
                        this,
                        "Matrix generated for ${summary.tagCount} tags (${summary.comparisonCount} comparisons).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showSettingsStatus("Cross-auth failed", showSpinner = false, autoHideMs = 1800L)
                    Toast.makeText(this, "Cross-auth failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    btnRunCrossAuth.isEnabled = true
                    btnRunCrossAuth.text = "Compute 30x30 Cross-Authentication"
                }
            }
        }.start()
    }
}
