package com.slowpixels.whitenoise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.slowpixels.whitenoise.play.NoiseHub
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightColors
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

/** How bright the clock's figures are, on black. The phone's wheel sets the screen itself. */
private val Glow = listOf(0x30, 0x50, 0x80, 0xB0, 0xFF)

/** The controls go this long after the last touch. */
private const val KEYS_MS = 6_000L

/** How far the time wanders, a new place each minute, so no pixel stays lit all night on the OLED screen. */
private const val WANDER_DP = 10

/** "9:05", or "09:05" with a 24-hour clock. */
internal fun clockText(hour: Int, minute: Int, is24: Boolean): String {
    val h = if (is24) hour else (hour % 12).let { if (it == 0) 12 else it }
    return if (is24) "%02d:%02d".format(hour, minute) else "%d:%02d".format(h, minute)
}

/** The next way up: as it stands, on its right side, on its left side. Quarter turns clockwise. */
internal fun nextTurn(turn: Int) = when (turn) { 0 -> 1; 1 -> 3; else -> 0 }

/**
 * The time as large as it fits, on black, for the bedside while the noise plays. The screen stays on, and changes once
 * a minute. A tap shows the few controls, and they go again by themselves.
 */
class NightClockScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    private val shown = mutableIntStateOf(0)

    /**
     * Only while on screen. If the keep-on flag is set when a window comes back from the background, Android treats it
     * as the window's own and it can't be cleared, so the phone would stay awake on the sliders after the clock had gone.
     */
    private val awake = mutableStateOf(false)

    override fun willShow() {
        shown.intValue++
        awake.value = true
    }

    override fun onAppPause() { awake.value = false }

    @Composable
    override fun Content() {
        val settings by NoiseHub.settings.values.collectAsState()
        val ui by NoiseHub.ui.collectAsState()
        val now = minuteClock(shown.intValue)
        val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        var is24 by remember { mutableStateOf(false) }
        val time = clockText(t.hour, t.minute, is24)
        val date = t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        var keys by remember { mutableStateOf(true) }
        var touched by remember { mutableIntStateOf(0) }
        LaunchedEffect(keys, touched) { if (keys) { delay(KEYS_MS); keys = false } }

        val g = Glow[settings.clockGlow.coerceIn(Glow.indices)]
        val ink = Color(g, g, g)
        val colors = LightColors(background = Color.Black, content = ink, contentSecondary = ink.copy(alpha = 0.7f))
        LightTheme(colors = colors) {
            Box(Modifier.fillMaxSize().then(if (awake.value) Modifier.keepScreenOn() else Modifier).background(Color.Black)) {
                // The phone's 12 or 24-hour choice, which only this widget can read from inside a tool.
                AndroidView(factory = { android.widget.TextClock(it) }, modifier = Modifier.size(0.dp), update = { is24 = it.is24HourModeEnabled })
                Box(Modifier.fillMaxSize().quarterTurns(settings.clockTurn)
                    .pointerInput(Unit) { detectTapGestures { keys = !keys; touched++ } }
                    .semantics {
                        contentDescription = "$time, $date"
                        onClick("Show controls") { keys = true; touched++; true }
                    }) {
                    Time(time, date, now, ink)
                    if (keys) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                            center = LightTopBarCenter.Text(soundLine(settings.sound, settings.motion, ui)),
                            rightButton = LightBarButton.LightIcon(LightIcons.ROTATE, contentDescription = "Turn the clock", onClick = {
                                NoiseHub.settings.update { it.copy(clockTurn = nextTurn(it.clockTurn)) }
                                touched++
                            }),
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                        LightBottomBar(listOf(
                            LightBarButton.LightIcon(LightIcons.DOWN, contentDescription = "Dimmer", onClick = { glow(-1); touched++ }),
                            LightBarButton.LightIcon(if (ui.playing) LightIcons.PAUSE else LightIcons.PLAY,
                                contentDescription = if (ui.playing) "Pause" else "Play", sizeUnits = 3f, onClick = { NoiseHub.toggle(); touched++ }),
                            LightBarButton.LightIcon(LightIcons.UP, contentDescription = "Brighter", onClick = { glow(1); touched++ }),
                        ), modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    private fun glow(step: Int) = NoiseHub.settings.update { it.copy(clockGlow = (it.clockGlow + step).coerceIn(Glow.indices)) }
}

/** The clock, read when the screen comes back and on each new minute. */
@Composable
private fun minuteClock(shown: Int): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shown) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    return now
}

/** The figures as wide as the screen allows, with the date under them. */
@Composable
private fun Time(time: String, date: String, now: Long, ink: Color) {
    val minute = now / 60_000
    val wander = remember(minute) { Random(minute).let { it.nextInt(-WANDER_DP, WANDER_DP + 1) to it.nextInt(-WANDER_DP, WANDER_DP + 1) } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val style = LightThemeTokens.typography.title.copy(color = ink, textAlign = TextAlign.Center, lineHeight = TextUnit.Unspecified)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val size = remember(time.length, constraints.maxWidth) {
            val probe = 100.sp
            val width = measurer.measure(time.replace(Regex("\\d"), "0"), style.copy(fontSize = probe)).size.width
            val room = constraints.maxWidth - with(density) { 2 * (WANDER_DP + 8).dp.toPx() }
            probe * (room / width)
        }
        Column(Modifier.offset(wander.first.dp, wander.second.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(time, style = style.copy(fontSize = size), maxLines = 1)
            LightText(date, LightTextVariant.Paragraph, color = ink, maxLines = 1)
        }
    }
}

/** Lays the content out a number of quarter turns clockwise, for a phone resting on its side. Touches turn with it. */
private fun Modifier.quarterTurns(turns: Int) = layout { measurable, constraints ->
    val sideways = turns % 2 != 0
    val inner = if (sideways) Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth) else constraints
    val placeable = measurable.measure(inner)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeWithLayer((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2) {
            rotationZ = turns * 90f
        }
    }
}
