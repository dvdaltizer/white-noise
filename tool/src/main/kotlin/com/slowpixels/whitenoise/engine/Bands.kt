package com.slowpixels.whitenoise.engine

/**
 * The ten sliders: one octave each, from the deepest rumble to the airy top, an octave apart as in a graphic
 * equaliser. With every slider at the same height the sound is pink noise (the same energy in every octave), which the
 * ear hears as even; white noise and the others are shapes of the sliders (model/Sounds.kt).
 */
object Bands {
    const val COUNT = 10

    /** The middle of each band, Hz: 31 Hz up to 16 kHz. */
    val centers = FloatArray(COUNT) { 31.25f * (1 shl it) }

    val names = listOf("Sub bass", "Low bass", "Bass", "High bass", "Low mids", "Mids", "High mids", "Low treble", "Treble", "High treble")

    /** "63 Hz", "1 kHz". */
    fun hz(i: Int): String {
        val f = centers[i]
        return if (f >= 1000) "${(f / 1000).toInt()} kHz" else "${Math.round(f)} Hz"
    }

    /**
     * A slider's height as a loudness: its square, so the top half of a slider spans about 12 dB and the bottom half
     * fades the band away to nothing, as a volume control should.
     */
    fun amplitude(level: Float): Float {
        val l = level.coerceIn(0f, 1f)
        return l * l
    }
}
