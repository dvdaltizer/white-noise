package com.slowpixels.whitenoise.play

import com.slowpixels.whitenoise.engine.Bands
import com.slowpixels.whitenoise.engine.Motion
import java.io.File
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow

/** The few things kept between uses: the sliders, which sound they came from, your own shape, and Animate. */
class Settings(private val file: File) {
    data class Values(
        val levels: List<Float> = Sounds.soft.levels,
        /** A ready-made sound's id while the sliders are still as it set them, else [MINE]. */
        val sound: String = Sounds.soft.id,
        /** The sliders as you last set them yourself. Kept when you try a ready-made sound. */
        val mine: List<Float>? = null,
        val motion: Motion = Motion.Off,
        /** How bright the night clock is, and which way up it stands (quarter turns clockwise). */
        val clockGlow: Int = 1,
        val clockTurn: Int = SIDEWAYS,
    )

    val values = MutableStateFlow(read())

    private fun read(): Values {
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }
        return Values(
            levels = parse(p.getProperty("levels")) ?: Sounds.soft.levels,
            sound = p.getProperty("sound") ?: Sounds.soft.id,
            mine = parse(p.getProperty("mine")),
            motion = Motion.entries.firstOrNull { it.name == p.getProperty("motion") } ?: Motion.Off,
            clockGlow = p.getProperty("clockGlow")?.toIntOrNull() ?: 1,
            clockTurn = p.getProperty("clockTurn")?.toIntOrNull() ?: SIDEWAYS,
        )
    }

    fun update(change: (Values) -> Values) {
        val v = change(values.value)
        values.value = v
        val p = Properties()
        p.setProperty("levels", v.levels.joinToString(","))
        p.setProperty("sound", v.sound)
        v.mine?.let { p.setProperty("mine", it.joinToString(",")) }
        p.setProperty("motion", v.motion.name)
        p.setProperty("clockGlow", v.clockGlow.toString())
        p.setProperty("clockTurn", v.clockTurn.toString())
        runCatching { file.outputStream().use { p.store(it, null) } }
    }

    companion object {
        const val MINE = "mine"

        /** The night clock's default: one quarter turn, for a phone lying on its side with its buttons facing up. */
        const val SIDEWAYS = 1

        private fun parse(s: String?): List<Float>? =
            s?.split(',')?.mapNotNull { it.toFloatOrNull()?.coerceIn(0f, 1f) }?.takeIf { it.size == Bands.COUNT }
    }
}
