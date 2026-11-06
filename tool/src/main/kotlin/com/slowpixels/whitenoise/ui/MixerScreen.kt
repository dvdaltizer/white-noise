package com.slowpixels.whitenoise.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.slowpixels.whitenoise.engine.Bands
import com.slowpixels.whitenoise.engine.Motion
import com.slowpixels.whitenoise.play.NoiseHub
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** A step of "all quieter" / "all louder": about 1.5 dB at the usual heights. */
private const val STEP = 0.05f

class MixerViewModel(audio: LightAudio, filesDir: File) : LightViewModel<Unit>() {
    init { NoiseHub.attach(this, audio, filesDir) }

    override fun onCleared() {
        NoiseHub.leave(this)
        super.onCleared()
    }
}

/** The one main screen: the ten sliders, play with "all quieter" and "all louder" either side, and three choices. */
@InitialScreen
class MixerScreen(private val sealed: SealedLightActivity) : LightScreen<Unit, MixerViewModel>(sealed) {
    override val viewModelClass = MixerViewModel::class.java
    override fun createViewModel() = MixerViewModel(DefaultLightAudio(sealed), lightContext.filesDir)

    @Composable
    override fun Content() {
        viewModel
        val hub = NoiseHub
        val ui by hub.ui.collectAsState()
        val levels by hub.levels.collectAsState()
        val settings by hub.settings.values.collectAsState()
        var touch by remember { mutableStateOf<Touch?>(null) }
        val movement = movement(ui.playing && settings.motion != Motion.Off)
        Themed {
            LightTopBar(center = LightTopBarCenter.TwoLineDetail("White Noise", status(touch, levels, ui, settings.sound, settings.motion)))
            SliderBank(levels, shownLevels(levels, movement, touch), onTouch = { touch = it }, onMove = hub::preview, onDone = hub::commit,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()))
            Row(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp())) {
                LightText("Bass", LightTextVariant.Fine, lighten = true, modifier = Modifier.weight(1f))
                LightText("Treble", LightTextVariant.Fine, lighten = true)
            }
            LightBottomBar(listOf(
                LightBarButton.LightIcon(LightIcons.DOWN, onClick = { hub.nudgeAll(-STEP) }, contentDescription = "All quieter"),
                LightBarButton.LightIcon(if (ui.playing) LightIcons.PAUSE else LightIcons.PLAY, onClick = hub::toggle,
                    contentDescription = if (ui.playing) "Pause" else "Play", sizeUnits = 3f),
                LightBarButton.LightIcon(LightIcons.UP, onClick = { hub.nudgeAll(STEP) }, contentDescription = "All louder"),
            ))
            LightBottomBar(listOf(
                LightBarButton.Text("SOUNDS", onClick = { navigateTo(::SoundsScreen) }),
                LightBarButton.Text("ANIMATE", onClick = { navigateTo(::AnimateScreen) }),
                LightBarButton.Text("SLEEP", onClick = { navigateTo(::SleepScreen) }),
            ))
        }
    }
}

/** Under the title: the band under your finger, why the sound stopped, or what's set. */
@Composable
private fun status(touch: Touch?, levels: List<Float>, ui: NoiseHub.Ui, sound: String, motion: Motion): String {
    val left = sleepShort(ui)
    return when {
        touch is Touch.Band -> "${Bands.names[touch.index]} ${(levels[touch.index] * 100).roundToInt()}%"
        touch == Touch.All -> "All sliders"
        ui.error != null -> ui.error
        else -> listOfNotNull(NoiseHub.soundName(sound), motion.takeIf { it != Motion.Off }?.label, left).joinToString(" · ")
    }
}

/** Animate's movement while it runs: read twice a second, and only redrawn when it has moved. */
@Composable
private fun movement(on: Boolean): List<Float>? {
    if (!on) return null
    val engine = NoiseHub.engine
    var shown by remember { mutableStateOf(engine.movement.toList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = engine.movement.toList()
            if (now.indices.any { abs(now[it] - shown.getOrElse(it) { 0f }) > 0.01f }) shown = now
            delay(500)
        }
    }
    return shown
}

/** The sleep timer's time left: "45m", "2h", "7h 59m". */
@Composable
private fun sleepShort(ui: NoiseHub.Ui): String? {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.sleepAt) { while (ui.sleepAt != null) { delay(5_000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val left = ui.sleepRemaining() ?: return null
    val min = maxOf(1, ceil(left / 60_000.0).toInt())
    return if (min < 60) "${min}m" else if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m"
}
