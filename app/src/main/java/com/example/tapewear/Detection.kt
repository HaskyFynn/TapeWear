package com.example.tapewear

import android.graphics.RectF

/** Generic detection for easy YOLO swap-in later. */
data class Detection(
    val box: RectF,         // [0..W],[0..H] pixels in the *bitmap* coordinate space
    val score: Float = 1f,  // confidence
    val label: Int = 0      // class id (0 = tag)
)
