package com.slowpixels.whitenoise.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.slowpixels.whitenoise.engine.Motion
import com.slowpixels.whitenoise.play.NoiseHub
import com.slowpixels.whitenoise.play.Settings
import com.slowpixels.whitenoise.play.Sounds
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

class SoundsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val settings by NoiseHub.settings.values.collectAsState()
        ListPage("Sounds", back = { goBack() }) {
            for (s in Sounds.all) Choice(s.name, s.id == settings.sound) { NoiseHub.choose(s); goBack() }
            if (settings.mine != null) Choice("Mine", settings.sound == Settings.MINE) { NoiseHub.chooseMine(); goBack() }
            Choice("About") { navigateTo(::AboutScreen) }
        }
    }
}

class AnimateScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val settings by NoiseHub.settings.values.collectAsState()
        ListPage("Animate", back = { goBack() }) {
            for (m in Motion.entries) Choice(m.label, m == settings.motion, detail = m.detail) { NoiseHub.setMotion(m); goBack() }
        }
    }
}

class SleepScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val ui by NoiseHub.ui.collectAsState()
        ListPage("Sleep", back = { goBack() }) {
            for (m in listOf(15, 30, 60, 120, 240, 480)) {
                Choice(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "${m / 60} hours") { NoiseHub.setSleep(m); goBack() }
            }
            if (ui.sleepOn) Choice("Turn off") { NoiseHub.setSleep(null); goBack() }
        }
    }
}

class AboutScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        ListPage("About", back = { goBack() }) {
            val gap = Modifier.padding(bottom = 1f.gridUnitsAsDp())
            LightText("Noise made live on your phone. It never loops, and it never repeats.", LightTextVariant.Paragraph, gap)
            LightText("Works with no connection. Nothing you do here leaves the phone.", LightTextVariant.Paragraph, gap)
            LightText("Made by Slow Pixels", LightTextVariant.Paragraph, lighten = true)
        }
    }
}
