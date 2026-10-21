package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.*
import com.shibaken.androidterminal.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.saveable.rememberSaveable
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.delay
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
            Text(stringResource(R.string.ext_gen_title), fontSize = 23.sp, fontWeight = FontWeight.Medium)
            val hostName = remember(hostId) { app.hosts.get(hostId)?.displayName }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (hostName != null) stringResource(R.string.ext_gen_desc_host, hostName) else stringResource(R.string.ext_gen_desc),
                    color = CyanVeil.Muted, fontSize = 12.sp)
                Text(stringResource(R.string.ext_gen_save_path), color = CyanVeil.Muted, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
            val kinds = ExtAgents.Kind.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(selected = kind == k, onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size), enabled = !running) { Text(if (k == ExtAgents.Kind.CUSTOM) stringResource(R.string.ext_agent_other) else k.label) }
                }
            }
            if (kind == ExtAgents.Kind.CUSTOM) {
                OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text(stringResource(R.string.ext_command)) },
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
            OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.ext_file_name)) },
                suffix = { if (!name.endsWith(".lua")) Text(".lua") }, isError = name.isNotBlank() && !nameValid,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(request, { request = it }, label = { Text(stringResource(R.string.ext_request_label)) },
                placeholder = { Text(stringResource(R.string.ext_request_hint), color = CyanVeil.Muted) }, minLines = 3, maxLines = 8,
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onGloballyPositioned { submitCenter = it.positionInWindow() + Offset(it.size.width / 2f, it.size.height / 2f) }) { Text(if (modify) stringResource(R.string.ext_request_modify) else stringResource(R.string.ext_request_create)) }
            when (val s = state) {
                is GenState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanVeil.Cyan)
                    Text(stringResource(R.string.ext_generating), color = CyanVeil.Muted, fontSize = 13.sp)
                }
                is GenState.Done -> VeilCard(Modifier.fillMaxWidth()) { ExtSummaryContent(s.name, s.summary, done = true) }
                is GenState.Failed -> Text(s.message, color = CyanVeil.Rose, fontSize = 13.sp)
                GenState.Idle -> {}
            }
        }
    }
}

/** Summary line, usage bullets and detected-feature chips for a script (shared by the sheet and the result dialog). */
/** スクリプトが提供するものを示す小さなタグ(⚡マクロ N / 自動処理 / 返信候補)。何も無ければ描画しない。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtTags(s: ExtSummary, modifier: Modifier = Modifier) {
    if (s.macroCount == 0 && !s.hasAutomation && !s.hasQuickReplies) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.macroCount > 0) StatusPill(stringResource(R.string.ext_tag_macro, s.macroCount), CyanVeil.Cyan)
        if (s.hasAutomation) StatusPill(stringResource(R.string.ext_tag_automation), CyanVeil.Amber)
        if (s.hasQuickReplies) StatusPill(stringResource(R.string.ext_tag_quick_replies), CyanVeil.Mint)
    }
}

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
            if (s.keys.isNotEmpty()) StatusPill(stringResource(R.string.ext_pill_macro, s.keys.joinToString(", ")))
            if (s.commands.isNotEmpty()) StatusPill(stringResource(R.string.ext_pill_macro, s.commands.joinToString(", ")))
            if (s.triggers > 0) StatusPill(stringResource(R.string.ext_pill_trigger, s.triggers), CyanVeil.Amber)
            if (s.quickReplies > 0) StatusPill(stringResource(R.string.ext_pill_quick_replies, s.quickReplies), CyanVeil.Mint)
            if (s.timers > 0) StatusPill(stringResource(R.string.ext_pill_timer, s.timers), CyanVeil.Amber)
            if (s.events.isNotEmpty()) StatusPill(stringResource(R.string.ext_pill_events, s.events.joinToString(", ")), CyanVeil.Muted)
        }
    }
}

/** Dialog shown by tapping the edge badge once generation finished or failed. */
@Composable
fun ExtResultDialog(state: GenState, onClose: () -> Unit, onEdit: (String) -> Unit, onRetry: () -> Unit) {
    when (state) {
        is GenState.Done -> AlertDialog(onDismissRequest = onClose, containerColor = CyanVeil.Surface,
            title = { Text(stringResource(R.string.ext_gen_done_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { ExtSummaryContent(state.name, state.summary, done = true) } },
            confirmButton = { TextButton(onClick = { onEdit(state.name) }) { Text(stringResource(R.string.ext_modify)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ext_close)) } })
        is GenState.Failed -> AlertDialog(onDismissRequest = onClose, containerColor = CyanVeil.Surface,
            title = { Text(stringResource(R.string.ext_result_failed_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(state.message, color = CyanVeil.Rose, fontSize = 14.sp)
            } },
            confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.ext_retry)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ext_close)) } })
        else -> {}
    }
}

/**
 * Small floating round indicator of the generation state. Running = outline (spinning ring + sparkle),
 * done = filled mint with check, breathing glow and unread dot, failed = filled rose "!".
 * On first appearance it is born with an overshoot pop, two expanding rings and a haptic tick (once per generation).
 */
@Composable
fun ExtActivityBadge(state: GenState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (state is GenState.Idle) return
    val desc = when (state) {
        is GenState.Running -> stringResource(R.string.ext_badge_running)
        is GenState.Done -> stringResource(R.string.ext_badge_done)
        else -> stringResource(R.string.ext_gen_failed_title)
    }
    // Saved so rotation / recomposition of the same generation does not replay the birth.
    var born by rememberSaveable { mutableStateOf(false) }
    val birth = remember { Animatable(if (born) 1f else 0f) }
    val ring1 = remember { Animatable(if (born) 1f else 0f) }
    val ring2 = remember { Animatable(if (born) 1f else 0f) }
    val pop = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (born) return@LaunchedEffect
        born = true
        view.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        launch { ring1.animateTo(1f, tween(600, easing = LinearEasing)) }
        launch { delay(120); ring2.animateTo(1f, tween(600, easing = LinearEasing)) }
        birth.animateTo(1.25f, tween(160))
        birth.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }
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
    val breathe = rememberInfiniteTransition(label = "extBreathe")
    val glow by breathe.animateFloat(.15f, .45f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "glow")
    val done = state is GenState.Done
    // Outer box is deliberately not clipped: rings and glow are drawn beyond its 48dp bounds.
    Box(modifier.size(48.dp).drawBehind {
        val r = size.minDimension / 2f
        for (a in listOf(ring1, ring2)) {
            val p = a.value
            if (p > 0f && p < 1f) drawCircle(CyanVeil.Cyan.copy(alpha = .6f * (1f - p)), radius = r * (1f + 1.2f * p), style = Stroke(2.dp.toPx()))
        }
        if (done) drawCircle(CyanVeil.Mint.copy(alpha = glow), radius = r * 1.4f)
    }, contentAlignment = Alignment.Center) {
        val fill = when (state) { is GenState.Done -> CyanVeil.Mint; is GenState.Running -> CyanVeil.Background.copy(alpha = .85f); else -> CyanVeil.Rose }
        val edge = if (state is GenState.Running) CyanVeil.Cyan.copy(alpha = .7f) else Color.Transparent
        Box(Modifier.fillMaxSize().graphicsLayer { val sc = birth.value * pop.value; scaleX = sc; scaleY = sc; translationX = shake.value }
            .clip(CircleShape).background(fill).border(1.dp, edge, CircleShape).clickable(onClick = onClick)
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
                is GenState.Done -> Icon(Icons.Filled.Check, null, Modifier.size(26.dp), tint = CyanVeil.Ink)
                else -> Text("!", color = CyanVeil.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (done) {
            Box(Modifier.align(Alignment.TopEnd).graphicsLayer { val sc = birth.value * pop.value; scaleX = sc; scaleY = sc }
                .size(13.dp).clip(CircleShape).background(CyanVeil.Amber).border(2.dp, CyanVeil.Background, CircleShape))
        }
    }
}

/** Static look of the badge's glyph (outline circle + sparkle), used by the launch flight overlay. */
@Composable
fun ExtLaunchGlyph(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).background(CyanVeil.Background.copy(alpha = .85f))
        .border(1.dp, CyanVeil.Cyan.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp), tint = CyanVeil.Cyan)
    }
}
