package com.example.tapewear.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.tapewear.config.AuthConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages enrollment persistence: save/load/check per-slot enrollment vectors.
 * Includes an in-memory cache that is invalidated on save and slot change.
 */
object EnrollmentStore {

    private const val TAG = "TapeWear_Model"

    // File layout
    private const val MODELS_DIR = "models_v4"
    private const val LEGACY_ENROLL_FILE = "enroll.json"
    private const val KEY_VEC = "vec"
    private const val KEY_DIM = "dim"
    private const val KEY_COUNT = "count"

    const val PIPELINE_CV = "cv"
    const val PIPELINE_ML = "ml"

    // ---- Active slot ----

    @Volatile
    private var _activeSlot: Int = 1

    var activeSlot: Int
        get() = _activeSlot
        set(value) {
            _activeSlot = value.coerceIn(1, AuthConfig.MAX_SLOTS)
            cachedEnroll = null   // invalidate cache on slot change
        }

    // ---- Enrollment data class ----

    data class Enroll(val mean: FloatArray, val count: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Enroll
            return count == other.count && mean.contentEquals(other.mean)
        }
        override fun hashCode(): Int = 31 * count + mean.contentHashCode()
    }

    data class StoredEnrollment(
        val slot: Int,
        val pipelineMode: String,
        val enroll: Enroll
    )

    // ---- Cache ----

    @Volatile
    private var cachedEnroll: Triple<Int, Boolean, Enroll>? = null

    // ---- File helpers ----

    private fun modelsDir(ctx: Context) =
        File(ctx.filesDir, MODELS_DIR).apply { mkdirs() }

    fun pipelineMode(useMlEmbedder: Boolean = AuthConfig.USE_ML_EMBEDDER): String =
        if (useMlEmbedder) PIPELINE_ML else PIPELINE_CV

    fun useMlForPipeline(pipelineMode: String): Boolean =
        pipelineMode.equals(PIPELINE_ML, ignoreCase = true)

    private fun fileForSlot(
        ctx: Context,
        slot: Int,
        useMlEmbedder: Boolean = AuthConfig.USE_ML_EMBEDDER
    ): File {
        val suffix = if (useMlEmbedder) "_ml" else "_cv"
        return File(modelsDir(ctx), "pattern_%02d%s.json".format(slot, suffix))
    }

    // ---- Public API ----

    fun save(
        ctx: Context,
        mean: FloatArray,
        count: Int,
        slot: Int,
        useMlEmbedder: Boolean = AuthConfig.USE_ML_EMBEDDER
    ) {
        val bb = ByteBuffer.allocate(mean.size * 4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            asFloatBuffer().put(mean)
        }
        val vecStr = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        val js = JSONObject()
            .put(KEY_DIM, mean.size)
            .put(KEY_COUNT, count)
            .put(KEY_VEC, vecStr)
        fileForSlot(ctx, slot, useMlEmbedder).writeText(js.toString())
        cachedEnroll = Triple(slot, useMlEmbedder, Enroll(mean.copyOf(), count))
        Log.i(TAG, "Saved enroll slot=$slot mode=${pipelineMode(useMlEmbedder)} dim=${mean.size} count=$count")
    }

    fun load(
        ctx: Context,
        slot: Int = activeSlot,
        useMlEmbedder: Boolean = AuthConfig.USE_ML_EMBEDDER
    ): Enroll? {
        val isML = useMlEmbedder
        cachedEnroll?.let { (s, m, e) -> if (s == slot && m == isML) return e }

        val f = when {
            fileForSlot(ctx, slot, useMlEmbedder).exists() -> fileForSlot(ctx, slot, useMlEmbedder)
            slot == 1 && !useMlEmbedder && File(ctx.filesDir, LEGACY_ENROLL_FILE).exists() ->
                File(ctx.filesDir, LEGACY_ENROLL_FILE)
            else -> return null
        }
        return try {
            val js = JSONObject(f.readText())
            val dim = js.getInt(KEY_DIM)
            val count = js.getInt(KEY_COUNT)
            val vecStr = js.getString(KEY_VEC)
            val bytes = Base64.decode(vecStr, Base64.NO_WRAP)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val arr = FloatArray(dim)
            fb.get(arr)
            Log.i(TAG, "Loaded enroll slot=$slot mode=${pipelineMode(useMlEmbedder)} dim=$dim count=$count")
            Enroll(arr, count).also { cachedEnroll = Triple(slot, useMlEmbedder, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load enroll slot=$slot: ${e.message}", e)
            null
        }
    }

    fun hasModel(
        ctx: Context,
        slot: Int = activeSlot,
        useMlEmbedder: Boolean = AuthConfig.USE_ML_EMBEDDER
    ): Boolean {
        val f = fileForSlot(ctx, slot, useMlEmbedder)
        if (f.exists() && f.length() > 0) return true
        if (slot == 1 && !useMlEmbedder) {
            val legacy = File(ctx.filesDir, LEGACY_ENROLL_FILE)
            if (legacy.exists() && legacy.length() > 0) return true
        }
        return false
    }

    fun listStoredEnrollments(ctx: Context): List<StoredEnrollment> {
        val stored = mutableListOf<StoredEnrollment>()
        for (slot in 1..AuthConfig.MAX_SLOTS) {
            for (useMl in listOf(false, true)) {
                if (!hasModel(ctx, slot, useMl)) continue
                val enroll = load(ctx, slot, useMl) ?: continue
                stored += StoredEnrollment(
                    slot = slot,
                    pipelineMode = pipelineMode(useMl),
                    enroll = enroll
                )
            }
        }
        return stored
    }
}
