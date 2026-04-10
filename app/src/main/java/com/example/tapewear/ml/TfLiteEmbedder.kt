package com.example.tapewear.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Modern Deep Learning Siamese Embedder.
 * Expects a 112x112 RGB image and outputs a highly discriminative 128D L2-Normalized float array.
 */
class TfLiteEmbedder(
    context: Context,
    assetName: String = "tapewear_embedder.tflite",
    numThreads: Int = 2
) : ModelManager.Embedder {

    private var interpreter: Interpreter? = null
    private val inSize = 112
    private val outSize = 128

    companion object {
        private const val TAG = "TapeWear_MLEmbedder"

        private fun mapAsset(context: Context, assetName: String): ByteBuffer {
            val afd = context.assets.openFd(assetName)
            val fis = FileInputStream(afd.fileDescriptor)
            val channel = fis.channel
            val mapped = channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.length
            )
            fis.close()
            afd.close()
            return mapped
        }
    }

    init {
        val t0 = SystemClock.elapsedRealtime()
        val opts = Interpreter.Options().apply {
            setNumThreads(numThreads)
            setUseNNAPI(false)
        }

        try {
            interpreter = Interpreter(mapAsset(context, assetName), opts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetName. AI Toggle will not work! Error: ${e.message}")
        }

        val local = interpreter
        if (local != null) {
            // Verify Shapes just to be safe
            val inShape = local.getInputTensor(0).shape()
            val outShape = local.getOutputTensor(0).shape()

            Log.i(TAG, "Siamese Embedder Ready! Input Shape: ${inShape.contentToString()}, Output Shape: ${outShape.contentToString()} in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    override fun embed(src: Bitmap, roi: Rect): FloatArray {
        val local = interpreter ?: return FloatArray(outSize)
        // 1. Crop to the YOLO Bounding Box
        val left = roi.left.coerceIn(0, src.width - 2)
        val top = roi.top.coerceIn(0, src.height - 2)
        val right = roi.right.coerceIn(left + 1, src.width)
        val bottom = roi.bottom.coerceIn(top + 1, src.height)
        val w = right - left
        val h = bottom - top
        
        val crop = Bitmap.createBitmap(src, left, top, w, h)
        
        // 2. Resize to 112x112 (Siamese Input Size)
        val resized = crop.scale(inSize, inSize, filter = true)
        crop.recycle()

        // 3. Prepare FloatBuffer [1, 112, 112, 3]
        val input = ByteBuffer
            .allocateDirect(4 * inSize * inSize * 3)
            .order(ByteOrder.nativeOrder())

        val row = IntArray(inSize)
        for (y in 0 until inSize) {
            resized.getPixels(row, 0, inSize, 0, y, inSize, 1)
            for (x in 0 until inSize) {
                val p = row[x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                
                // MobileNetV3 Keras natively expects [0, 255] float inputs!
                input.putFloat(r.toFloat())
                input.putFloat(g.toFloat())
                input.putFloat(b.toFloat())
            }
        }
        input.rewind()
        resized.recycle()

        // 4. Run Inference
        val output = Array(1) { FloatArray(outSize) }
        
        try {
            local.run(input, output)
        } catch(e: Exception) {
            Log.e(TAG, "Inference failed or model absent. Returning empty array.")
            return FloatArray(outSize)
        }

        // Output is already L2-Normalized by the Keras Lambda layer!
        return output[0]
    }

    override fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch(e: Exception) {}
    }
}
