package com.slowpixels.whitenoise.engine

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Makes the noise, live, for as long as it plays, so nothing is recorded and nothing loops.
 *
 * Each block is a fresh random spectrum, scaled by the sliders through [Spectrum], turned into sound by an inverse FFT
 * and cross-faded with the blocks either side. One transform gives both channels, each its own independent noise.
 *
 * The sound goes to an [AudioTrack] on its own thread. Starting, stopping and the sleep timer's fade are ramps on one
 * gain, so nothing starts or stops with a pop.
 */
class NoiseEngine(val sampleRate: Int = nativeRate()) {
    private val n = 8192
    private val hop = n / 2
    private val fft = Fft(n)
    val spectrum = Spectrum(n, sampleRate)
    // A sine window, × √2: two blocks overlapping add up to the same loudness everywhere.
    private val window = FloatArray(n) { (sqrt(2.0) * sin(PI * (it + 0.5) / n)).toFloat() }
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    /** The second half of the last block, still to be blended with the first half of the next. */
    private val tailL = FloatArray(hop)
    private val tailR = FloatArray(hop)
    private val gains = FloatArray(n / 2 + 1)
    private val power = FloatArray(Bands.COUNT)
    private val shaped = FloatArray(Bands.COUNT) { -1f }
    private val offsets = FloatArray(Bands.COUNT)
    private val pcm = ShortArray(hop * 2)
    private val rng = Rng(Rng.seed())
    private val animator = Animator(Rng(Rng.seed()))
    // Random strength and phase by table. Strengths are spread as noise's are (sqrt(−ln u), its square averaging 1).
    private val radius = FloatArray(TABLE) { sqrt(-ln((it + 0.5) / TABLE)).toFloat() }.also { r ->
        val rms = sqrt(r.sumOf { it.toDouble() * it } / r.size).toFloat()
        for (i in r.indices) r[i] /= rms
    }
    private val cosP = FloatArray(TABLE) { cos(2 * PI * it / TABLE).toFloat() }
    private val sinP = FloatArray(TABLE) { sin(2 * PI * it / TABLE).toFloat() }
    // How long blocks take to make, for the log's heartbeat.
    private var blocks = 0
    private var spentNs = 0L
    private var worstNs = 0L
    private var beatAt = 0L

    /** The sliders as set, 0..1 each; read once a block. */
    @Volatile var levels: FloatArray = FloatArray(Bands.COUNT) { 0.5f }
    @Volatile var motion: Motion = Motion.Off
    /** Animate's movement of each slider right now ([Animator.apply] adds it to a level), for the screen. */
    @Volatile var movement: FloatArray = FloatArray(Bands.COUNT)
        private set
    /** Seconds of sound made: Animate's clock, so the movement rests while paused. */
    private var clock = 0.0

    // Loudness: [gain] follows [target] by [step] a sample.
    private var gain = 0f
    @Volatile private var target = 0f
    @Volatile private var step = 1f
    private val lock = Object()
    /** Sound wanted: playing, or fading out to a stop. */
    private var running = false
    /** Run on the main thread once a fade-out reaches silence. */
    private var whenSilent: (() -> Unit)? = null
    private var thread: Thread? = null
    /** Told (on the main thread) when the phone won't play sound for us. */
    var onError: ((String) -> Unit)? = null
    /** Told (on the main thread) when headphones go and the sound would move to the speaker. */
    var onLeftHeadphones: (() -> Unit)? = null
    private var onHeadphones = false
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Plays, fading in over [fadeSeconds]; a fade-out under way turns round. */
    fun start(fadeSeconds: Float = 1.5f) {
        synchronized(lock) {
            whenSilent = null
            running = true
            fade(1f, fadeSeconds)
            if (thread?.isAlive != true) thread = Thread(::loop, "WhiteNoise").apply { start() }
            lock.notifyAll()
        }
    }

    /** Fades out over [fadeSeconds], stops, then runs [then] on the main thread. */
    fun stop(fadeSeconds: Float = 0.3f, then: () -> Unit = {}) {
        synchronized(lock) {
            if (!running) { main.post(then); return }
            whenSilent = then
            fade(0f, fadeSeconds)
        }
    }

    internal fun fade(to: Float, seconds: Float) {
        target = to
        step = maxOf(1e-7f, abs(to - gain) / maxOf(1f, seconds * sampleRate))
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var track: AudioTrack? = null
        var failures = 0
        try {
            while (true) {
                synchronized(lock) { while (!running) lock.wait() }
                val t = track ?: try { makeTrack().also { track = it } } catch (e: Exception) {
                    Log.w(TAG, "No audio track", e); fail("Couldn't play sound."); continue
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                val began = System.nanoTime()
                render()
                heartbeat(t, System.nanoTime() - began)
                var off = 0
                while (off < pcm.size) {
                    val w = t.write(pcm, off, pcm.size - off)
                    if (w < 0) {
                        // The output went away (rare: the audio system restarted). Make a new track and carry on.
                        Log.w(TAG, "Audio track write failed: $w")
                        runCatching { t.release() }
                        track = null
                        if (++failures >= 5) fail("Sound output stopped.")
                        break
                    }
                    off += w
                    failures = 0
                }
                // A fade-out has reached silence: let what's queued play out, stop, and say so.
                if (gain == 0f) {
                    val done = synchronized(lock) {
                        if (target == 0f && whenSilent != null) { running = false; whenSilent.also { whenSilent = null } } else null
                    }
                    if (done != null) {
                        track?.let { runCatching { it.stop() } }
                        main.post(done)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            Log.e(TAG, "Noise engine stopped", e)
            fail("Sound stopped.")
        } finally {
            track?.let { runCatching { it.release() } }
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
        }
    }

    /**
     * Every 5 minutes of play, a line in the system log (`adb logcat -s WhiteNoise`): how many blocks were made, how long
     * they took, and whether the output ever ran dry. In the morning it shows whether the sound ran all night.
     */
    private fun heartbeat(t: AudioTrack, ns: Long) {
        blocks++
        spentNs += ns
        worstNs = maxOf(worstNs, ns)
        val now = System.nanoTime()
        if (beatAt == 0L) beatAt = now
        if (now - beatAt < 300_000_000_000L) return
        Log.i(TAG, "Playing: %d blocks, %.1f ms each (worst %.1f), %d underruns".format(java.util.Locale.US,
            blocks, spentNs / 1e6 / blocks, worstNs / 1e6, runCatching { t.underrunCount }.getOrDefault(-1)))
        blocks = 0; spentNs = 0; worstNs = 0; beatAt = now
    }

    private fun fail(message: String) {
        synchronized(lock) { running = false; whenSilent = null }
        main.post { onError?.invoke(message) }
    }

    /** The next 85 ms of sound into [pcm] (stereo, 16-bit). */
    internal fun render(): ShortArray {
        val set = levels
        val m = motion
        animator.offsets(m, clock, offsets)
        var changed = false
        for (i in 0 until Bands.COUNT) {
            val a = Bands.amplitude(Animator.apply(set[i], offsets[i]))
            power[i] = a * a
            if (power[i] != shaped[i]) { shaped[i] = power[i]; changed = true }
        }
        if (m != Motion.Off) movement = offsets.copyOf()
        // Most of the night nothing moves: the shape is only worked out again when a slider (or Animate) moves it.
        if (changed) spectrum.gains(power, gains)

        // A fresh random spectrum; the negative frequencies get their own, so the two channels are independent.
        re[0] = 0f; im[0] = 0f; re[n / 2] = 0f; im[n / 2] = 0f
        for (k in 1 until n / 2) {
            val g = gains[k]
            draw(k, g)
            draw(n - k, g)
        }
        fft.inverse(re, im)

        // The first half of this block blends with the second half of the last; this block's second half waits.
        val goal = target
        val st = step
        var g = gain
        for (i in 0 until hop) {
            if (g != goal) g = if (g < goal) minOf(goal, g + st) else maxOf(goal, g - st)
            val w = window[i]
            pcm[2 * i] = sample((tailL[i] + re[i] * w) * g)
            pcm[2 * i + 1] = sample((tailR[i] + im[i] * w) * g)
            val v = window[hop + i]
            tailL[i] = re[hop + i] * v
            tailR[i] = im[hop + i] * v
        }
        gain = g
        clock += hop.toDouble() / sampleRate
        return pcm
    }

    private fun draw(k: Int, g: Float) {
        val r = rng.next()
        val m = radius[(r ushr 54).toInt()] * g
        val p = (r ushr 40).toInt() and (TABLE - 1)
        re[k] = m * cosP[p]
        im[k] = m * sinP[p]
    }

    /** Test hook: plays at full gain at once, without a sound card. */
    internal fun primeForTest() { target = 1f; gain = 1f; step = 1f }

    private fun watchRoute(track: AudioTrack) {
        onHeadphones = isHeadphones(track.routedDevice?.type)
        track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { routing ->
            val type = routing.routedDevice?.type ?: return@OnRoutingChangedListener
            if (leftHeadphones(onHeadphones, type)) onLeftHeadphones?.invoke()
            onHeadphones = isHeadphones(type)
        }, main)
    }

    private fun makeTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            // Two blocks queued (about 170 ms): plenty to ride out a busy moment, short enough that sliders feel live.
            .setBufferSizeInBytes(maxOf(min, hop * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also(::watchRoute)
    }

    companion object {
        private const val TAG = "WhiteNoise"
        /** 1024 strengths and 1024 phases: 12 KB, which stays in the processor's fastest cache. */
        private const val TABLE = 1024
        /** Peaks above this are rounded off rather than clipped (only near the very top of every slider). */
        private const val KNEE = 0.85f

        val ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        }

        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        internal fun isHeadphones(type: Int?) = type in HEADPHONES

        /** Headphones have gone and the sound now comes from somewhere the room can hear it. */
        internal fun leftHeadphones(wasOn: Boolean, type: Int) = wasOn && type !in HEADPHONES

        fun nativeRate(): Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 22_050..96_000 } ?: 48_000

        private fun sample(x: Float): Short {
            val a = abs(x)
            val y = if (a <= KNEE) x else Math.copySign(KNEE + (1 - KNEE) * tanh((a - KNEE) / (1 - KNEE)), x)
            return (y * 32767f).toInt().toShort()
        }
    }
}
