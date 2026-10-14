package com.shibaken.androidterminal.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shibaken.androidterminal.data.ColorPalette
import com.shibaken.androidterminal.data.ColorProfiles
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.herdr.AgentStatus

/** Color roles read through a snapshot state, so changing [palette] recomposes/redraws every reader. */
object CyanVeil {
    var palette by mutableStateOf(ColorProfiles.CyanVeil)

    val Background get() = Color(palette.background)
    val Surface get() = Color(palette.surface)
    val Raised get() = Color(palette.raised)
    val Cyan get() = Color(palette.accent)
    val Mint get() = Color(palette.secondary)
    val Amber get() = Color(palette.warning)
    val Rose get() = Color(palette.error)
    val Text get() = Color(palette.text)
    val Muted get() = Color(palette.muted)
    val Border get() = Color(palette.border)
    val Ink get() = Color(palette.ink)
}

private fun schemeOf(p: ColorPalette) = darkColorScheme(
    primary = Color(p.accent), onPrimary = Color(p.ink),
    primaryContainer = Color(p.raised), onPrimaryContainer = Color(p.accent),
    secondary = Color(p.secondary), onSecondary = Color(p.ink),
    secondaryContainer = Color(p.raised), onSecondaryContainer = Color(p.secondary),
    tertiary = Color(p.warning), onTertiary = Color(p.ink),
    tertiaryContainer = Color(p.raised), onTertiaryContainer = Color(p.warning),
    background = Color(p.background), onBackground = Color(p.text),
    surface = Color(p.surface), onSurface = Color(p.text),
    surfaceVariant = Color(p.raised), onSurfaceVariant = Color(p.muted),
    surfaceContainer = Color(p.surface),
    surfaceDim = Color(p.background), surfaceBright = Color(p.raised),
    surfaceContainerLowest = Color(p.background), surfaceContainerLow = Color(p.surface),
    surfaceContainerHigh = Color(p.raised),
    surfaceContainerHighest = Color(p.surfaceHighest),
    outline = Color(p.muted), outlineVariant = Color(p.border),
    error = Color(p.error), onError = Color(p.ink),
    errorContainer = Color(p.errorContainer), onErrorContainer = Color(p.error),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = schemeOf(CyanVeil.palette), shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )) { CompositionLocalProvider(LocalContentColor provides CyanVeil.Text, content = content) }
}

object StatusColors {
    val blocked get() = CyanVeil.Amber
    val working get() = CyanVeil.Cyan
    val done get() = CyanVeil.Mint
    val idle get() = CyanVeil.Muted

    fun of(status: String?): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.WORKING -> working
        AgentStatus.DONE -> done
        AgentStatus.IDLE -> idle
        else -> Color.Transparent
    }
}
