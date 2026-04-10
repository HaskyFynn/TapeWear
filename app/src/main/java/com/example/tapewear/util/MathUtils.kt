package com.example.tapewear.util

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure math utilities used across the auth pipeline.
 * No Android dependencies — easily unit-testable.
 */
object MathUtils {

    /**
     * Cosine similarity between two float vectors.
     * Returns a value in [-1, 1]; 1 = identical direction.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        var num = 0.0
        var da = 0.0
        var db = 0.0
        var i = 0
        while (i < n) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            num += x * y
            da += x * x
            db += y * y
            i++
        }
        val denom = (sqrt(da) * sqrt(db)).let { if (it == 0.0) 1.0 else it }
        return (num / denom).toFloat()
    }

    /**
     * In-place L2 normalization of a float vector.
     * Returns the same array for convenience.
     */
    fun l2norm(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = if (s <= 0.0) 1.0 else sqrt(s)
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }
}
