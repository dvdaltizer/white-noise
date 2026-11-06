package com.slowpixels.whitenoise.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A radix-2 complex FFT of [n] points (a power of two, at least 4), in place, with its tables made once. Kept lean: the
 * first two passes are done together (their twiddles are 1 and i, so nothing to multiply), and each later pass reads
 * its own twiddles in order.
 */
class Fft(val n: Int) {
    private val bits = Integer.numberOfTrailingZeros(n)
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
    // The pass that joins halves of length h uses e^(iπk/h), k < h, stored from index h on.
    private val twR = FloatArray(n)
    private val twI = FloatArray(n)

    init {
        require(n >= 4 && n and (n - 1) == 0) { "FFT size must be a power of two, at least 4" }
        var h = 1
        while (h < n) {
            for (k in 0 until h) { twR[h + k] = cos(PI * k / h).toFloat(); twI[h + k] = sin(PI * k / h).toFloat() }
            h *= 2
        }
    }

    /** The inverse transform without the 1/n: x[t] = Σ X[k]·e^(2πikt/n). */
    fun inverse(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val m = im[i]; im[i] = im[j]; im[j] = m
            }
        }
        // Lengths 2 and 4 in one pass.
        var s = 0
        while (s < n) {
            val ar = re[s] + re[s + 1]; val ai = im[s] + im[s + 1]
            val br = re[s] - re[s + 1]; val bi = im[s] - im[s + 1]
            val cr = re[s + 2] + re[s + 3]; val ci = im[s + 2] + im[s + 3]
            val dr = re[s + 2] - re[s + 3]; val di = im[s + 2] - im[s + 3]
            re[s] = ar + cr; im[s] = ai + ci
            re[s + 2] = ar - cr; im[s + 2] = ai - ci
            re[s + 1] = br - di; im[s + 1] = bi + dr
            re[s + 3] = br + di; im[s + 3] = bi - dr
            s += 4
        }
        var h = 4
        while (h < n) {
            var start = 0
            while (start < n) {
                var a = start
                var t = h
                val end = start + h
                while (a < end) {
                    val b = a + h
                    val wr = twR[t]; val wi = twI[t]
                    val xr = re[b]; val xi = im[b]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    val yr = re[a]; val yi = im[a]
                    re[b] = yr - tr; im[b] = yi - ti
                    re[a] = yr + tr; im[a] = yi + ti
                    a++; t++
                }
                start += 2 * h
            }
            h *= 2
        }
    }
}
