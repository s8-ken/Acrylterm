package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.shibaken.androidterminal.ext.ExtAgents
import com.shibaken.androidterminal.ext.ExtSummary
import com.shibaken.androidterminal.ext.ScriptStore
import com.shibaken.androidterminal.session.GenState
import com.shibaken.androidterminal.session.SessionExt

/** Ask the host's AI CLI to write (or modify) a host-scoped extension script. Generation continues after closing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExtGenerateSheet(ext: SessionExt, hostId: String, app: App, initialName: String = "", initialRequest: String = "", initialAgent: String = "", onLaunched: (Offset?) -> Unit = {}, onDismiss: () -> Unit) {
    val scriptStore = app.scriptStore
    val state by ext.genState.collectAsState()
    val revision by ext.loadRevision.collectAsState()
    val existing by produceState(emptyList<String>(), hostId, state, revision) {
        value = withContext(Dispatchers.IO) { scriptStore.listHost(hostId) }
    }
    var name by remember { mutableStateOf(initialName.removeSuffix(".lua")) }
    var request by remember { mutableStateOf(initialRequest) }
    val savedCmd = remember(hostId) { initialAgent.ifBlank { app.hosts.get(hostId)?.extAgentCommand ?: "claude -p" } }
    var kind by remember { mutableStateOf(ExtAgents.kindOf(savedCmd)) }
    var custom by remember { mutableStateOf(if (ExtAgents.kindOf(savedCmd) == ExtAgents.Kind.CUSTOM) savedCmd else "") }
    val agentCmd = (kind.command ?: custom).trim()
    val full = if (name.endsWith(".lua")) name else "$name.lua"
    val nameValid = name.isNotBlank() && ScriptStore.isValidName(full)
    val running = state is GenState.Running
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiScope = rememberCoroutineScope()
    var submitCenter by remember { mutableStateOf<Offset?>(null) }
    val modify = full in existing
    val currentSummary by produceState<ExtSummary?>(null, hostId, full, state, revision, existing) {
        value = withContext(Dispatchers.IO) {
            if (full in existing) scriptStore.readHost(hostId, full)?.let { ExtSummary.parse(it) } else null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CyanVeil.Surface, contentColor = CyanVeil.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = CyanVeil.Background.copy(alpha = .72f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = CyanVeil.Border) }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("拡張を作成", fontSize = 23.sp, fontWeight = FontWeight.Medium)
            val hostName = remember(hostId) { app.hosts.get(hostId)?.displayName }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("接続先ホストの AI エージェントが拡張を作成します" + (hostName?.let { "($it)" } ?: ""),
                    color = CyanVeil.Muted, fontSize = 12.sp)
                Text("保存先: ~/.config/androidterminal/", color = CyanVeil.Muted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
            val kinds = ExtAgents.Kind.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size), enabled = !running) { Text(k.label) }
                }
            }
            if (kind == ExtAgents.Kind.CUSTOM) {
                OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("コマンド") },
                    isError = custom.isBlank(), enabled = !running, modifier = Modifier.fillMaxWidth())
            }
            if (existing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                existing.forEach { n ->
                    FilterChip(selected = n == full, onClick = { name = n.removeSuffix(".lua") }, label = { Text(n) })
                }
            }
            currentSummary?.let { cur ->
                VeilCard(Modifier.fillMaxWidth()) { ExtSummaryContent(full, cur) }
            }
            OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text("ファイル名") },
                suffix = { if (!name.endsWith(".lua")) Text(".lua") }, isError = name.isNotBlank() && !nameValid,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(request, { request = it }, label = { Text("やりたいこと") }, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = {
                app.hosts.get(hostId)?.let { latest ->
                    if (latest.extAgentCommand != agentCmd) app.hosts.save(latest.copy(extAgentCommand = agentCmd))
                }
                ext.clearGenState(); ext.generate(full, request.trim(), agentCmd)
                onLaunched(submitCenter)
                uiScope.launch { sheetState.hide(); onDismiss() }
            },
                enabled = nameValid && request.isNotBlank() && agentCmd.isNotBlank() && !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onGloballyPositioned { submitCenter = it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f) }) { Text(if (modify) "修正を依頼" else "作成を依頼") }
            when (val s = state) {
                is GenState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanVeil.Cyan)
                    Text("ホストで生成中… 閉じても続きます", color = CyanVeil.Muted, fontSize = 13.sp)
                }
                is GenState.Done -> VeilCard(Modifier.fillMaxWidth()) { ExtSummaryContent(s.name, s.summary, done = true) }
                is GenState.Failed -> Text(s.message, color = CyanVeil.Rose, fontSize = 13.sp)
                GenState.Idle -> {}
            }
        }
    }
}

/** Summary line, usage bullets and detected-feature chips for a script (shared by the sheet and the result dialog). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtSummaryContent(fileName: String, s: ExtSummary, done: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (done) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = CyanVeil.Mint)
            Text(s.summary ?: fileName, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
        if (s.summary != null) Text(fileName, color = CyanVeil.Muted, fontSize = 12.sp)
        s.usage.forEach { Text("・$it", fontSize = 13.sp, color = CyanVeil.Text) }
        if (s.hasFeatures) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.keys.isNotEmpty()) StatusPill("キー: " + s.keys.joinToString(", "))
            if (s.commands.isNotEmpty()) StatusPill("コマンド: " + s.commands.joinToString(", "))
            if (s.triggers > 0) StatusPill("トリガー ×${s.triggers}", CyanVeil.Amber)
            if (s.quickReplies > 0) StatusPill("クイック返信 ×${s.quickReplies}", CyanVeil.Mint)
            if (s.timers > 0) StatusPill("タイマー ×${s.timers}", CyanVeil.Amber)
            if (s.events.isNotEmpty()) StatusPill("イベント: " + s.events.joinToString(", "), CyanVeil.Muted)
        }
    }
}

/** Dialog shown by tapping the edge badge once generation finished or failed. */
@Composable
fun ExtResultDialog(state: GenState, onClose: () -> Unit, onEdit: (String) -> Unit, onRetry: () -> Unit) {
    when (state) {
        is GenState.Done -> AlertDialog(onDismissRequest = onClose, containerColor = CyanVeil.Surface,
            title = { Text("拡張を作成しました") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ExtSummaryContent(state.name, state.summary, done = true) } },
            confirmButton = { TextButton(onClick = { onEdit(state.name) }) { Text("修正") } },
            dismissButton = { TextButton(onClick = onClose) { Text("閉じる") } })
        is GenState.Failed -> AlertDialog(onDismissRequest = onClose, containerColor = CyanVeil.Surface,
            title = { Text("作成に失敗しました") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(state.message, color = CyanVeil.Rose, fontSize = 14.sp)
            } },
            confirmButton = { TextButton(onClick = onRetry) { Text("再試行") } },
            dismissButton = { TextButton(onClick = onClose) { Text("閉じる") } })
        else -> {}
    }
}

/** Small floating round indicator of the generation state: spinning ring (running), check (done), warning (failed). */
@Composable
fun ExtActivityBadge(state: GenState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state is GenState.Idle) return
    val (tint, desc) = when (state) {
        is GenState.Running -> CyanVeil.Cyan to "拡張を作成中"
        is GenState.Done -> CyanVeil.Mint to "拡張を作成しました"
        else -> CyanVeil.Rose to "拡張の作成に失敗"
    }
    val pop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state::class) {
        when (state) {
            is GenState.Done -> {
                pop.animateTo(1.25f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
                pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            }
            is GenState.Failed -> {
                for (x in listOf(-8f, 8f, -6f, 6f, -3f, 0f)) shake.animateTo(x, tween(55))
            }
            else -> {}
        }
    }
    Box(modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; translationX = shake.value }.size(48.dp).clip(CircleShape).background(CyanVeil.Raised.copy(alpha = .94f))
        .border(1.dp, tint.copy(alpha = .6f), CircleShape).clickable(onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        when (state) {
            is GenState.Running -> {
                val t = rememberInfiniteTransition(label = "extGen")
                val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ring")
                val pulse by t.animateFloat(.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
                CircularProgressIndicator(progress = { .28f }, modifier = Modifier.size(36.dp).rotate(angle),
                    strokeWidth = 2.dp, color = CyanVeil.Cyan, trackColor = Color.Transparent)
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp).alpha(pulse), tint = CyanVeil.Cyan)
            }
            is GenState.Done -> {
                Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = tint)
                Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(7.dp).clip(CircleShape).background(CyanVeil.Mint))
            }
            else -> Icon(Icons.Filled.Warning, null, Modifier.size(22.dp), tint = tint)
        }
    }
}

/** Static look of the badge's glyph (circle + sparkle), used by the launch flight overlay. */
@Composable
fun ExtLaunchGlyph(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).background(CyanVeil.Raised.copy(alpha = .94f))
        .border(1.dp, CyanVeil.Cyan.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp), tint = CyanVeil.Cyan)
    }
}
