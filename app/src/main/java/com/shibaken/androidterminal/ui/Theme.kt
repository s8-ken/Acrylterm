package com.shibaken.androidterminal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.shibaken.androidterminal.herdr.AgentStatus

private val Scheme = darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF0B1220),
    secondary = Color(0xFF7EE787),
    onSecondary = Color(0xFF07140A),
    background = Color(0xFF101216),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161A20),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF9DA7B3),
    surfaceContainer = Color(0xFF181C22),
    surfaceContainerHigh = Color(0xFF1F242B),
    outline = Color(0xFF30363D),
    error = Color(0xFFFF7B72),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

object StatusColors {
    val blocked = Color(0xFFFFA657)
    val working = Color(0xFF58A6FF)
    val done = Color(0xFF7EE787)
    val idle = Color(0xFF8B949E)

    fun of(status: String?): Color = when (status) {
        AgentStatus.BLOCKED -> blocked
        AgentStatus.WORKING -> working
        AgentStatus.DONE -> done
        AgentStatus.IDLE -> idle
        else -> Color.Transparent
    }
}
