package com.slowpixels.whitenoise.engine

/**
 * xorshift64*: fast, and with a period of 2⁶⁴ − 1 numbers. At the engine's rate of about 200,000 a second it would
 * take millions of years to come round again, so the noise never repeats.
 */
class Rng(seed: Long) {
    private var s = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun next(): Long {
        var x = s
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        s = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun float(): Float = (next() ushr 40).toFloat() / (1 shl 24)

    /** Uniform in [a, b). */
    fun between(a: Float, b: Float): Float = a + (b - a) * float()

    companion object {
        /** A seed nothing else will have: the clock and the system's own randomness. */
        fun seed(): Long = System.nanoTime() xor java.security.SecureRandom().nextLong()
    }
}
