package com.slowpixels.whitenoise.engine

import kotlin.math.PI
import kotlin.math.sin

/** Animate's choices: how the sliders move by themselves around where you set them. */
enum class Motion(val label: String, val detail: String) {
    Off("Off", "The sliders stay put"),
    Zen("Zen", "Barely moves"),
    Drift("Drift", "Each band wanders"),
    Waves("Waves", "Rolls from low to high"),
    Breathe("Breathe", "Rises and falls together"),
}

/**
 * Moves the sliders for [Motion]. Every movement is made from fresh random choices (how far, how long, how fast the
 * waves roll, how long each breath is), so the sound keeps changing without ever coming round to the same place: there
 * is nothing to notice repeating at 3 a.m.
 *
 * [offsets] gives each band's movement in slider units, already scaled by how far it may go; [apply] adds them to the
 * levels you set. A slider at the bottom stays silent.
 */
class Animator(private val rng: Rng) {
    private val n = Bands.COUNT
    // Drift and Zen: each band glides from one random point to the next, each glide its own length.
    private val from = FloatArray(n)
    private val to = FloatArray(n) { rng.between(-1f, 1f) }
    private val start = DoubleArray(n)
    private val length = DoubleArray(n) { 1.0 }
    // Waves: a swell rolling up through the bands, its speed itself wandering.
    private var wavePhase = rng.between(0f, 2 * PI.toFloat()).toDouble()
    private val wavePeriod = Glide(rng, 14f, 26f, 30f, 60f)
    // Breathe: one breath in, a longer one out, each breath a little different.
    private var breath = 0.0
    private var breathLength = 11.0
    private var last = Double.NaN
    private var mode = Motion.Off

    fun offsets(motion: Motion, t: Double, out: FloatArray) {
        if (motion != mode) { mode = motion; restart(t) }
        val dt = if (last.isNaN()) 0.0 else (t - last).coerceIn(0.0, 1.0)
        last = t
        when (motion) {
            Motion.Off -> out.fill(0f)
            Motion.Zen -> wander(t, out, depth = 0.06f, shortest = 20f, longest = 45f)
            Motion.Drift -> wander(t, out, depth = 0.14f, shortest = 7f, longest = 18f)
            Motion.Waves -> {
                wavePhase += 2 * PI * dt / wavePeriod.at(t)
                for (i in 0 until n) out[i] = 0.12f * sin(wavePhase - i * 0.55).toFloat()
            }
            Motion.Breathe -> {
                breath += dt / breathLength
                if (breath >= 1.0) { breath -= 1.0; breathLength = rng.between(9f, 13f).toDouble() }
                // In for 40% of the breath, out for the rest; the treble breathes more than the bass, like a long "shh".
                val v = if (breath < 0.4) smooth(breath / 0.4) else 1.0 - smooth((breath - 0.4) / 0.6)
                for (i in 0 until n) out[i] = (0.10 * (2 * v - 1) * (0.5 + 0.5 * i / (n - 1))).toFloat()
            }
        }
    }

    private fun restart(t: Double) {
        for (i in 0 until n) { from[i] = 0f; to[i] = rng.between(-1f, 1f); start[i] = t; length[i] = rng.between(4f, 9f).toDouble() }
        last = Double.NaN
        breath = 0.4
    }

    private fun wander(t: Double, out: FloatArray, depth: Float, shortest: Float, longest: Float) {
        for (i in 0 until n) {
            if (t >= start[i] + length[i]) {
                from[i] = to[i]
                to[i] = rng.between(-1f, 1f)
                start[i] = t
                length[i] = rng.between(shortest, longest).toDouble()
            }
            val p = ((t - start[i]) / length[i]).coerceIn(0.0, 1.0)
            out[i] = depth * (from[i] + (to[i] - from[i]) * smooth(p).toFloat())
        }
    }

    /** One slow random value between [low] and [high], gliding to a new one every [shortest]–[longest] s. */
    private class Glide(private val rng: Rng, private val low: Float, private val high: Float,
                        private val shortest: Float, private val longest: Float) {
        private var a = rng.between(low, high)
        private var b = rng.between(low, high)
        private var start = 0.0
        private var length = rng.between(shortest, longest).toDouble()

        fun at(t: Double): Double {
            if (t < start) start = t
            if (t >= start + length) { a = b; b = rng.between(low, high); start = t; length = rng.between(shortest, longest).toDouble() }
            return a + (b - a) * smooth(((t - start) / length).coerceIn(0.0, 1.0))
        }
    }

    companion object {
        private fun smooth(p: Double) = p * p * (3 - 2 * p)

        /** The level playing for a slider set at [set] moved by [offset]; quiet sliders move less, and zero stays zero. */
        fun apply(set: Float, offset: Float): Float = (set + offset * minOf(1f, set / 0.15f)).coerceIn(0f, 1f)
    }
}
