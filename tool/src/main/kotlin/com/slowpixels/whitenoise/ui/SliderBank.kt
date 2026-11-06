package com.slowpixels.whitenoise.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.slowpixels.whitenoise.engine.Animator
import com.slowpixels.whitenoise.engine.Bands
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.roundToInt

/** Where a finger is, for the line above the sliders: on one band, or moving them all. */
sealed interface Touch {
    data class Band(val index: Int) : Touch
    data object All : Touch
}

private val KnobRadius = 13.dp
private val TrackWidth = 18.dp

/**
 * The ten sliders, touched as Clearcast's bar is: a tap jumps the slider to that height, and a drag follows the
 * finger; sliding sideways sets every slider the finger passes, at the height it passed it, so a shape can be drawn
 * in one stroke. Two fingers move every slider together, keeping their shape (put down together, the first finger's
 * jump doesn't count). What you hear changes as the finger moves; the shape is saved when it lifts ([onDone]).
 *
 * Each track fills up to the level playing ([shown], which Animate moves), with the round knob on top of the fill, so
 * the two always move as one.
 */
@Composable
fun SliderBank(levels: List<Float>, shown: List<Float>, onTouch: (Touch?) -> Unit, onMove: (List<Float>) -> Unit,
               onDone: () -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(levels)
    val touch by rememberUpdatedState(onTouch)
    val move by rememberUpdatedState(onMove)
    val done by rememberUpdatedState(onDone)
    val knob = with(LocalDensity.current) { KnobRadius.toPx() }
    val ink = LightThemeTokens.colors.content
    val paper = LightThemeTokens.colors.background
    Row(modifier.systemGestureExclusion().pointerInput(Unit) { sliderGestures(knob, { current }, { touch(it) }, { move(it) }, { done() }) }) {
        for (i in levels.indices) {
            val level = levels[i]
            val playing = shown.getOrElse(i) { level }
            Box(Modifier.weight(1f).fillMaxHeight()
                .semantics {
                    contentDescription = Bands.names[i]
                    stateDescription = "${(level * 100).roundToInt()}%"
                    progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                    setProgress { v -> move(current.toMutableList().also { it[i] = v.coerceIn(0f, 1f) }); done(); true }
                }
                .drawBehind { drawSlider(ink, paper, playing) })
        }
    }
}

/**
 * Where each slider shows: where it's set plus Animate's [movement], which is what's playing. The slider under a
 * finger shows where it's set, so it stays with the finger; it joins the movement again when the finger moves on.
 */
internal fun shownLevels(levels: List<Float>, movement: List<Float>?, touch: Touch?): List<Float> =
    levels.mapIndexed { i, l -> if (touch == Touch.Band(i)) l else Animator.apply(l, movement?.getOrNull(i) ?: 0f) }

private fun DrawScope.drawSlider(ink: Color, paper: Color, level: Float) {
    val r = KnobRadius.toPx()
    val tw = TrackWidth.toPx()
    val line = 2.dp.toPx()
    val cx = size.width / 2
    val top = r
    val bottom = size.height - r
    fun y(l: Float) = bottom - l.coerceIn(0f, 1f) * (bottom - top)
    val box = Rect(cx - tw / 2, top - tw / 2, cx + tw / 2, bottom + tw / 2)
    val track = Path().apply { addRoundRect(RoundRect(box, CornerRadius(tw / 2))) }
    clipPath(track) { drawRect(ink, Offset(box.left, y(level)), Size(tw, box.bottom - y(level))) }
    drawPath(track, ink, style = Stroke(line))
    val k = Offset(cx, y(level))
    drawCircle(paper, r - line / 2, k)
    drawCircle(ink, r - line / 2, k, style = Stroke(line * 1.15f))
}

private suspend fun PointerInputScope.sliderGestures(knob: Float, levels: () -> List<Float>, onTouch: (Touch?) -> Unit,
                                                     onMove: (List<Float>) -> Unit, onDone: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val before = levels()
        val n = before.size
        var now = before
        val travel = (size.height - 2 * knob).coerceAtLeast(1f)
        fun slot(x: Float) = (x / size.width * n).toInt().coerceIn(0, n - 1)
        fun level(y: Float) = (1f - (y - knob) / travel).coerceIn(0f, 1f)

        /** A finger moving from [from] to [to] sets every slider it passed, at the height it passed it. */
        fun sweep(from: Offset, to: Offset) {
            val a = slot(from.x)
            val b = slot(to.x)
            val next = now.toMutableList()
            if (a != b) {
                val step = if (b > a) 1 else -1
                var i = a + step
                while (i != b) {
                    val x = (i + 0.5f) * size.width / n
                    val t = ((x - from.x) / (to.x - from.x)).coerceIn(0f, 1f)
                    next[i] = level(from.y + (to.y - from.y) * t)
                    i += step
                }
            }
            next[b] = level(to.y)
            now = next
            onTouch(Touch.Band(b))
        }

        sweep(down.position, down.position)
        onMove(now)
        down.consume()
        var finger: PointerId = down.id
        var last = down.position
        var together = false
        var base = now
        var anchor = 0f
        var fingers = 0
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (together || pressed.size >= 2) {
                val y = pressed.take(2).map { it.position.y }.average().toFloat()
                if (!together) {
                    together = true
                    // Both fingers down at about the same moment: the first finger's jump is undone.
                    base = if (event.changes.first().uptimeMillis - down.uptimeMillis < 300) before else now
                    anchor = y
                    fingers = pressed.size
                    onTouch(Touch.All)
                } else if (pressed.size != fingers) {
                    // A finger lifted or joined: carry on from here, so nothing jumps.
                    base = now
                    anchor = y
                    fingers = pressed.size
                }
                val d = (anchor - y) / travel
                now = base.map { (it + d).coerceIn(0f, 1f) }
            } else {
                val c = pressed.first()
                if (c.id != finger) { finger = c.id; last = c.position }
                sweep(last, c.position)
                last = c.position
            }
            onMove(now)
            event.changes.forEach { it.consume() }
        }
        onTouch(null)
        onDone()
    }
}
