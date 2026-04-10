package com.example.tapewear.ml

import android.content.Context
import android.util.Log
import com.example.tapewear.config.AuthConfig
import com.example.tapewear.data.ExperimentStore
import com.example.tapewear.util.MathUtils
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Offline cross-authentication engine.
 * Loads all saved enrollment embeddings and computes an NxN similarity matrix.
 */
object CrossAuthEngine {

    private const val TAG = "TapeWear_CrossAuth"

    data class CrossAuthSummary(
        val tagCount: Int,
        val comparisonCount: Int,
        val outputFile: File
    )

    fun computeSimilarityMatrix(context: Context): CrossAuthSummary {
        val embeddings = ExperimentStore.loadAllEmbeddingsForCrossAuth(context)
        if (embeddings.size < 2) {
            throw IllegalStateException("Need at least 2 enrolled experiment tags to compute cross-authentication.")
        }

        val tags = embeddings.keys.toList().sorted()
        val dimensions = embeddings.values.map { it.size }.distinct()
        if (dimensions.size != 1) {
            throw IllegalStateException("Saved experiment embeddings have inconsistent dimensions.")
        }

        Log.i(TAG, "Computing ${tags.size}x${tags.size} cross-authentication matrix...")

        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "TapeWear_Metrics")
        dir.mkdirs()
        val outFile = File(dir, "cross_auth_matrix.csv")

        try {
            FileWriter(outFile, false).use { writer ->
                writer.write("tag_a,tag_b,similarity,is_match,is_genuine\n")

                var falseAccepts = 0
                var impostorTotal = 0

                for (tagA in tags) {
                    val embA = embeddings[tagA] ?: continue
                    for (tagB in tags) {
                        val embB = embeddings[tagB] ?: continue
                        val sim = MathUtils.cosine(embA, embB)
                        val isMatch = sim >= AuthConfig.MATCH_THRESHOLD
                        val isGenuine = tagA == tagB

                        writer.write(
                            String.format(Locale.US, "%s,%s,%.6f,%b,%b\n", tagA, tagB, sim, isMatch, isGenuine)
                        )

                        if (tagA != tagB) {
                            impostorTotal++
                            if (isMatch) falseAccepts++
                        }
                    }
                }

                val far = if (impostorTotal > 0) falseAccepts.toFloat() / impostorTotal else 0f
                Log.i(TAG, "Matrix saved to ${outFile.absolutePath}")
                Log.i(TAG, "Approx FAR = $falseAccepts / $impostorTotal = ${"%.4f".format(Locale.US, far)}")
                Log.i(TAG, "Total comparisons: ${tags.size * tags.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write cross-auth matrix: ${e.message}", e)
            throw e
        }

        return CrossAuthSummary(
            tagCount = tags.size,
            comparisonCount = tags.size * tags.size,
            outputFile = outFile
        )
    }
}
