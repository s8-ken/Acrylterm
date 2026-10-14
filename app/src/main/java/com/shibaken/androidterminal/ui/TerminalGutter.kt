package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** Match the terminal's default background and opacity while keeping drawing and touch coordinates aligned. */
@Composable
internal fun Modifier.terminalGutters(): Modifier {
    val background = CyanVeil.Background.copy(alpha = LocalAppearance.current.backgroundOpacity)
    val gutterWidth = 6.dp
    return drawBehind {
        val gutter = Size(gutterWidth.roundToPx().toFloat().coerceAtMost(size.width / 2f), size.height)
        drawRect(background, size = gutter)
        drawRect(background, topLeft = Offset(size.width - gutter.width, 0f), size = gutter)
    }
        .absolutePadding(left = gutterWidth, right = gutterWidth)
        // Canvas.drawColor fills its clip; keep the view from painting over the gutters a second time.
        .clipToBounds()
}
