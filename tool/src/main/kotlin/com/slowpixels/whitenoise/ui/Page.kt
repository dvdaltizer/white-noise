package com.slowpixels.whitenoise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** LightOS's theme on a full screen. */
@Composable
fun Themed(content: @Composable ColumnScope.() -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background), content = content)
    }
}

/** A sub-screen: a back arrow and title over a scrolling list. */
@Composable
fun ListPage(title: String, back: () -> Unit, rows: @Composable ColumnScope.() -> Unit) = Themed {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = back),
        center = LightTopBarCenter.Text(title),
        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
    )
    LightScrollView(Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()), content = rows)
}

/** One choice in a list; the one in use is underlined, as LightOS's own settings lists do. */
@Composable
fun Choice(label: String, selected: Boolean = false, detail: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().lightClickable(onClick = onClick).semantics(mergeDescendants = true) { this.selected = selected }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(label.uppercase(), LightTextVariant.Copy, underline = selected)
        if (detail != null) LightText(detail, LightTextVariant.Fine, lighten = true)
    }
}
