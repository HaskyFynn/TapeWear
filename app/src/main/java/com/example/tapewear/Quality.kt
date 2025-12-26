package com.example.tapewear

object Quality {

    data class Gates(
        val lumaLo: Double,   // minimum average brightness (0..255)
        val lumaHi: Double,   // maximum average brightness (0..255)
        val blurMin: Double,  // minimum edge strength (higher = sharper)
        val motionMax: Double // maximum allowed inter frame change
    )

    // These are heuristic defaults. Tune per-device if needed.
    // Day mode: allow slightly dimmer images but not washed out.
    val DAY = Gates(
        lumaLo = 60.0,
        lumaHi = 200.0,
        blurMin = 20.0,
        motionMax = 12.0
    )

    // Night mode: require a bit more brightness, same sharpness and motion limits.
    val NIGHT = Gates(
        lumaLo = 80.0,
        lumaHi = 220.0,
        blurMin = 20.0,
        motionMax = 12.0
    )

    data class Verdict(
        val pass: Boolean,
        val hint: String
    )

    /**
     * Decide whether this frame is good enough to keep for registration / auth.
     *
     *  - luma:   average brightness (0..255, from meanLuma)
     *  - blur:   sharpness metric (higher = more edges, from blurMetric)
     *  - motion: mean absolute difference vs previous frame (from meanAbsDiff)
     *  - night:  whether we use NIGHT gates or DAY gates
     */
    fun assess(
        luma: Double,
        blur: Double,
        motion: Double,
        night: Boolean
    ): Verdict {
        val g = if (night) NIGHT else DAY

        return when {
            luma < g.lumaLo ->
                Verdict(false, "Too dark")

            luma > g.lumaHi ->
                Verdict(false, "Too bright")

            motion > g.motionMax ->
                Verdict(false, "Hold steady...")

            blur < g.blurMin ->
                Verdict(false, "Blurred")

            else ->
                Verdict(true, "Good Lighting")
        }
    }
}