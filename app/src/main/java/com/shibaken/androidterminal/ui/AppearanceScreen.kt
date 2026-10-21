package com.shibaken.androidterminal.ui

import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.shibaken.androidterminal.data.AppearanceSettings
import com.shibaken.androidterminal.data.ColorPalette
import com.shibaken.androidterminal.data.ColorProfiles
import com.shibaken.androidterminal.data.ColorSelection
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val repository = app.appearance
    val appSettings by app.settings.settings.collectAsState()
    val saved by repository.settings.collectAsState()
    var transparency by rememberSaveable { mutableIntStateOf(saved.transparencyPercent) }
    var blurred by rememberSaveable { mutableStateOf(saved.blurBackground) }
    var colorCode by rememberSaveable { mutableStateOf(saved.colors.encode()) }
    val colors = ColorSelection.decode(colorCode)
    val draft = AppearanceSettings(transparency, blurred, colors)
    var editing by remember { mutableStateOf<ColorRole?>(null) }
    val descTransparency = stringResource(R.string.settings_appearance_transparency)
    val descBlur = stringResource(R.string.settings_appearance_blur)
    CompositionLocalProvider(LocalAppearance provides draft) {
        VeilBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VeilIconButton(R.drawable.veil_back, stringResource(R.string.settings_back), onBack)
                    Text(stringResource(R.string.settings_appearance_title), fontSize = 23.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppearancePreview(draft)
                    VeilCard(Modifier.fillMaxWidth()) {
                        SectionHeading(stringResource(R.string.settings_appearance_color_profile), "")
                        for (preset in ColorProfiles.presets) {
                            ProfileTile(preset.name, preset.palette, colors.id == preset.id) {
                                colorCode = ColorSelection(preset.id, preset.id).encode()
                            }
                        }
                        ProfileTile(stringResource(R.string.settings_appearance_custom), colors.resolve(), colors.isCustom) { colorCode = colors.toCustom().encode() }
                        if (colors.isCustom) {
                            val p = colors.resolve()
                            for (role in ColorRole.values()) {
                                ColorRow(stringResource(role.labelRes), role.of(p)) { editing = role }
                            }
                        }
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_appearance_transparency), fontWeight = FontWeight.Medium)
                            Text("$transparency%", fontSize = 24.sp, color = CyanVeil.Cyan, fontWeight = FontWeight.Medium)
                        }
                        Slider(transparency.toFloat(), { transparency = it.roundToInt() }, valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = descTransparency },
                            thumb = { Box(Modifier.size(24.dp).background(CyanVeil.Cyan, CircleShape).border(5.dp, CyanVeil.Ink, CircleShape)) },
                            track = {
                                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                                    val y = size.height / 2
                                    drawLine(CyanVeil.Raised, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                                    if (transparency > 0) drawLine(CyanVeil.Cyan, Offset(0f, y), Offset(size.width * transparency / 100f, y), size.height, StrokeCap.Round)
                                }
                            })
                        SectionHeading(stringResource(R.string.settings_appearance_opaque), stringResource(R.string.settings_appearance_transparent))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (value in listOf(0, 28, 55)) {
                                FilterChip(selected = transparency == value, onClick = { transparency = value },
                                    label = { Text(if (value == 28) stringResource(R.string.settings_appearance_recommended_28) else "$value%", fontSize = 11.sp) },
                                    shape = CircleShape, modifier = Modifier.weight(if (value == 28) 1.7f else 1f).heightIn(min = 48.dp))
                            }
                        }
                        Text(stringResource(R.string.settings_appearance_text_unchanged), fontSize = 11.sp, color = CyanVeil.Muted)
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_appearance_blur), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.settings_appearance_blur_hint), fontSize = 11.sp, color = CyanVeil.Muted)
                            }
                            Switch(blurred, { blurred = it }, modifier = Modifier.semantics { contentDescription = descBlur })
                        }
                    }
                    TextButton(onClick = { transparency = 28; blurred = true; colorCode = ColorSelection().encode() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.settings_appearance_reset))
                    }
                    Button(onClick = { app.applyAppearance(draft); onBack() }, shape = CircleShape,
                        modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.settings_appearance_apply), Modifier.padding(horizontal = 12.dp))
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        SectionHeading(stringResource(R.string.settings_appearance_advanced), "")
                        SwitchRow(stringResource(R.string.settings_appearance_ext_title), stringResource(R.string.settings_appearance_ext_sub), appSettings.extensionsEnabled) {
                            app.settings.setExtensionsEnabled(it)
                        }
                    }
                    // Android 13+ has a per-app language page (fed by res/xml/locales_config.xml); older versions follow the system language.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        val context = LocalContext.current
                        VeilCard(Modifier.fillMaxWidth().clickable {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null)))
                        }) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.settings_language), fontWeight = FontWeight.Medium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val locale = LocalConfiguration.current.locales[0]
                                    Text(locale.getDisplayLanguage(locale), color = CyanVeil.Muted)
                                    Text("  ›", fontSize = 20.sp, color = CyanVeil.Muted)
                                }
                            }
                        }
                    }
                    VeilCard(Modifier.fillMaxWidth().clickable(onClick = onLicenses)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_licenses), fontWeight = FontWeight.Medium)
                            Text("›", fontSize = 20.sp, color = CyanVeil.Muted)
                        }
                    }
                }
            }
        }
    }
    editing?.let { role ->
        ColorPickerDialog(stringResource(role.labelRes), role.of(colors.resolve()), { editing = null }) { picked ->
            colorCode = role.set(colors.toCustom(), picked).encode()
            editing = null
        }
    }
}

@Composable
internal fun AppearancePreview(settings: AppearanceSettings, modifier: Modifier = Modifier) {
    val p = settings.colors.resolve()
    Box(modifier.fillMaxWidth().height(136.dp).clip(MaterialTheme.shapes.large)) {
        AuroraBackground(Modifier.matchParentSize(), settings.blurBackground, p)
        Column(Modifier.padding(16.dp).fillMaxSize().clip(MaterialTheme.shapes.medium)
            .background(Color(p.background).copy(alpha = settings.backgroundOpacity)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Color(p.accent), CircleShape))
                Text("LIVE PREVIEW", fontSize = 9.sp, color = Color(p.accent), letterSpacing = 1.sp)
            }
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = Color(p.accent))) { append("❯ ") }
                withStyle(SpanStyle(color = Color(p.terminalForeground))) { append("ssh mac-mini\n") }
                withStyle(SpanStyle(color = Color(p.ansi[2]))) { append("Connected. ") }
                withStyle(SpanStyle(color = Color(p.ansi[3]))) { append("Ready ") }
                withStyle(SpanStyle(color = Color(p.ansi[4]))) { append("when ") }
                withStyle(SpanStyle(color = Color(p.ansi[5]))) { append("you ") }
                withStyle(SpanStyle(color = Color(p.ansi[1]))) { append("are.") }
            }, color = Color(p.text), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}

private enum class ColorRole(@StringRes val labelRes: Int) {
    Background(R.string.settings_appearance_role_background), Accent(R.string.settings_appearance_role_accent),
    Text(R.string.settings_appearance_role_text), Secondary(R.string.settings_appearance_role_secondary);

    fun of(p: ColorPalette): Color = Color(when (this) {
        Background -> p.background; Accent -> p.accent; Text -> p.text; Secondary -> p.secondary
    })

    fun set(sel: ColorSelection, color: Color): ColorSelection {
        val v = color.toArgb()
        return when (this) {
            Background -> sel.copy(background = v); Accent -> sel.copy(accent = v)
            Text -> sel.copy(text = v); Secondary -> sel.copy(secondary = v)
        }
    }
}

@Composable
private fun ProfileTile(name: String, p: ColorPalette, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
        .background(Color(p.background))
        .border(if (selected) 2.dp else 1.dp, if (selected) CyanVeil.Cyan else CyanVeil.Border, shape)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f), color = Color(p.text), fontWeight = FontWeight.Medium)
        for (c in listOf(p.accent, p.secondary, p.warning, p.error)) Box(Modifier.size(16.dp).background(Color(c), CircleShape))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) Text("✓", color = Color(p.accent), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ColorRow(label: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp)
        Box(Modifier.size(32.dp).background(color, CircleShape).border(1.dp, CyanVeil.Border, CircleShape))
    }
}

@Composable
private fun ColorPickerDialog(title: String, initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val descHue = stringResource(R.string.settings_appearance_hue)
    val descSat = stringResource(R.string.settings_appearance_saturation)
    val descVal = stringResource(R.string.settings_appearance_brightness)
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    var hex by remember { mutableStateOf(ColorProfiles.toHex(current.toArgb())) }
    fun syncHex() { hex = ColorProfiles.toHex(Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))).toArgb()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp).background(current, MaterialTheme.shapes.small)
                    .border(1.dp, CyanVeil.Border, MaterialTheme.shapes.small))
                Slider(h, { h = it; syncHex() }, valueRange = 0f..360f, modifier = Modifier.semantics { contentDescription = descHue })
                Slider(s, { s = it; syncHex() }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = descSat })
                Slider(v, { v = it; syncHex() }, valueRange = 0f..1f, modifier = Modifier.semantics { contentDescription = descVal })
                OutlinedTextField(hex, {
                    hex = it
                    ColorProfiles.parseHex(it)?.let { argb ->
                        val out = FloatArray(3)
                        android.graphics.Color.colorToHSV(argb, out)
                        h = out[0]; s = out[1]; v = out[2]
                    }
                }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
            }
        },
        confirmButton = { TextButton({ onPick(current) }) { Text(stringResource(R.string.settings_appearance_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.settings_cancel)) } })
}
