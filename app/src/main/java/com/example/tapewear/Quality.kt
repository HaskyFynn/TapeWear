package com.example.tapewear

object Quality {

    data class Gates(
        var lumaLo: Double,   // minimum average brightness (0..255)
        var lumaHi: Double,   // maximum average brightness (0..255)
        var blurMin: Double,  // minimum edge strength (higher = sharper)
        var motionMax: Double // maximum allowed inter frame change
    )

    // These are heuristic defaults. Tune per-device if needed.
    // Day mode: allow slightly dimmer images but not washed out.
    // Reduced default limits explicitly for real-world movement and blur
    var DAY = Gates(
        lumaLo = 40.0,
        lumaHi = 240.0,
        blurMin = 10.0,
        motionMax = 30.0
    )

    // Night mode: require a bit more brightness, same sharpness and motion limits.
    var NIGHT = Gates(
        lumaLo = 60.0,
        lumaHi = 250.0,
        blurMin = 10.0,
        motionMax = 30.0
    )

    data class Verdict(
        val pass: Boolean,
        val isIdeal: Boolean,
        val hint: String
    )

    /**
     * Decide whether this frame is good enough to keep for registration / auth.
     */
    fun assess(
        luma: Double,
        blur: Double,
        motion: Double,
        night: Boolean
    ): Verdict {
        val g = if (night) NIGHT else DAY

        val hint = when {
            luma < g.lumaLo -> "Too dark"
            luma > g.lumaHi -> "Too bright"
            motion > g.motionMax -> "Hold steady..."
            blur < g.blurMin -> "Blurred"
            else -> "Good Lighting"
        }

        // Exactly match the Developer Setting threshold, no hidden math
        val isIdeal = hint == "Good Lighting"
        val pass = isIdeal 

        return Verdict(pass, isIdeal, hint)
    }
}