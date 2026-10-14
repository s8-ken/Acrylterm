package com.shibaken.androidterminal.ui

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.herdr.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chrome-like, stable two-column cards. Previews read output only and never focus or type into a pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneOverview(state: HerdrState, herdr: HerdrController, onDismiss: () -> Unit,
    localPreview: (String) -> String? = { null }, modifier: Modifier = Modifier) {
    val rawState by herdr.state.collectAsState()
    val bulkClosing by herdr.bulkClosing.collectAsState()
    val creationBusy by herdr.creationBusy.collectAsState()
    val busy = bulkClosing || creationBusy
    var overviewMenu by remember { mutableStateOf(false) }
    var newWorkspace by remember { mutableStateOf(false) }
    var worktreeFor by remember { mutableStateOf<String?>(null) }
    var closePlan by remember { mutableStateOf<BulkClosePlan?>(null) }
    var workspace by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var previews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val latestState by rememberUpdatedState(state)
    val latestLocalPreview by rememberUpdatedState(localPreview)
    val context = LocalContext.current
    val terminalFont = remember(context) { FontFamily(Typeface.createFromAsset(context.assets, "fonts/UDEVGothic-Regular.ttf")) }
    BackHandler { if (!busy) { if (expandedId != null) expandedId = null else onDismiss() } }
    LaunchedEffect(herdr) {
        while (true) {
            val panes = latestState.panes
            val local = panes.mapNotNull { p -> latestLocalPreview(p.paneId)?.let { p.paneId to it } }.toMap()
            previews = (local + previews).filterKeys { id -> panes.any { it.paneId == id } }
            val gate = Semaphore(3)
            val remote = coroutineScope {
                panes.map { p -> async {
                    (gate.withPermit { herdr.readPaneVisible(p.paneId) } ?: local[p.paneId])?.let { p.paneId to it }
                } }.awaitAll()
            }.filterNotNull().toMap()
            previews = previews + remote
            delay(3_000)
        }
    }
    LaunchedEffect(state.workspaces, workspace) {
        if (workspace != null && state.workspaces.none { it.workspaceId == workspace }) workspace = null
    }
    val tabs = state.tabs.filter { workspace == null || it.workspaceId == workspace }
    val waiting = tabs.count { tab -> state.panes.any { it.tabId == tab.tabId && it.agentStatus == AgentStatus.BLOCKED } }
    VeilBackdrop(modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(64.dp).background(CyanVeil.Background).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { herdr.newTab(workspace ?: state.focusedWorkspaceId); onDismiss() }, enabled = !busy, modifier = Modifier.height(48.dp)) {
                    VeilIcon(R.drawable.veil_plus, null)
                    Text("新しいタブ", Modifier.padding(start = 8.dp), color = CyanVeil.Text, fontSize = 16.sp)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { overviewMenu = true }, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "タブ一覧メニュー") }
                    DropdownMenu(overviewMenu, { overviewMenu = false }) {
                        DropdownMenuItem(text = { Text("新しいワークスペース") }, onClick = { overviewMenu = false; newWorkspace = true })
                        DropdownMenuItem(text = { Text("新しいワークツリー") }, enabled = (workspace ?: state.focusedWorkspaceId) != null,
                            onClick = { overviewMenu = false; worktreeFor = workspace ?: state.focusedWorkspaceId })
                        AGENT_KINDS.forEach { (kind, name) ->
                            DropdownMenuItem(text = { Text("$name を新しいタブで") }, onClick = {
                                overviewMenu = false; herdr.newAgentTab(kind, workspace ?: state.focusedWorkspaceId); onDismiss()
                            })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("すべてのタブを閉じる") },
                            enabled = rawState.tabs.isNotEmpty(), onClick = { overviewMenu = false; closePlan = rawState.bulkClosePlan(BulkCloseKind.TABS) })
                        DropdownMenuItem(text = { Text("すべてのワークスペースを閉じる") },
                            enabled = rawState.workspaces.isNotEmpty(), onClick = { overviewMenu = false; closePlan = rawState.bulkClosePlan(BulkCloseKind.WORKSPACES) })
                    }
                }
                IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.size(48.dp)) { VeilIcon(R.drawable.veil_close, "一覧を閉じる") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyRow(Modifier.fillMaxWidth().height(56.dp).background(CyanVeil.Background), contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { FilterChip(workspace == null, { workspace = null }, { Text("すべて · ${state.tabs.size}") }, shape = CircleShape) }
                items(state.workspaces, key = { it.workspaceId }) { ws ->
                    val selected = workspace == ws.workspaceId
                    // New tabs go to the filtered workspace, or to herdr's focused one when showing all.
                    val isTarget = ws.workspaceId == (workspace ?: state.focusedWorkspaceId)
                    FilterChip(selected, { workspace = ws.workspaceId },
                        modifier = Modifier.semantics {
                            contentDescription = ws.title
                            stateDescription = (if (selected) "絞り込み中" else "絞り込みなし") + if (isTarget) "、新しいタブの追加先" else ""
                        },
                        border = if (isTarget && !selected) BorderStroke(1.dp, CyanVeil.Cyan)
                            else FilterChipDefaults.filterChipBorder(enabled = true, selected = selected),
                        label = { Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isTarget) {
                                Box(Modifier.size(6.dp).background(CyanVeil.Cyan, CircleShape))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(ws.title, Modifier.widthIn(max = 132.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } }, shape = CircleShape)
                }
            }
            Row(Modifier.fillMaxWidth().height(40.dp).background(CyanVeil.Background).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${tabs.size} 個のタブ", Modifier.weight(1f), fontSize = 14.sp)
                if (waiting > 0) Text("! 入力待ち $waiting", fontSize = 11.sp, color = CyanVeil.Amber)
            }
            if (tabs.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("このワークスペースにはタブがありません", color = CyanVeil.Muted, fontSize = 12.sp)
            } else LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                items(tabs, key = { it.tabId }) { tab ->
                    TabCard(tab, state, previews, tab.tabId == state.focusedTabId, terminalFont,
                        onClick = { herdr.focusTab(tab.tabId); onDismiss() }, onPreview = { expandedId = tab.tabId },
                        onClose = { herdr.closeTabWithUndo(tab.tabId) }, enabled = !busy)
                }
            }
            Box(Modifier.fillMaxWidth().height(52.dp).background(CyanVeil.Background), contentAlignment = Alignment.Center) {
                Text("カードを横スワイプで閉じる", fontSize = 12.sp, color = CyanVeil.Muted)
            }
        }
    }
    if (newWorkspace) WorkspaceCreationDialog(onCreate = { label -> herdr.newWorkspace(label); newWorkspace = false; onDismiss() },
        onDismiss = { newWorkspace = false })
    worktreeFor?.let { id ->
        WorktreeDialog(id, state.workspace(id)?.title.orEmpty(), herdr, onDismiss = { worktreeFor = null }, onNavigated = onDismiss)
    }
    closePlan?.let { plan ->
        val closingTabs = plan.kind == BulkCloseKind.TABS
        AlertDialog(onDismissRequest = { closePlan = null },
            title = { Text(if (closingTabs) "すべてのタブを閉じる" else "すべてのワークスペースを閉じる") },
            text = { Text((if (closingTabs) "全ワークスペースの ${plan.tabCount} 個のタブ（${plan.paneCount} ペイン）を閉じます。"
                else "この接続の ${plan.workspaceCount} 個のワークスペース、${plan.tabCount} 個のタブ（${plan.paneCount} ペイン）を閉じます。") +
                "実行中のリモートプロセスは終了します。元に戻せません。表示フィルタにかかわらず全ワークスペースが対象です。") },
            confirmButton = { TextButton(onClick = { herdr.closeAllConfirmed(plan); closePlan = null }) { Text("すべて閉じる") } },
            dismissButton = { TextButton(onClick = { closePlan = null }) { Text("キャンセル") } })
    }
    val expanded = state.tabs.firstOrNull { it.tabId == expandedId }
    if (expanded != null) ModalBottomSheet(onDismissRequest = { expandedId = null }, containerColor = CyanVeil.Surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(expanded.title, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text("${state.workspace(expanded.workspaceId)?.title.orEmpty()} · ${state.panes.count { it.tabId == expanded.tabId }} ペイン",
                        fontSize = 12.sp, color = CyanVeil.Muted)
                }
                IconButton(onClick = { expandedId = null }, modifier = Modifier.size(48.dp)) { VeilIcon(R.drawable.veil_close, "プレビューを閉じる") }
            }
            TabPreview(expanded, state, previews, terminalFont, true, Modifier.weight(1f).fillMaxWidth())
            val status = tabStatus(state.panes.filter { it.tabId == expanded.tabId })
            Text(status.first, color = status.second, fontSize = 12.sp)
            Button(onClick = { herdr.focusTab(expanded.tabId); expandedId = null; onDismiss() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("このタブを開く") }
            Text("プレビュー中 · 表示中のタブは ${state.tabs.firstOrNull { it.tabId == state.focusedTabId }?.title.orEmpty()}", fontSize = 11.sp, color = CyanVeil.Muted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabCard(tab: TabInfo, state: HerdrState, previews: Map<String, String>, focused: Boolean, terminalFont: FontFamily,
    onClick: () -> Unit, onPreview: () -> Unit, onClose: () -> Unit, enabled: Boolean = true) {
    var dragX by remember(tab.tabId) { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val close by rememberUpdatedState(onClose)
    val status = tabStatus(state.panes.filter { it.tabId == tab.tabId })
    Column(Modifier.fillMaxWidth().height(246.dp).offset { IntOffset(dragX.roundToInt(), 0) }
        .clip(MaterialTheme.shapes.large).background(CyanVeil.Surface)
        .border(if (focused) 2.dp else 1.dp, if (focused) CyanVeil.Cyan else CyanVeil.Border, MaterialTheme.shapes.large)
        .pointerInput(tab.tabId, threshold, enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(onHorizontalDrag = { change, delta -> change.consume(); dragX += delta },
                onDragEnd = { val shouldClose = abs(dragX) >= threshold; dragX = 0f; if (shouldClose) close() }, onDragCancel = { dragX = 0f })
        }
        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onPreview)
        .semantics { selected = focused; contentDescription = "${tab.title}、${status.first}"
            customActions = if (enabled) listOf(CustomAccessibilityAction("タブを閉じる") { onClose(); true }, CustomAccessibilityAction("プレビューを拡大") { onPreview(); true }) else emptyList() }) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            VeilIcon(R.drawable.veil_terminal, null, CyanVeil.Cyan)
            Text(tab.title, Modifier.weight(1f).padding(start = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(48.dp)) { VeilIcon(R.drawable.veil_close, "${tab.title}を閉じる") }
        }
        TabPreview(tab, state, previews, terminalFont, false, Modifier.fillMaxWidth().height(142.dp).padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.first, fontSize = 11.sp, color = status.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.workspace(tab.workspaceId)?.title.orEmpty(), fontFamily = terminalFont, fontSize = 10.sp, color = CyanVeil.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPreview, enabled = enabled, modifier = Modifier.size(48.dp)) { VeilIcon(R.drawable.veil_expand, "${tab.title}のプレビューを拡大") }
        }
    }
}

@Composable
private fun TabPreview(tab: TabInfo, state: HerdrState, previews: Map<String, String>, font: FontFamily, expanded: Boolean, modifier: Modifier) {
    val panes = state.panes.filter { it.tabId == tab.tabId }
    val rects = state.layout(tab.tabId)?.normalizedPaneRects()?.takeIf { it.isNotEmpty() }
        ?: panes.withIndex().associate { (i, p) -> p.paneId to FractionRect(i.toFloat() / panes.size, 0f, 1f / panes.size, 1f) }
    BoxWithConstraints(modifier.clip(MaterialTheme.shapes.medium).background(CyanVeil.Background)) {
        val width = maxWidth; val height = maxHeight
        rects.forEach { (id, rect) ->
            val pane = panes.firstOrNull { it.paneId == id }
            Column(Modifier.offset(width * rect.x, height * rect.y)
                .size((width * rect.width).coerceAtLeast(1.dp), (height * rect.height).coerceAtLeast(1.dp))
                .padding(if (expanded) 16.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pane?.foregroundCwd ?: pane?.cwd ?: pane?.title2.orEmpty(), fontFamily = font,
                    fontSize = if (expanded) 11.sp else 8.sp, color = CyanVeil.Mint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(previews[id]?.trimEnd() ?: "プレビューを読み込み中…", color = if (expanded) CyanVeil.Text else CyanVeil.Muted, fontFamily = font,
                    fontSize = if (expanded) 12.sp else 8.sp, lineHeight = if (expanded) 19.sp else 12.sp, softWrap = false, overflow = TextOverflow.Clip)
            }
        }
    }
}

private fun tabStatus(panes: List<PaneInfo>) = when {
    panes.any { it.agentStatus == AgentStatus.BLOCKED } -> "! 入力待ち" to CyanVeil.Amber
    panes.any { it.agentStatus == AgentStatus.WORKING } -> "● 実行中" to CyanVeil.Cyan
    panes.any { it.agentStatus == AgentStatus.DONE } -> "✓ 完了" to CyanVeil.Mint
    else -> "● 待機中" to CyanVeil.Muted
}
