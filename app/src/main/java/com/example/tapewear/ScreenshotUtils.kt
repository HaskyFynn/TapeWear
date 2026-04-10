package com.example.tapewear

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object ScreenshotUtils {
    private const val TAG = "TapeWear_Screenshot"

    /**
     * Captures a screenshot of the activity window (including TextureView camera feed)
     * and saves it asynchronously to Documents/TapeWear_Metrics/screenshots/.
     * Wrapped in blanket try-catch to guarantee it never crashes the app.
     */
    fun takeScreenshot(activity: Activity, filename: String) {
        try {
            val window = activity.window ?: return
            val view = window.decorView

            // PixelCopy needs to be executed because standard View.draw() renders TextureViews as black.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val handler = Handler(Looper.getMainLooper())
                
                PixelCopy.request(window, bitmap, { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        saveBitmapAsync(activity, bitmap, filename)
                    } else {
                        Log.e(TAG, "PixelCopy failed with result: $copyResult")
                        bitmap.recycle()
                    }
                }, handler)
            } else {
                // Fallback for extremely old Android versions (< O)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                view.draw(canvas)
                saveBitmapAsync(activity, bitmap, filename)
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM while trying to capture screenshot. Skipping.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Exception while capturing screenshot: ${e.message}", e)
        }
    }

    private fun saveBitmapAsync(activity: Activity, bitmap: Bitmap, filename: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                val metricsDir = File(dir, "TapeWear_Metrics")
                val screenshotDir = File(metricsDir, "screenshots")
                
                if (!screenshotDir.exists()) {
                    screenshotDir.mkdirs()
                }

                val targetFile = File(screenshotDir, filename)
                
                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                Log.d(TAG, "Screenshot saved to ${targetFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
            } finally {
                // Free memory immediately
                bitmap.recycle()
            }
        }
    }
}
