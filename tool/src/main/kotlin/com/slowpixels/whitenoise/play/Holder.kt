package com.slowpixels.whitenoise.play

import com.slowpixels.whitenoise.engine.NoiseEngine
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightMediaMetadata
import java.io.DataOutputStream
import java.io.File

/**
 * The SDK's detached player can only play files, and a file would have to loop, so the sound itself comes from
 * [NoiseEngine]. This is what the detached player plays instead: a queue of silence. It gives the tool everything
 * LightOS offers a player (a foreground service for playing with the screen off, audio focus, the lock screen's
 * play and pause) and [NoiseHub] makes the engine follow it.
 */
internal object Holder {
    private const val RATE = 8_000
    private const val SECONDS = 600
    const val QUEUE = 60

    /** Ten minutes of 8-bit silence, written once: 4.8 MB. */
    fun silence(dir: File): File {
        val file = File(dir, "silence.wav")
        val data = RATE * SECONDS
        if (file.length() == 44L + data) return file
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun text(s: String) = out.writeBytes(s)
            fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            text("RIFF"); int(36 + data); text("WAVE")
            text("fmt "); int(16); short(1); short(1); int(RATE); int(RATE); short(1); short(8)
            text("data"); int(data)
            val quiet = ByteArray(RATE) { 0x80.toByte() }
            repeat(SECONDS) { out.write(quiet) }
        }
        return file
    }

    fun item(file: File, n: Int) = LightAudioItem(
        source = LightAudioSource.FileSource(file),
        metadata = LightMediaMetadata(title = "White Noise"),
        id = "silence-$n",
    )
}
