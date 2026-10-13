package com.shibaken.androidterminal.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.herdr.AgentStatus

object CyanVeil {
    val Background = Color(0xFF071216)
    val Surface = Color(0xFF102329)
    val Raised = Color(0xFF173239)
    val Cyan = Color(0xFF64F4E5)
    val Mint = Color(0xFF9CF6C9)
    val Amber = Color(0xFFFFD18A)
    val Rose = Color(0xFFFF8497)
    val Text = Color(0xFFE4F7F6)
    val Muted = Color(0xFF96B2B8)
    val Border = Color(0xFF2B4B52)
    val Ink = Color(0xFF052629)
}

private val Scheme = darkColorScheme(
    primary = CyanVeil.Cyan, onPrimary = CyanVeil.Ink,
    primaryContainer = CyanVeil.Raised, onPrimaryContainer = CyanVeil.Cyan,
    secondary = CyanVeil.Mint, onSecondary = CyanVeil.Ink,
    secondaryContainer = CyanVeil.Raised, onSecondaryContainer = CyanVeil.Mint,
    tertiary = CyanVeil.Amber, onTertiary = CyanVeil.Ink,
    tertiaryContainer = CyanVeil.Raised, onTertiaryContainer = CyanVeil.Amber,
    background = CyanVeil.Background, onBackground = CyanVeil.Text,
    surface = CyanVeil.Surface, onSurface = CyanVeil.Text,
    surfaceVariant = CyanVeil.Raised, onSurfaceVariant = CyanVeil.Muted,
    surfaceContainer = CyanVeil.Surface,
    surfaceDim = CyanVeil.Background, surfaceBright = CyanVeil.Raised,
    surfaceContainerLowest = CyanVeil.Background, surfaceContainerLow = CyanVeil.Surface,
    surfaceContainerHigh = CyanVeil.Raised,
    surfaceContainerHighest = Color(0xFF204049),
    outline = CyanVeil.Muted, outlineVariant = CyanVeil.Border,
    error = CyanVeil.Rose, onError = CyanVeil.Ink,
    errorContainer = Color(0xFF432B34), onErrorContainer = CyanVeil.Rose,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
    )) { CompositionLocalProvider(LocalContentColor provides CyanVeil.Text, content = content) }
}

object StatusColors {
    val blocked = CyanVeil.Amber
    val working = CyanVeil.Cyan
    val done = CyanVeil.Mint
    val idle = CyanVeil.Muted

    fun of(status: String?): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.WORKING -> working
        AgentStatus.DONE -> done
        AgentStatus.IDLE -> idle
        else -> Color.Transparent
    }
}
