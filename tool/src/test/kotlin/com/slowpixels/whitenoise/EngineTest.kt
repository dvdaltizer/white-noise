package com.slowpixels.whitenoise

import com.slowpixels.whitenoise.engine.Animator
import com.slowpixels.whitenoise.engine.Bands
import com.slowpixels.whitenoise.engine.Fft
import com.slowpixels.whitenoise.engine.Motion
import com.slowpixels.whitenoise.engine.NoiseEngine
import com.slowpixels.whitenoise.engine.Rng
import com.slowpixels.whitenoise.play.Sounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** What the ear would judge over a night: even loudness, the right colour, no repeats, no clicks. */
class EngineTest {
    private val rate = 48_000

    private fun engine(levels: List<Float>, motion: Motion = Motion.Off) = NoiseEngine(rate).apply {
        this.levels = levels.toFloatArray()
        this.motion = motion
        primeForTest()
    }

    /** [seconds] of sound as left and right floats (−1..1), after the overlap has filled. */
    private fun record(e: NoiseEngine, seconds: Double): Pair<FloatArray, FloatArray> {
        repeat(4) { e.render() }
        val out = mutableListOf<ShortArray>()
        var frames = 0
        while (frames < seconds * rate) { val b = e.render().copyOf(); out += b; frames += b.size / 2 }
        val l = FloatArray(frames); val r = FloatArray(frames)
        var i = 0
        for (b in out) for (k in 0 until b.size / 2) { l[i] = b[2 * k] / 32767f; r[i] = b[2 * k + 1] / 32767f; i++ }
        return l to r
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i].toDouble()
        return sqrt(s / (to - from))
    }

    private fun db(x: Double) = 20 * log10(x)

    @Test fun loudnessMatchesTheSliders() {
        val e = engine(Sounds.soft.levels)
        val power = FloatArray(Bands.COUNT) { Bands.amplitude(Sounds.soft.levels[it]).let { a -> a * a } }
        val expected = e.spectrum.rms(power).toDouble()
        val (l, r) = record(e, 10.0)
        assertEquals(db(expected), db(rms(l)), 0.4)
        assertEquals(db(expected), db(rms(r)), 0.4)
        // Soft sits well clear of the top, so nothing is ever rounded off.
        assertTrue("Soft is ${db(expected)} dBFS", db(expected) in -24.0..-12.0)
        assertTrue(l.all { abs(it) < 0.85f } && r.all { abs(it) < 0.85f })
    }

    /** Loudness second by second over two minutes: no pumping from the blocks' blending. */
    @Test fun loudnessIsSteady() {
        val (l, _) = record(engine(Sounds.all.first { it.id == "pink" }.levels), 120.0)
        // Noise's own wobble: a second of pink noise varies by about 0.13 dB, mostly from its few slow bass waves.
        val each = (0 until 120).map { db(rms(l, it * rate, (it + 1) * rate)) }
        val mean = each.average()
        val spread = sqrt(each.sumOf { (it - mean) * (it - mean) } / each.size)
        assertTrue("seconds vary ${each.min() - mean}..${each.max() - mean} dB, spread $spread", each.all { abs(it - mean) < 0.6 } && spread < 0.2)
        // And within a second: 10 ms slices stay close to the average, as true noise does (no beat at the 85 ms blocks).
        val slice = rate / 100
        val slices = (0 until 100 * 20).map { rms(l, it * slice, (it + 1) * slice) }
        val sliceMean = slices.average()
        val beat = (0 until 4).map { phase -> slices.filterIndexed { i, _ -> (i * slice % 4096) / 1024 == phase }.average() / sliceMean }
        assertTrue("block phase loudness $beat", beat.all { abs(it - 1) < 0.02 })
    }

    /** White noise, whose chance likeness over a second is tiny (±0.005), so any repeat would stand out. */
    @Test fun channelsAreIndependentAndNothingRepeats() {
        val (l, r) = record(engine(Sounds.byId("white")!!.levels), 60.0)
        fun corr(a: FloatArray, ao: Int, b: FloatArray, bo: Int, n: Int): Double {
            var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until n) { ab += a[ao + i] * b[bo + i].toDouble(); aa += a[ao + i] * a[ao + i].toDouble(); bb += b[bo + i] * b[bo + i].toDouble() }
            return ab / sqrt(aa * bb)
        }
        assertTrue(abs(corr(l, 0, r, 0, l.size)) < 0.01)
        // The first second against every later one, at every block offset: no stretch comes back.
        for (s in 1 until 59) for (shift in 0 until 4) {
            val c = corr(l, 0, l, s * rate + shift * 2048, rate)
            assertTrue("second $s shift $shift correlates $c", abs(c) < 0.03)
        }
    }

    @Test fun fftMatchesTheSlowWay() {
        for (n in listOf(4, 16, 256)) {
            val rng = Rng(n.toLong())
            val xr = FloatArray(n) { rng.between(-1f, 1f) }
            val xi = FloatArray(n) { rng.between(-1f, 1f) }
            val re = xr.copyOf(); val im = xi.copyOf()
            Fft(n).inverse(re, im)
            for (t in 0 until n) {
                var sr = 0.0; var si = 0.0
                for (k in 0 until n) {
                    val a = 2 * Math.PI * k * t / n
                    sr += xr[k] * kotlin.math.cos(a) - xi[k] * kotlin.math.sin(a)
                    si += xr[k] * kotlin.math.sin(a) + xi[k] * kotlin.math.cos(a)
                }
                assertEquals(sr, re[t].toDouble(), 1e-3 * n)
                assertEquals(si, im[t].toDouble(), 1e-3 * n)
            }
        }
    }

    /** Energy per octave band, measured from the sound itself. */
    private fun octaves(l: FloatArray): DoubleArray {
        val n = 8192
        val fft = Fft(n)
        val re = FloatArray(n); val im = FloatArray(n)
        val bands = DoubleArray(Bands.COUNT)
        var at = 0
        while (at + n <= l.size) {
            for (i in 0 until n) { re[i] = l[at + i] * kotlin.math.sin(Math.PI * (i + 0.5) / n).toFloat(); im[i] = 0f }
            fft.inverse(re, im)
            for (k in 1 until n / 2) {
                val f = k * rate.toDouble() / n
                val b = Math.round(kotlin.math.ln(f / 31.25) / kotlin.math.ln(2.0)).toInt()
                if (b in 0 until Bands.COUNT && f > 22.1 && f < 19_000) bands[b] += (re[k] * re[k] + im[k] * im[k]).toDouble()
            }
            at += n
        }
        return bands
    }

    @Test fun pinkHasEqualOctavesAndWhiteRisesThreeDb() {
        val pink = octaves(record(engine(Sounds.byId("pink")!!.levels), 30.0).first)
        for (b in 2..8) assertEquals("pink band $b", 0.0, 10 * log10(pink[b] / pink[5]), 1.0)
        val white = octaves(record(engine(Sounds.byId("white")!!.levels), 30.0).first)
        for (b in 2..8) assertEquals("white band $b", 3.0 * (b - 5), 10 * log10(white[b] / white[5]), 1.0)
    }

    @Test fun oneSliderMakesItsOwnBand() {
        val levels = MutableList(Bands.COUNT) { 0f }.also { it[5] = 0.8f }
        val o = octaves(record(engine(levels), 20.0).first)
        // Its own octave is loudest; two octaves away it has all but gone.
        assertTrue(o[5] > o[4] && o[5] > o[6])
        assertTrue(10 * log10(o[3] / o[5]) < -20 && 10 * log10(o[7] / o[5]) < -20)
    }

    @Test fun startsWithoutAPop() {
        val e = NoiseEngine(rate).apply { levels = Sounds.soft.levels.toFloatArray() }
        // As start() leaves it: silent, turning up over 1.5 s.
        e.fade(1f, 1.5f)
        val first = e.render()
        assertTrue(first.take(200).all { abs(it.toInt()) < 200 })
    }

    @Test fun animateStaysNearTheSliders() {
        for (m in Motion.entries) {
            val a = Animator(Rng(42))
            val out = FloatArray(Bands.COUNT)
            var t = 0.0
            var widest = 0f
            while (t < 600) {
                a.offsets(m, t, out)
                widest = maxOf(widest, out.maxOf { abs(it) })
                t += 0.0427
            }
            assertTrue("$m moves $widest", widest <= 0.15f)
            if (m != Motion.Off) assertTrue("$m should move", widest > 0.04f)
        }
        assertEquals(0f, Animator.apply(0f, 0.15f))
        assertEquals(1f, Animator.apply(0.95f, 0.15f))
    }

    @Test fun waveAndBreathNeverLoopExactly() {
        val a = Animator(Rng(7))
        val out = FloatArray(Bands.COUNT)
        val trace = mutableListOf<Float>()
        var t = 0.0
        while (t < 1200) { a.offsets(Motion.Breathe, t, out); trace += out[9]; t += 0.5 }
        // Breaths come at 9–13 s, each its own length: the trace never matches itself a whole minute later.
        for (lag in 18..26) {
            val d = (0 until 120).map { abs(trace[it + 400] - trace[it + 400 + lag]) }.average()
            assertTrue("lag $lag repeats", d > 0.002)
        }
    }

    @Test fun `sound stops only when it would move from headphones to the room`() {
        val speaker = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val wired = android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val bluetooth = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        assertTrue(NoiseEngine.leftHeadphones(wasOn = true, type = speaker))
        assertTrue(!NoiseEngine.leftHeadphones(wasOn = false, type = speaker))
        assertTrue(!NoiseEngine.leftHeadphones(wasOn = true, type = bluetooth))
        assertTrue(!NoiseEngine.leftHeadphones(wasOn = false, type = wired))
    }

    @Test fun `the night clock reads as a clock should`() {
        assertEquals("12:05", com.slowpixels.whitenoise.ui.clockText(0, 5, is24 = false))
        assertEquals("9:30", com.slowpixels.whitenoise.ui.clockText(9, 30, is24 = false))
        assertEquals("12:00", com.slowpixels.whitenoise.ui.clockText(12, 0, is24 = false))
        assertEquals("1:07", com.slowpixels.whitenoise.ui.clockText(13, 7, is24 = false))
        assertEquals("00:05", com.slowpixels.whitenoise.ui.clockText(0, 5, is24 = true))
        assertEquals("22:07", com.slowpixels.whitenoise.ui.clockText(22, 7, is24 = true))
    }

    @Test fun `the night clock turns round and comes back upright`() {
        assertEquals(1, com.slowpixels.whitenoise.ui.nextTurn(0))
        assertEquals(3, com.slowpixels.whitenoise.ui.nextTurn(1))
        assertEquals(0, com.slowpixels.whitenoise.ui.nextTurn(3))
        assertEquals("the clock starts sideways", 1, com.slowpixels.whitenoise.play.Settings.Values().clockTurn)
    }
}
