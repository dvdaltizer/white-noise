package com.slowpixels.whitenoise.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How loud each frequency is for the sliders' levels, worked out for the [size]-point transform at [sampleRate].
 *
 * Every frequency sits between two band centres and takes a share of each (a raised cosine on the octave scale), and
 * the shares always add up to one, so with the sliders level there are no bumps or dips between bands, and one slider
 * alone makes a smooth band of its own. Under it all is pink noise: the same energy in every octave. The very bottom
 * (below about 20 Hz, felt rather than heard) and the very top (above 20 kHz) fade out.
 */
class Spectrum(val size: Int, val sampleRate: Int) {
    /** For bin k (0..size/2): the band below it, its share of that band (the band above gets the rest), and its base power. */
    private val lower = IntArray(size / 2 + 1)
    private val share = FloatArray(size / 2 + 1)
    private val base = FloatArray(size / 2 + 1)

    init {
        val binHz = sampleRate.toFloat() / size
        val lpStart = minOf(18_000f, 0.38f * sampleRate)
        val lpEnd = minOf(20_500f, 0.45f * sampleRate)
        var total = 0.0
        for (k in 1 until size / 2) {
            val f = k * binHz
            val x = (ln(f / Bands.centers[0]) / ln(2f))
            when {
                x <= 0f -> { lower[k] = 0; share[k] = 1f }
                x >= Bands.COUNT - 1 -> { lower[k] = Bands.COUNT - 2; share[k] = 0f }
                else -> {
                    val i = x.toInt()
                    val c = cos(PI / 2 * (x - i)).toFloat()
                    lower[k] = i; share[k] = c * c
                }
            }
            val highPass = when {
                f < 15f -> 0f
                f < 25f -> sin(PI / 2 * (f - 15f) / 10f).toFloat().let { it * it }
                else -> 1f
            }
            val lowPass = when {
                f <= lpStart -> 1f
                f >= lpEnd -> 0f
                else -> cos(PI / 2 * (f - lpStart) / (lpEnd - lpStart)).toFloat().let { it * it }
            }
            base[k] = highPass * lowPass / f
            // Every bin is used twice (its positive and negative frequency), and at full level every band counts 1.
            total += 2.0 * base[k]
        }
        // At full level on every slider the noise comes out at FULL_RMS; anything louder is the phone's volume's job.
        val scale = (FULL_RMS * FULL_RMS / total).toFloat()
        for (k in base.indices) base[k] *= scale
    }

    /**
     * Each bin's amplitude for the bands' [power] (one per band, 0..1; a slider's amplitude squared) into [out]
     * (size/2 + 1 values). The output's variance per channel is the sum of the squares over all bins.
     */
    fun gains(power: FloatArray, out: FloatArray) {
        out[0] = 0f
        out[size / 2] = 0f
        for (k in 1 until size / 2) {
            val lo = lower[k]
            val s = share[k]
            out[k] = sqrt(base[k] * (s * power[lo] + (1f - s) * power[lo + 1]))
        }
    }

    /** The expected loudness (RMS, full scale = 1) of one channel for the bands' [power]. */
    fun rms(power: FloatArray): Float {
        var v = 0.0
        for (k in 1 until size / 2) v += 2.0 * base[k] * (share[k] * power[lower[k]] + (1f - share[k]) * power[lower[k] + 1])
        return sqrt(v).toFloat()
    }

    companion object {
        /** −6 dBFS: every slider at the top. A slider halfway down is 12 dB quieter (see [Bands.amplitude]). */
        const val FULL_RMS = 0.5
    }
}
