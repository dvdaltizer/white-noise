package com.slowpixels.whitenoise.play

import com.slowpixels.whitenoise.engine.Bands
import kotlin.math.pow

/** A shape for the ten sliders, from Sounds. */
class Sound(val id: String, val name: String, val levels: List<Float>)

/**
 * The ready-made shapes. Level sliders are pink noise; the others tilt or bend it. Written in dB below the top
 * (a slider's level is 10^(dB/40), as [Bands.amplitude] squares it).
 */
object Sounds {
    private fun db(vararg d: Number) = d.map { 10.0.pow(it.toDouble() / 40).toFloat().coerceIn(0f, 1f) }
    private fun tilt(top: Double, perOctave: Double) = db(*Array(Bands.COUNT) { top + perOctave * it })

    /** Between pink and brown: full, soft, nothing hissy. The first sound a new listener hears. */
    val soft = Sound("soft", "Soft", tilt(-6.0, -1.8))

    val all = listOf(
        soft,
        // The same energy in every octave: the even one.
        Sound("pink", "Pink", tilt(-8.0, 0.0)),
        // Softer still at the top (Brownian, red): a low roar, like a waterfall heard from inside.
        Sound("brown", "Brown", tilt(-4.0, -3.0)),
        // Even to the ear: lifted where hearing is weak (the very bottom and top).
        Sound("grey", "Grey", db(-2, -6, -9, -11, -12, -12, -13, -14, -12, -9)),
        // The same energy at every frequency: bright, the classic hiss.
        Sound("white", "White", tilt(-31.0, 3.0)),
        // Rumble with little above it, like a plane's cabin.
        Sound("deep", "Deep", db(-3, -2, -3, -6, -11, -16, -21, -26, -30, -34)),
        // A box fan's hum and whoosh.
        Sound("fan", "Fan", db(-14, -9, -5, -4, -6, -9, -13, -17, -21, -26)),
        // Light and open, like rain far away.
        Sound("airy", "Airy", db(-22, -18, -15, -13, -11, -9, -8, -8, -10, -14)),
    )

    fun byId(id: String): Sound? = all.firstOrNull { it.id == id }
}
