package com.example.tapewear.data

import android.content.Context
import android.util.Log
import com.example.tapewear.MetricsLogger
import com.example.tapewear.config.AuthConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * Manages experiment state, study metadata, and coverage tracking.
 */
object ExperimentStore {
    private const val TAG = "TapeWear_Experiment"

    private const val SESSION_PREFS = "experiment_session"
    private const val KEY_CURRENT_TAG = "current_tag"
    private const val KEY_TRIAL_COUNT = "trial_count"
    private const val KEY_ATTEMPT = "trial_attempt"
    private const val KEY_PARTICIPANT_ID = "participant_id"
    private const val KEY_OPERATOR_ID = "operator_id"
    private const val KEY_STUDY_BLOCK = "study_block"
    private const val KEY_SESSION_ID = "study_session_id"
    private const val KEY_SESSION_STARTED_AT = "study_session_started_at"

    private val sessionFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    data class StudyMetadata(
        val participantId: String,
        val operatorId: String,
        val studyBlock: String,
        val sessionId: String,
        val sessionStartedAt: String
    ) {
        fun isComplete(): Boolean =
            participantId.isNotBlank() &&
                operatorId.isNotBlank() &&
                studyBlock.isNotBlank() &&
                sessionId.isNotBlank() &&
                sessionStartedAt.isNotBlank()
    }

    data class ExperimentCondition(
        val illumination: String,
        val distance: String
    ) {
        val key: String get() = "$illumination|$distance"
        fun label(): String = "${illumination.uppercase(Locale.US)} / ${distance.uppercase(Locale.US)}"
    }

    data class PendingAuthCell(
        val tagName: String,
        val condition: ExperimentCondition,
        val trialNumber: Int
    ) {
        fun label(): String = "$tagName | ${condition.label()} | Trial $trialNumber"
    }

    data class StudyCoverageReport(
        val metadata: StudyMetadata,
        val sessionTags: List<String>,
        val completedRegistrations: Set<String>,
        val activeIllumination: String,
        val completedAuthCells: Int,
        val requiredAuthCells: Int,
        val completedActiveAuthCells: Int,
        val requiredActiveAuthCells: Int,
        val missingRegistrations: List<String>,
        val missingAuthCells: List<PendingAuthCell>,
        val missingActiveAuthCells: List<PendingAuthCell>,
        val issues: List<String>
    ) {
        val activeBlockComplete: Boolean =
            metadata.isComplete() &&
                issues.isEmpty() &&
                sessionTags.isNotEmpty() &&
                missingRegistrations.isEmpty() &&
                missingActiveAuthCells.isEmpty() &&
                requiredActiveAuthCells > 0

        val overallComplete: Boolean =
            metadata.isComplete() &&
                issues.isEmpty() &&
                sessionTags.isNotEmpty() &&
                missingRegistrations.isEmpty() &&
                missingAuthCells.isEmpty()

        val readyForExport: Boolean = activeBlockComplete

        fun nextAction(): String? {
            if (issues.isNotEmpty()) return issues.first()
            if (sessionTags.isEmpty()) return "Complete the first registration in this study session."
            if (missingRegistrations.isNotEmpty()) {
                return "Retake registration: ${missingRegistrations.first()}"
            }
            return missingActiveAuthCells.firstOrNull()?.let {
                "Next ${activeIllumination.uppercase(Locale.US)} auth: ${it.label()}"
            }
                ?: if (missingAuthCells.isNotEmpty()) {
                    val nextIllumination =
                        missingAuthCells.first().condition.illumination.uppercase(Locale.US)
                    "${activeIllumination.uppercase(Locale.US)} block complete. $nextIllumination remains pending."
                } else {
                    "All study blocks complete. Export is ready."
                }
        }

        fun summaryText(): String {
            val lines = mutableListOf<String>()
            if (metadata.isComplete()) {
                lines += "Participant: ${metadata.participantId}"
                lines += "Operator: ${metadata.operatorId}"
                lines += "Block: ${metadata.studyBlock}"
                lines += "Session: ${metadata.sessionId}"
                lines += "Started: ${metadata.sessionStartedAt}"
            } else {
                lines += "Study metadata incomplete."
            }

            if (sessionTags.isEmpty()) {
                lines += "Session tags: none recorded yet."
            } else {
                lines += "Session tags (${sessionTags.size}): ${sessionTags.joinToString(", ")}"
            }

            lines += "Completed registrations: ${completedRegistrations.size}/${sessionTags.size}"
            lines +=
                "Active ${activeIllumination.uppercase(Locale.US)} auth cells: $completedActiveAuthCells/$requiredActiveAuthCells"
            lines += "Overall auth cells: $completedAuthCells/$requiredAuthCells"
            if (activeBlockComplete && !overallComplete) {
                lines +=
                    "${activeIllumination.uppercase(Locale.US)} block complete. Other illumination remains pending."
            }
            if (overallComplete) {
                lines += "Overall study complete."
            }

            nextAction()?.let { lines += it }
            if (issues.size > 1) {
                lines += issues.drop(1)
            }
            return lines.joinToString("\n")
        }
    }

    private data class CsvTable(
        val header: List<String>,
        val rows: List<Map<String, String>>
    )

    private val allConditions = listOf(
        ExperimentCondition("bright", "near"),
        ExperimentCondition("bright", "far"),
        ExperimentCondition("dim", "near"),
        ExperimentCondition("dim", "far")
    )

    // Map of Tag Name -> Slot Number
    private val tagToSlotMap = mutableMapOf<String, Int>()
    private var tagsLoaded = false

    fun normalizeTag(raw: String): String {
        return raw
            .trim()
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(",", "_")
            .replace(Regex("\\s+"), " ")
    }

    fun normalizeStudyField(raw: String): String {
        return raw
            .trim()
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")
    }

    private fun fileSafeTag(raw: String): String {
        return normalizeTag(raw)
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifEmpty { "unknown" }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    private fun nowIso(): String = sessionFormatter.format(Date())

    private fun createSessionId(participantId: String, studyBlock: String): String {
        val compactTs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val participant = normalizeStudyField(participantId).replace(" ", "_").ifEmpty { "participant" }
        val block = normalizeStudyField(studyBlock).replace(" ", "_").ifEmpty { "block" }
        return "${participant}_${block}_$compactTs"
    }

    private fun conditionKey(illumination: String, distance: String): String =
        "${illumination.lowercase(Locale.US)}|${distance.lowercase(Locale.US)}"

    private fun normalizeIllumination(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.US)) {
            "dim" -> "dim"
            else -> "bright"
        }

    private fun normalizeDistance(raw: String?): String =
        when (raw?.trim()?.lowercase(Locale.US)) {
            "far" -> "far"
            else -> "near"
        }

    private fun conditionsForIllumination(
        participantId: String,
        illumination: String
    ): List<ExperimentCondition> =
        orderedConditions(participantId).filter { it.illumination == illumination }

    private fun metricsDir(context: Context): File {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
        return File(dir, "TapeWear_Metrics").apply { mkdirs() }
    }

    private fun missingMetadataFields(metadata: StudyMetadata): List<String> {
        val missing = mutableListOf<String>()
        if (metadata.operatorId.isBlank()) missing += "Operator ID"
        if (metadata.studyBlock.isBlank()) missing += "Study Block"
        return missing
    }

    private fun orderedConditions(participantId: String): List<ExperimentCondition> {
        if (participantId.isBlank()) return allConditions
        val ordered = allConditions.toMutableList()
        Collections.shuffle(ordered, Random(participantId.hashCode().toLong()))
        return ordered
    }

    private fun readCsv(file: File): CsvTable {
        if (!file.exists()) return CsvTable(emptyList(), emptyList())
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return CsvTable(emptyList(), emptyList())

        val header = lines.first().split(",").map { it.trim() }
        val rows = lines.drop(1).map { line ->
            val values = line.split(",")
            header.indices.associate { index ->
                header[index] to values.getOrElse(index) { "" }.trim()
            }
        }
        return CsvTable(header, rows)
    }

    private fun readMergedCsv(vararg files: File): CsvTable {
        val tables = files.map(::readCsv)
        val header = tables.firstOrNull { it.header.isNotEmpty() }?.header ?: emptyList()
        val rows = tables.flatMap { it.rows }
        return CsvTable(header, rows)
    }

    private fun filterSessionRows(rows: List<Map<String, String>>, metadata: StudyMetadata): List<Map<String, String>> {
        if (metadata.sessionId.isBlank()) return emptyList()
        return rows.filter { row ->
            row["session_id"] == metadata.sessionId &&
                (row["participant_id"].isNullOrBlank() || row["participant_id"] == metadata.participantId) &&
                (row["study_block"].isNullOrBlank() || row["study_block"] == metadata.studyBlock)
        }
    }

    fun getStudyMetadata(context: Context): StudyMetadata {
        val prefs = prefs(context)
        return StudyMetadata(
            participantId = normalizeStudyField(prefs.getString(KEY_PARTICIPANT_ID, "") ?: ""),
            operatorId = normalizeStudyField(prefs.getString(KEY_OPERATOR_ID, "") ?: ""),
            studyBlock = normalizeStudyField(prefs.getString(KEY_STUDY_BLOCK, "") ?: ""),
            sessionId = normalizeStudyField(prefs.getString(KEY_SESSION_ID, "") ?: ""),
            sessionStartedAt = normalizeStudyField(prefs.getString(KEY_SESSION_STARTED_AT, "") ?: "")
        )
    }

    fun saveStudyMetadata(
        context: Context,
        operatorId: String,
        studyBlock: String
    ): StudyMetadata {
        val previous = getStudyMetadata(context)
        val normalizedParticipant = previous.participantId
        val normalizedOperator = normalizeStudyField(operatorId)
        val normalizedBlock = normalizeStudyField(studyBlock)

        val complete = normalizedParticipant.isNotBlank() &&
            normalizedOperator.isNotBlank() &&
            normalizedBlock.isNotBlank()

        val participantChanged =
            previous.participantId != normalizedParticipant || previous.studyBlock != normalizedBlock

        val sessionId: String
        val sessionStartedAt: String
        if (!complete) {
            sessionId = ""
            sessionStartedAt = ""
        } else if (participantChanged || previous.sessionId.isBlank() || previous.sessionStartedAt.isBlank()) {
            sessionId = createSessionId(normalizedParticipant, normalizedBlock)
            sessionStartedAt = nowIso()
        } else {
            sessionId = previous.sessionId
            sessionStartedAt = previous.sessionStartedAt
        }

        prefs(context).edit()
            .putString(KEY_PARTICIPANT_ID, normalizedParticipant)
            .putString(KEY_OPERATOR_ID, normalizedOperator)
            .putString(KEY_STUDY_BLOCK, normalizedBlock)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_SESSION_STARTED_AT, sessionStartedAt)
            .apply()

        return getStudyMetadata(context)
    }

    fun startNewStudySession(context: Context): StudyMetadata {
        val metadata = getStudyMetadata(context)
        require(metadata.operatorId.isNotBlank()) { "Set Operator ID before starting a new session." }
        require(metadata.studyBlock.isNotBlank()) { "Set Study Block before starting a new session." }

        val newParticipant = "P_" + SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        val sessionId = createSessionId(newParticipant, metadata.studyBlock)
        val sessionStartedAt = nowIso()
        prefs(context).edit()
            .putString(KEY_PARTICIPANT_ID, newParticipant)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_SESSION_STARTED_AT, sessionStartedAt)
            .apply()
        resetSession(context)
        return getStudyMetadata(context)
    }

    fun clearStudyMetadata(context: Context) {
        prefs(context).edit()
            .remove(KEY_PARTICIPANT_ID)
            .remove(KEY_OPERATOR_ID)
            .remove(KEY_STUDY_BLOCK)
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_STARTED_AT)
            .apply()
    }

    fun studySetupError(context: Context): String? {
        val metadata = getStudyMetadata(context)
        val missing = missingMetadataFields(metadata)
        return when {
            missing.isNotEmpty() -> "Set ${missing.joinToString(", ")} in Settings before running the study."
            metadata.sessionId.isBlank() -> "Save Settings or start a new study session before running the study."
            else -> null
        }
    }

    fun buildCoverageReport(context: Context): StudyCoverageReport {
        val metadata = getStudyMetadata(context)
        val issues = mutableListOf<String>()
        studySetupError(context)?.let { issues += it }
        val activeIllumination = normalizeIllumination(AuthConfig.EXPERIMENT_ILLUMINATION)

        val dir = metricsDir(context)
        val regTable = readMergedCsv(
            File(dir, MetricsLogger.LEGACY_EXP_REG_FILE_V2),
            File(dir, MetricsLogger.LEGACY_EXP_REG_FILE_V3),
            File(dir, MetricsLogger.EXP_REG_FILE)
        )
        val authTable = readMergedCsv(
            File(dir, MetricsLogger.LEGACY_EXP_AUTH_FILE_V2),
            File(dir, MetricsLogger.LEGACY_EXP_AUTH_FILE_V3),
            File(dir, MetricsLogger.EXP_AUTH_FILE)
        )

        val regRows = filterSessionRows(regTable.rows, metadata)
        val authRows = filterSessionRows(authTable.rows, metadata)

        val sessionTags = (regRows.mapNotNull { it["pattern_tag_name"]?.takeIf(String::isNotBlank) } +
            authRows.mapNotNull { it["pattern_tag_name"]?.takeIf(String::isNotBlank) })
            .map(::normalizeTag)
            .distinct()
            .sorted()

        val completedRegistrations = regRows
            .filter { it["trial_status"] == "completed" }
            .mapNotNull { it["pattern_tag_name"]?.takeIf(String::isNotBlank) }
            .map(::normalizeTag)
            .toSet()

        val missingRegistrations = sessionTags.filter { it !in completedRegistrations }

        val completedAuthCells = mutableSetOf<String>()
        val completedActiveAuthCells = mutableSetOf<String>()
        val validConditionKeys = allConditions.map { it.key }.toSet()
        for (row in authRows) {
            if (row["trial_status"] != "completed") continue
            val tag = normalizeTag(row["pattern_tag_name"].orEmpty())
            val illumination = normalizeIllumination(row["illumination"])
            val distance = normalizeDistance(row["distance"])
            val trial = row["trial_number"]?.toIntOrNull() ?: continue
            if (tag.isBlank()) continue
            if (trial !in 1..AuthConfig.EXPERIMENT_AUTH_TRIALS) continue
            val key = conditionKey(illumination, distance)
            if (key !in validConditionKeys) continue
            val cellKey = "$tag|$key|$trial"
            completedAuthCells += cellKey
            if (illumination == activeIllumination) {
                completedActiveAuthCells += cellKey
            }
        }

        val pending = mutableListOf<PendingAuthCell>()
        val orderedConditions = orderedConditions(metadata.participantId)
        for (tag in sessionTags) {
            if (tag !in completedRegistrations) continue
            for (condition in orderedConditions) {
                for (trial in 1..AuthConfig.EXPERIMENT_AUTH_TRIALS) {
                    val cellKey = "$tag|${condition.key}|$trial"
                    if (cellKey !in completedAuthCells) {
                        pending += PendingAuthCell(tag, condition, trial)
                    }
                }
            }
        }
        val pendingActive = pending.filter { it.condition.illumination == activeIllumination }

        if (metadata.isComplete() && sessionTags.isEmpty()) {
            issues += "No registration or authentication rows recorded for the current session."
        }
        if (missingRegistrations.isNotEmpty()) {
            issues += "Registration incomplete for ${missingRegistrations.joinToString(", ")}."
        }

        return StudyCoverageReport(
            metadata = metadata,
            sessionTags = sessionTags,
            completedRegistrations = completedRegistrations,
            activeIllumination = activeIllumination,
            completedAuthCells = completedAuthCells.size,
            requiredAuthCells = sessionTags.size * allConditions.size * AuthConfig.EXPERIMENT_AUTH_TRIALS,
            completedActiveAuthCells = completedActiveAuthCells.size,
            requiredActiveAuthCells =
                sessionTags.size *
                    conditionsForIllumination(metadata.participantId, activeIllumination).size *
                    AuthConfig.EXPERIMENT_AUTH_TRIALS,
            missingRegistrations = missingRegistrations,
            missingAuthCells = pending,
            missingActiveAuthCells = pendingActive,
            issues = issues.distinct()
        )
    }

    fun nextPendingAuthCell(
        context: Context,
        tagName: String? = null,
        illumination: String? = null,
        distance: String? = null
    ): PendingAuthCell? {
        val normalizedTag = normalizeTag(tagName.orEmpty())
        val report = buildCoverageReport(context)
        val targetIllumination = illumination?.let(::normalizeIllumination)
        val targetDistance =
            distance?.takeIf { it.isNotBlank() }?.let(::normalizeDistance)
        return report.missingAuthCells.firstOrNull { cell ->
            (normalizedTag.isBlank() || cell.tagName == normalizedTag) &&
                (targetIllumination == null || cell.condition.illumination == targetIllumination) &&
                (targetDistance == null || cell.condition.distance == targetDistance)
        }
    }

    fun nextPendingAuthCellAnyIllumination(
        context: Context,
        tagName: String? = null
    ): PendingAuthCell? {
        val normalizedTag = normalizeTag(tagName.orEmpty())
        val report = buildCoverageReport(context)
        return if (normalizedTag.isBlank()) {
            report.missingAuthCells.firstOrNull()
        } else {
            report.missingAuthCells.firstOrNull { it.tagName == normalizedTag }
        }
    }

    fun writeStudySummaryFile(context: Context): File {
        val report = buildCoverageReport(context)
        val file = File(metricsDir(context), MetricsLogger.EXP_SESSION_SUMMARY_FILE)
        file.writeText(report.summaryText() + "\n")
        return file
    }

    // ---- State getters/setters ----

    fun getCurrentTagName(context: Context): String? {
        return prefs(context).getString(KEY_CURRENT_TAG, null)?.let(::normalizeTag)
    }

    fun setCurrentTagName(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        val editor = prefs(context).edit()
        if (normalized.isEmpty()) {
            editor.remove(KEY_CURRENT_TAG)
        } else {
            editor.putString(KEY_CURRENT_TAG, normalized)
        }
        editor.apply()
    }

    // Trial is 1-indexed (1, 2, 3)
    fun getAuthTrialCount(context: Context): Int {
        return prefs(context).getInt(KEY_TRIAL_COUNT, 1)
    }

    fun setAuthTrialCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_TRIAL_COUNT, count).apply()
    }

    fun incrementAuthTrialCount(context: Context) {
        val current = getAuthTrialCount(context)
        setAuthTrialCount(context, current + 1)
        setAuthAttempt(context, 1)
    }

    fun isSessionComplete(context: Context): Boolean {
        return getAuthTrialCount(context) > AuthConfig.EXPERIMENT_AUTH_TRIALS
    }

    // ---- Attempt tracking (per trial) ----

    fun getAuthAttempt(context: Context): Int {
        return prefs(context).getInt(KEY_ATTEMPT, 1)
    }

    fun setAuthAttempt(context: Context, attempt: Int) {
        prefs(context).edit().putInt(KEY_ATTEMPT, attempt).apply()
    }

    fun incrementAuthAttempt(context: Context) {
        setAuthAttempt(context, getAuthAttempt(context) + 1)
    }

    fun resetSession(context: Context) {
        setAuthTrialCount(context, 1)
        setAuthAttempt(context, 1)
        prefs(context).edit().remove(KEY_CURRENT_TAG).apply()
    }

    // ---- Tag Management ----

    private fun getTagsFile(context: Context): File {
        return File(context.filesDir, "experiment_tags.json")
    }

    @Synchronized
    private fun ensureTagsLoaded(context: Context) {
        if (tagsLoaded) return
        val file = getTagsFile(context)
        if (!file.exists()) {
            tagsLoaded = true
            return
        }
        try {
            val json = JSONObject(file.readText())
            val keys = json.keys()
            tagToSlotMap.clear()
            while (keys.hasNext()) {
                val key = normalizeTag(keys.next())
                if (key.isNotEmpty()) {
                    tagToSlotMap[key] = json.getInt(key).coerceIn(1, AuthConfig.MAX_SLOTS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tags: ${e.message}")
        }
        tagsLoaded = true
    }

    @Synchronized
    private fun saveTags(context: Context) {
        val json = JSONObject()
        for ((tag, slot) in tagToSlotMap) {
            json.put(tag, slot)
        }
        getTagsFile(context).writeText(json.toString())
    }

    @Synchronized
    fun getRegisteredTags(context: Context): List<String> {
        ensureTagsLoaded(context)
        return tagToSlotMap.keys.toList()
    }

    @Synchronized
    fun getTagMap(context: Context): Map<String, Int> {
        ensureTagsLoaded(context)
        return tagToSlotMap.toMap()
    }

    @Synchronized
    fun isTagRegistered(context: Context, tag: String): Boolean {
        ensureTagsLoaded(context)
        return tagToSlotMap.containsKey(normalizeTag(tag))
    }

    @Synchronized
    fun getRegisteredSlotForTag(context: Context, tag: String): Int? {
        ensureTagsLoaded(context)
        return tagToSlotMap[normalizeTag(tag)]
    }

    /**
     * Reserve a slot for a tag in memory only. The mapping is NOT persisted to
     * disk until [confirmTagRegistration] is called after a successful enrollment.
     * If enrollment fails, call [releaseUnconfirmedTag] to free the slot.
     */
    @Synchronized
    fun getSlotForTag(context: Context, tag: String): Int {
        ensureTagsLoaded(context)
        val normalized = normalizeTag(tag)
        require(normalized.isNotEmpty()) { "Pattern tag name cannot be empty." }

        val existingSlot = tagToSlotMap[normalized]
        if (existingSlot != null) return existingSlot

        val usedSlots = tagToSlotMap.values.toSet()
        val nextSlot = (1..AuthConfig.MAX_SLOTS).firstOrNull { it !in usedSlots }
            ?: throw IllegalStateException("All ${AuthConfig.MAX_SLOTS} experiment slots are already assigned.")

        tagToSlotMap[normalized] = nextSlot
        // NOTE: intentionally NOT calling saveTags here — slot is reserved in
        // memory only until confirmTagRegistration is called.

        Log.i(TAG, "Reserved tag '$normalized' to slot $nextSlot (not yet persisted)")
        return nextSlot
    }

    /**
     * Persist a previously reserved tag-to-slot mapping to disk.
     * Call this only after enrollment has successfully completed.
     */
    @Synchronized
    fun confirmTagRegistration(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        if (tagToSlotMap.containsKey(normalized)) {
            saveTags(context)
            Log.i(TAG, "Confirmed and persisted tag '$normalized' (slot ${tagToSlotMap[normalized]})")
        } else {
            Log.w(TAG, "confirmTagRegistration called for unknown tag '$normalized'")
        }
    }

    /**
     * Remove a tag-to-slot mapping that was reserved but never confirmed
     * (i.e. enrollment failed). This frees the slot for future use.
     */
    @Synchronized
    fun releaseUnconfirmedTag(context: Context, tag: String) {
        val normalized = normalizeTag(tag)
        if (tagToSlotMap.containsKey(normalized)) {
            val slot = tagToSlotMap.remove(normalized)
            // Do NOT call saveTags — the tag was never persisted, so there is
            // nothing to remove from disk. But call it defensively in case
            // an earlier session persisted a stale entry.
            saveTags(context)
            Log.i(TAG, "Released unconfirmed tag '$normalized' (was slot $slot)")
        }
    }

    @Synchronized
    fun deleteTag(context: Context, tag: String) {
        ensureTagsLoaded(context)
        tagToSlotMap.remove(normalizeTag(tag))
        saveTags(context)
    }

    // ---- Raw Embeddings For Cross-Authentication ----

    private fun getEmbeddingsDir(context: Context): File {
        return File(context.filesDir, "experiment_embeddings").apply { mkdirs() }
    }

    fun saveEnrollmentEmbeddingForCrossAuth(context: Context, tag: String, mean: FloatArray) {
        try {
            val file = File(getEmbeddingsDir(context), "${fileSafeTag(tag)}.bin")
            val bb = ByteBuffer.allocate(mean.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(mean)
            file.writeBytes(bb.array())
            Log.i(TAG, "Saved enrollment embedding for tag '$tag' for cross-auth")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save embedding for cross-auth: ${e.message}")
        }
    }

    fun loadAllEmbeddingsForCrossAuth(context: Context): Map<String, FloatArray> {
        val map = mutableMapOf<String, FloatArray>()
        val dir = getEmbeddingsDir(context)
        if (!dir.exists()) return map

        val files = dir.listFiles { f -> f.extension == "bin" } ?: return map
        for (f in files) {
            try {
                val tag = f.nameWithoutExtension
                val bytes = f.readBytes()
                val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val array = FloatArray(floatBuffer.capacity())
                floatBuffer.get(array)
                map[tag] = array
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read embedding file ${f.name}: ${e.message}")
            }
        }
        return map
    }
}
