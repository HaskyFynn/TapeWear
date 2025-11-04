package com.example.tapewear

object Quality {

    data class Gates(
        val lumaLo: Double,
        val lumaHi: Double,
        val blurMin: Double,
        val motionMax: Double
    )

    // Defaults; tweak per device
    val DAY = Gates(lumaLo = 60.0,  lumaHi = 200.0, blurMin = 20.0, motionMax = 12.0)
    val NIGHT = Gates(lumaLo = 80.0, lumaHi = 220.0, blurMin = 20.0, motionMax = 12.0)

    data class Verdict(val pass: Boolean, val hint: String)

    fun assess(luma: Double, blur: Double, motion: Double, night: Boolean): Verdict {
        val g = if (night) NIGHT else DAY
        return when {
            luma < g.lumaLo   -> Verdict(false, "Too dark")
            luma > g.lumaHi   -> Verdict(false, "Too bright")
            motion > g.motionMax -> Verdict(false, "Hold steady…")
            blur < g.blurMin  -> Verdict(false, "Blurred")
            else              -> Verdict(true,  "Good")
        }
    }
}
