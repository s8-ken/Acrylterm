package com.shibaken.androidterminal.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.data.AppearanceSettings
import com.shibaken.androidterminal.data.ColorPalette

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

/** Only this wallpaper layer is blurred. No text or Android terminal View is faded/blurred. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    blurred: Boolean = LocalAppearance.current.blurBackground,
    palette: ColorPalette = CyanVeil.palette,
) {
    Canvas(modifier.background(Color(palette.background)).then(if (blurred) Modifier.blur(24.dp) else Modifier)) {
        val stops = if (blurred) arrayOf(
            0f to Color(palette.background), .46f to Color(palette.aurora1), .74f to Color(palette.aurora2), 1f to Color(palette.aurora3),
        ) else arrayOf(
            0f to Color(palette.background), .54f to Color(palette.aurora1), .72f to Color(palette.aurora2), .90f to Color(palette.aurora3),
        )
        drawRect(Brush.linearGradient(*stops, start = Offset.Zero, end = Offset(size.width * 1.1f, size.height * .55f)))
    }
}

@Composable
fun VeilBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(CyanVeil.Background)) {
        AuroraBackground(Modifier.matchParentSize())
        content()
    }
}

@Composable
fun VeilCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Figma SVGs converted by Android's Svg2Vector; keep each asset's intrinsic geometry. */
@Composable
fun VeilIcon(@DrawableRes resource: Int, description: String?, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val vector = ImageVector.vectorResource(resource)
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(vector, description, Modifier.size(vector.defaultWidth, vector.defaultHeight), tint = tint)
    }
}

@Composable
fun VeilIconButton(@DrawableRes resource: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(onClick, modifier.size(48.dp), shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
        VeilIcon(resource, description)
    }
}

@Composable
fun StatusPill(label: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = color, fontSize = 11.sp)
    }
}

@Composable
fun SectionHeading(title: String, detail: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VeilPageHeader(title: String, onBack: () -> Unit, subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VeilIconButton(R.drawable.veil_back, "戻る", onBack)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = CyanVeil.Muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
