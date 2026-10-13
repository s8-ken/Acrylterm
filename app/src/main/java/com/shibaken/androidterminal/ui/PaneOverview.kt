package com.shibaken.androidterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.font.FontWeight
import com.shibaken.androidterminal.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.FractionRect
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.herdr.PaneInfo
import com.shibaken.androidterminal.herdr.TabInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.min
import kotlin.math.roundToInt

/** Overview cards keep their live pane previews and explicit close confirmation. */
@Composable
fun PaneOverview(state: HerdrState, herdr: HerdrController, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var workspace by remember { mutableStateOf<String?>(state.focusedWorkspaceId) }
    BackHandler(onBack = { if (editing) editing = false else onDismiss() })
    var closing by remember { mutableStateOf<TabInfo?>(null) }
    var previews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        while (true) {
            val gate = Semaphore(3)
            val got = coroutineScope {
                latestState.panes.map { p -> async { gate.withPermit { herdr.readPaneVisible(p.paneId) }?.let { p.paneId to it } } }.awaitAll()
            }.filterNotNull().toMap()
            if (got.isNotEmpty()) previews = previews + got
            delay(3_000)
        }
    }
    // If the selected workspace disappears, show all remaining tabs.
    LaunchedEffect(state.workspaces, workspace) {
        if (workspace != null && state.workspaces.none { it.workspaceId == workspace }) workspace = null
    }
    val tabs = state.tabs.filter { workspace == null || it.workspaceId == workspace }
    val waiting = tabs.filter { t -> state.panes.any { it.tabId == t.tabId && it.agentStatus == AgentStatus.BLOCKED } }
    VeilBackdrop(modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("タブとペイン", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text("${state.tabs.size} TABS / ${state.panes.size} PANES", fontSize = 10.sp, color = CyanVeil.Muted, fontFamily = FontFamily.Monospace)
                }
                VeilIconButton(R.drawable.veil_close, "一覧を閉じる", onDismiss)
            }
            LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(workspace == null, { workspace = null }, { Text("すべて") }, shape = CircleShape) }
                items(state.workspaces, key = { it.workspaceId }) { ws ->
                    FilterChip(workspace == ws.workspaceId, { workspace = ws.workspaceId }, { Text(ws.title, maxLines = 1) }, shape = CircleShape)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                for ((title, group) in listOf("入力待ちのタブ" to waiting, "ほかのタブ" to tabs.filterNot { it in waiting })) {
                    if (group.isEmpty()) continue
                    item(key = "section-$title") { SectionHeading(title, "${group.size} 件") }
                    items(group, key = { it.tabId }) { tab ->
                        TabCard(tab, state, previews, tab.tabId == state.focusedTabId, editing,
                            onClick = { if (editing) editing = false else { herdr.focusTab(tab.tabId); onDismiss() } },
                            onLongClick = { editing = true }, onClose = { closing = tab })
                    }
                }
                if (tabs.isEmpty()) item { Text("このワークスペースにはタブがありません", color = CyanVeil.Muted) }
                item { Text(if (editing) "閉じるボタン、またはカードを上へスワイプ" else "カードをタップして開く · 長押しで閉じる操作", fontSize = 11.sp, color = CyanVeil.Muted) }
            }
            Button(onClick = { herdr.newTab(workspace ?: state.focusedWorkspaceId); onDismiss() }, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).height(52.dp)) {
                VeilIcon(R.drawable.veil_plus, null, CyanVeil.Ink)
                Text("新しいタブ", Modifier.padding(start = 8.dp))
            }
        }
    }
    closing?.let { tab ->
        AlertDialog(onDismissRequest = { closing = null }, title = { Text("タブを閉じる") },
            text = { Text("「${tab.title}」を閉じます。実行中のプロセスは終了します。") },
            confirmButton = { TextButton(onClick = { herdr.closeTab(tab.tabId); closing = null }) { Text("閉じる") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("キャンセル") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabCard(
    tab: TabInfo, state: HerdrState, previews: Map<String, String>, focused: Boolean, editing: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val panes = state.panes.filter { it.tabId == tab.tabId }
    val rects = state.layout(tab.tabId)?.normalizedPaneRects()?.takeIf { it.isNotEmpty() }
        ?: panes.withIndex().associate { (i, p) -> p.paneId to FractionRect(i.toFloat() / panes.size, 0f, 1f / panes.size, 1f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val blocked = panes.count { it.agentStatus == AgentStatus.BLOCKED }
    val status = when {
        blocked > 0 -> "入力待ち $blocked" to CyanVeil.Amber
        panes.any { it.agentStatus == AgentStatus.WORKING } -> "実行中" to CyanVeil.Cyan
        panes.any { it.agentStatus == AgentStatus.DONE } -> "完了" to CyanVeil.Mint
        else -> "待機中" to CyanVeil.Muted
    }
    Column(Modifier.fillMaxWidth().offset { IntOffset(0, dragY.roundToInt()) }
        .clip(MaterialTheme.shapes.large).background(CyanVeil.Surface)
        .border(1.dp, if (focused) CyanVeil.Cyan else CyanVeil.Border, MaterialTheme.shapes.large)
        .pointerInput(tab.tabId, editing) {
            // Scrolling the vertical list must never close a tab. Swipe-to-close is enabled only in edit mode.
            if (!editing) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> if (over < 0f) change.consume() }
                if (drag != null) {
                    var total = 0f
                    verticalDrag(drag.id) { change -> total += change.positionChange().y; dragY = min(0f, total); change.consume() }
                    dragY = 0f
                    if (total < -threshold) onClose()
                }
            }
        }
        .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
        .semantics { contentDescription = "${tab.title}、${status.first}"; customActions = listOf(CustomAccessibilityAction("タブを閉じる") { onClose(); true }) }
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (editing) VeilIconButton(R.drawable.veil_close, "${tab.title}を閉じる", onClose)
            else StatusPill(status.first, status.second)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(116.dp)) {
            val width = maxWidth; val height = maxHeight
            rects.forEach { (id, r) ->
                MiniPane(panes.firstOrNull { it.paneId == id }, previews[id], Modifier
                    .offset(width * r.x + 2.dp, height * r.y + 2.dp)
                    .size((width * r.width - 4.dp).coerceAtLeast(1.dp), (height * r.height - 4.dp).coerceAtLeast(1.dp)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${panes.size} ペイン", fontSize = 11.sp, color = CyanVeil.Muted)
            Text("タップで開く", fontSize = 11.sp, color = CyanVeil.Cyan)
        }
    }
}

@Composable
private fun MiniPane(pane: PaneInfo?, text: String?, modifier: Modifier) {
    val color = StatusColors.of(pane?.agentStatus).takeUnless { it == androidx.compose.ui.graphics.Color.Transparent } ?: CyanVeil.Muted
    Column(modifier.clip(MaterialTheme.shapes.medium).background(CyanVeil.Background).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(pane?.title2.orEmpty(), color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val shown = text?.trimEnd()?.lines()?.takeLast(16)?.joinToString("\n")
        Text(shown ?: "プレビューを読み込み中…", color = CyanVeil.Muted, fontFamily = FontFamily.Monospace,
            fontSize = 8.sp, lineHeight = 12.sp, softWrap = false, overflow = TextOverflow.Clip)
    }
}
