package com.shibaken.androidterminal.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.data.AppearanceSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val repository = app.appearance
    val appSettings by app.settings.settings.collectAsState()
    val saved by repository.settings.collectAsState()
    var transparency by rememberSaveable { mutableIntStateOf(saved.transparencyPercent) }
    var blurred by rememberSaveable { mutableStateOf(saved.blurBackground) }
    val draft = AppearanceSettings(transparency, blurred)
    CompositionLocalProvider(LocalAppearance provides draft) {
        VeilBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VeilIconButton(R.drawable.veil_back, "戻る", onBack)
                    Column {
                        Text("設定", fontSize = 23.sp, fontWeight = FontWeight.Medium)
                        Text("自分に合う、夜のターミナル。", fontSize = 11.sp, color = CyanVeil.Muted)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppearancePreview(draft)
                    VeilCard(Modifier.fillMaxWidth()) {
                        SectionHeading("カラープロファイル", "選択中")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Cyan Veil", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            listOf(CyanVeil.Background, CyanVeil.Cyan, CyanVeil.Mint, CyanVeil.Amber, CyanVeil.Rose).forEach {
                                Box(Modifier.size(18.dp).background(it, CircleShape))
                            }
                        }
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("背景の透明度", fontWeight = FontWeight.Medium)
                            Text("$transparency%", fontSize = 24.sp, color = CyanVeil.Cyan, fontWeight = FontWeight.Medium)
                        }
                        Slider(transparency.toFloat(), { transparency = it.roundToInt() }, valueRange = 0f..100f,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "背景の透明度" },
                            thumb = { Box(Modifier.size(24.dp).background(CyanVeil.Cyan, CircleShape).border(5.dp, CyanVeil.Ink, CircleShape)) },
                            track = {
                                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                                    val y = size.height / 2
                                    drawLine(CyanVeil.Raised, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                                    if (transparency > 0) drawLine(CyanVeil.Cyan, Offset(0f, y), Offset(size.width * transparency / 100f, y), size.height, StrokeCap.Round)
                                }
                            })
                        SectionHeading("0%  不透明", "100%  透明")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (value in listOf(0, 28, 55)) {
                                FilterChip(selected = transparency == value, onClick = { transparency = value },
                                    label = { Text(if (value == 28) "28% おすすめ" else "$value%", fontSize = 11.sp) },
                                    shape = CircleShape, modifier = Modifier.weight(if (value == 28) 1.7f else 1f).heightIn(min = 48.dp))
                            }
                        }
                        Text("文字・アイコンの濃さは変わりません", fontSize = 11.sp, color = CyanVeil.Muted)
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("背景をぼかす", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("壁紙の輪郭をやわらかく", fontSize = 11.sp, color = CyanVeil.Muted)
                            }
                            Switch(blurred, { blurred = it }, modifier = Modifier.semantics { contentDescription = "背景をぼかす" })
                        }
                    }
                    TextButton(onClick = { transparency = 28; blurred = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("おすすめの設定に戻す")
                    }
                    VeilCard(Modifier.fillMaxWidth()) {
                        SectionHeading("高度な機能", "")
                        SwitchRow("拡張機能 (Lua)", "補助キー・コマンド・マクロを Lua で追加", appSettings.extensionsEnabled) {
                            app.settings.setExtensionsEnabled(it)
                        }
                    }
                }
                Button(onClick = { repository.save(draft); onBack() }, shape = CircleShape,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp).heightIn(min = 52.dp)) {
                    Text("この外観を適用", Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
internal fun AppearancePreview(settings: AppearanceSettings, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(136.dp).clip(MaterialTheme.shapes.large)) {
        AuroraBackground(Modifier.matchParentSize(), settings.blurBackground)
        Column(Modifier.padding(16.dp).fillMaxSize().clip(MaterialTheme.shapes.medium)
            .background(CyanVeil.Background.copy(alpha = settings.backgroundOpacity)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("LIVE PREVIEW", fontSize = 9.sp, color = CyanVeil.Cyan, letterSpacing = 1.sp)
            Text("❯ ssh mac-mini\nConnected. Ready when you are.", color = CyanVeil.Text,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}
