package com.example.tapewear.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.tapewear.config.AuthConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Imports and exports enrolled pattern embeddings for experiment transfer.
 *
 * Archive format v1:
 * - ZIP file
 * - manifest.json with metadata and entry paths
 * - embeddings/slot_XX_cv.json or embeddings/slot_XX_ml.json per template
 * - vector payload: Base64-encoded little-endian float32 array
 */
object EmbeddingArchiveStore {
    private const val TAG = "TapeWear_EmbArchive"
    private const val ARCHIVE_KIND = "tapewear.embedding_archive"
    private const val SCHEMA_VERSION = 1
    private const val VEC_ENCODING = "base64_float32_le"
    private const val MANIFEST_FILE = "manifest.json"
    private const val EMBEDDINGS_DIR = "embeddings/"

    data class ExportSummary(
        val file: File,
        val templateCount: Int
    )

    data class ImportSummary(
        val importedCount: Int,
        val skippedCount: Int,
        val details: List<String>
    ) {
        fun userMessage(): String = when {
            importedCount == 0 && skippedCount == 0 -> "No embeddings found in archive."
            skippedCount == 0 -> "Imported $importedCount embedding template(s)."
            else -> "Imported $importedCount template(s); skipped $skippedCount."
        }
    }

    private data class ArchiveEmbedding(
        val slot: Int,
        val pipelineMode: String,
        val patternTag: String,
        val count: Int,
        val vector: FloatArray
    )

    private fun nowUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    private fun archiveFile(context: Context): File = File(
        context.cacheDir,
        "TapeWearEmbeddings_${System.currentTimeMillis()}.zip"
    )

    fun exportArchive(context: Context): ExportSummary {
        val stored = EnrollmentStore.listStoredEnrollments(context)
        if (stored.isEmpty()) {
            throw IllegalStateException("No enrolled embeddings to export.")
        }

        val tagBySlot = ExperimentStore.getTagMap(context)
            .entries
            .groupBy(keySelector = { it.value }, valueTransform = { it.key })
            .mapValues { (_, tags) -> tags.sorted().firstOrNull().orEmpty() }

        val outFile = archiveFile(context)
        val manifestEntries = JSONArray()

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            for (storedEnrollment in stored.sortedWith(compareBy({ it.slot }, { it.pipelineMode }))) {
                val slot = storedEnrollment.slot
                val pipelineMode = storedEnrollment.pipelineMode
                val entryName = EMBEDDINGS_DIR + "slot_%02d_%s.json".format(Locale.US, slot, pipelineMode)
                val tag = tagBySlot[slot].orEmpty()
                val json = embeddingJson(
                    slot = slot,
                    pipelineMode = pipelineMode,
                    patternTag = tag,
                    count = storedEnrollment.enroll.count,
                    vector = storedEnrollment.enroll.mean
                )

                putEntry(zip, entryName, json.toString(2).toByteArray(Charsets.UTF_8))
                manifestEntries.put(
                    JSONObject()
                        .put("path", entryName)
                        .put("slot", slot)
                        .put("pipeline_mode", pipelineMode)
                        .put("pattern_tag", tag)
                        .put("dim", storedEnrollment.enroll.mean.size)
                        .put("count", storedEnrollment.enroll.count)
                )
            }

            val manifest = JSONObject()
                .put("archive_kind", ARCHIVE_KIND)
                .put("schema_version", SCHEMA_VERSION)
                .put("created_at_utc", nowUtc())
                .put("template_count", stored.size)
                .put("max_slots", AuthConfig.MAX_SLOTS)
                .put("vector_encoding", VEC_ENCODING)
                .put("entries", manifestEntries)
            putEntry(zip, MANIFEST_FILE, manifest.toString(2).toByteArray(Charsets.UTF_8))
        }

        Log.i(TAG, "Exported ${stored.size} embeddings to ${outFile.absolutePath}")
        return ExportSummary(outFile, stored.size)
    }

    fun importArchive(context: Context, uri: Uri): ImportSummary {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Could not read selected embedding archive.")
        return importArchiveBytes(context, bytes)
    }

    private fun importArchiveBytes(context: Context, bytes: ByteArray): ImportSummary {
        val parsed = parseArchive(bytes)
        val details = mutableListOf<String>()
        var imported = 0
        var skipped = 0

        for (embedding in parsed) {
            try {
                val useMl = EnrollmentStore.useMlForPipeline(embedding.pipelineMode)
                EnrollmentStore.save(
                    ctx = context,
                    mean = embedding.vector,
                    count = embedding.count,
                    slot = embedding.slot,
                    useMlEmbedder = useMl
                )

                val crossAuthTag = embedding.patternTag.ifBlank {
                    "slot_%02d_%s".format(Locale.US, embedding.slot, embedding.pipelineMode)
                }
                if (embedding.patternTag.isNotBlank()) {
                    ExperimentStore.upsertTagSlot(context, embedding.patternTag, embedding.slot)
                }
                ExperimentStore.saveEnrollmentEmbeddingForCrossAuth(context, crossAuthTag, embedding.vector)
                imported++
                details += "slot=${embedding.slot} mode=${embedding.pipelineMode} tag=${crossAuthTag}"
            } catch (e: Exception) {
                skipped++
                details += "skipped slot=${embedding.slot}: ${e.message}"
                Log.e(TAG, "Failed to import embedding slot=${embedding.slot}: ${e.message}", e)
            }
        }

        return ImportSummary(imported, skipped, details)
    }

    private fun parseArchive(bytes: ByteArray): List<ArchiveEmbedding> {
        return if (looksLikeZip(bytes)) parseZipArchive(bytes) else parseJsonArchive(bytes)
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun parseZipArchive(bytes: ByteArray): List<ArchiveEmbedding> {
        val embeddings = mutableListOf<ArchiveEmbedding>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.endsWith(".json") && name != MANIFEST_FILE) {
                    val jsonText = zip.readBytes().toString(Charsets.UTF_8)
                    parseEmbeddingJson(JSONObject(jsonText))?.let { embeddings += it }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return embeddings
    }

    private fun parseJsonArchive(bytes: ByteArray): List<ArchiveEmbedding> {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        if (json.has("vec")) return listOfNotNull(parseEmbeddingJson(json))

        val array = json.optJSONArray("embeddings") ?: return emptyList()
        val embeddings = mutableListOf<ArchiveEmbedding>()
        for (i in 0 until array.length()) {
            parseEmbeddingJson(array.getJSONObject(i))?.let { embeddings += it }
        }
        return embeddings
    }

    private fun parseEmbeddingJson(json: JSONObject): ArchiveEmbedding? {
        if (!json.has("vec")) return null
        val slot = json.getInt("slot").coerceIn(1, AuthConfig.MAX_SLOTS)
        val pipelineMode = json.optString("pipeline_mode", EnrollmentStore.pipelineMode())
            .lowercase(Locale.US)
            .let { mode ->
                when (mode) {
                    EnrollmentStore.PIPELINE_ML -> EnrollmentStore.PIPELINE_ML
                    else -> EnrollmentStore.PIPELINE_CV
                }
            }
        val dim = json.getInt("dim").coerceAtLeast(1)
        val count = json.optInt("count", 1).coerceAtLeast(1)
        val patternTag = json.optString("pattern_tag", "")
        val encoding = json.optString("vec_encoding", VEC_ENCODING)
        require(encoding == VEC_ENCODING) { "Unsupported vector encoding: $encoding" }
        val vector = decodeVector(json.getString("vec"), dim)
        return ArchiveEmbedding(slot, pipelineMode, patternTag, count, vector)
    }

    private fun embeddingJson(
        slot: Int,
        pipelineMode: String,
        patternTag: String,
        count: Int,
        vector: FloatArray
    ): JSONObject = JSONObject()
        .put("archive_kind", ARCHIVE_KIND)
        .put("schema_version", SCHEMA_VERSION)
        .put("slot", slot)
        .put("pipeline_mode", pipelineMode)
        .put("pattern_tag", patternTag)
        .put("dim", vector.size)
        .put("count", count)
        .put("vec_encoding", VEC_ENCODING)
        .put("vec", encodeVector(vector))

    private fun encodeVector(vector: FloatArray): String {
        val bb = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(vector)
        return Base64.encodeToString(bb.array(), Base64.NO_WRAP)
    }

    private fun decodeVector(encoded: String, dim: Int): FloatArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size == dim * 4) {
            "Vector byte length ${bytes.size} does not match dim=$dim."
        }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val vector = FloatArray(dim)
        fb.get(vector)
        return vector
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
