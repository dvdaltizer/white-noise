package com.slowpixels.whitenoise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens

/** A line-drawn crescent, as LightOS has no moon of its own. */
@Composable
fun moonPainter(): Painter {
    val ink = LightThemeTokens.colors.content
    val moon = remember(ink) {
        ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes("M20.5 14.5A9 9 0 1 1 9.5 3.5A7.2 7.2 0 0 0 20.5 14.5Z"), stroke = SolidColor(ink), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round)
            .build()
    }
    return rememberVectorPainter(moon)
}
