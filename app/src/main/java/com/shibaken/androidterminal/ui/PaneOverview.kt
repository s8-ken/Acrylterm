package com.shibaken.androidterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
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

/**
 * Full-screen overview of every tab of every workspace as cards with a miniature of the pane layout.
 * Tap a card to go there; swipe it up to close the tab (after a confirmation). Long-press enters a delete
 * mode with a close button on every card; tapping a card or Back leaves it. Back dismisses.
 */
@Composable
fun PaneOverview(state: HerdrState, herdr: HerdrController, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (editing) editing = false else onDismiss() })
    var closing by remember { mutableStateOf<TabInfo?>(null) }
    var previews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val latestState by rememberUpdatedState(state)
    // Poll visible text of every pane while the overview is shown (a few requests at a time).
    LaunchedEffect(Unit) {
        while (true) {
            val ids = latestState.panes.map { it.paneId }
            val gate = Semaphore(3)
            val got = coroutineScope {
                ids.map { id -> async { gate.withPermit { herdr.readPaneVisible(id) }?.let { id to it } } }.awaitAll()
            }.filterNotNull().toMap()
            if (got.isNotEmpty()) previews = previews + got
            delay(3_000)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow everything that children did not handle so nothing reaches the panes underneath.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "一覧を閉じる") }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.workspaces.forEach { ws ->
                val tabs = state.tabs.filter { it.workspaceId == ws.workspaceId }
                if (tabs.isEmpty()) return@forEach
                item(key = "ws-${ws.workspaceId}") {
                    Text(
                        ws.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ws.workspaceId == state.focusedWorkspaceId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                item(key = "tabs-${ws.workspaceId}") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(tabs, key = { it.tabId }) { tab ->
                            TabCard(
                                tab = tab, state = state, previews = previews,
                                focused = tab.tabId == state.focusedTabId,
                                editing = editing,
                                onClick = { if (editing) editing = false else { herdr.focusTab(tab.tabId); onDismiss() } },
                                onLongClick = { editing = true },
                                onClose = { closing = tab },
                            )
                        }
                    }
                }
            }
        }
    }

    closing?.let { tab ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("タブを閉じる") },
            text = { Text("「${tab.title}」を閉じます。実行中のプロセスは終了します。") },
            confirmButton = { TextButton(onClick = { herdr.closeTab(tab.tabId); closing = null }) { Text("閉じる") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("キャンセル") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(
    tab: TabInfo, state: HerdrState, previews: Map<String, String>, focused: Boolean, editing: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val panes = state.panes.filter { it.tabId == tab.tabId }
    val rects: Map<String, FractionRect> = state.layout(tab.tabId)?.normalizedPaneRects()?.takeIf { it.isNotEmpty() }
        ?: panes.withIndex().associate { (i, p) -> p.paneId to FractionRect(i.toFloat() / panes.size, 0f, 1f / panes.size, 1f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val closeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val desc = tab.title + listOf(AgentStatus.BLOCKED, AgentStatus.WORKING, AgentStatus.DONE)
        .filter { st -> panes.any { it.agentStatus == st } }
        .joinToString("") { "、" + AgentStatus.label(it) }

    Column(
        Modifier
            .width(132.dp)
            .offset { IntOffset(0, dragY.roundToInt()) }
            .alpha(1f - (-dragY / (closeThreshold * 3)).coerceIn(0f, 0.6f))
            .pointerInput(tab.tabId) {
                // Claims only upward vertical drags so horizontal scrolling and taps keep working.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over -> if (over < 0f) change.consume() }
                    if (drag != null) {
                        var total = 0f
                        verticalDrag(drag.id) { change ->
                            total += change.positionChange().y
                            dragY = min(0f, total)
                            change.consume()
                        }
                        dragY = 0f
                        if (total < -closeThreshold) onClose()
                    }
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
            .semantics {
                contentDescription = desc
                customActions = listOf(CustomAccessibilityAction("タブを閉じる") { onClose(); true })
            },
    ) {
        Box {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        ) {
            val pad = 4.dp
            val w = maxWidth - pad * 2
            val h = maxHeight - pad * 2
            rects.forEach { (id, r) ->
                val pane = panes.firstOrNull { it.paneId == id }
                MiniPane(
                    pane, previews[id],
                    Modifier
                        .offset(pad + w * r.x + 1.dp, pad + h * r.y + 1.dp)
                        .size(w * r.width - 2.dp, h * r.height - 2.dp),
                )
            }
        }
        if (editing) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "タブを閉じる" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onError) }
        }
        }
        Text(
            tab.title,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pane miniature: tinted by agent status plus a shape glyph (triangle = blocked, cycle = working, check = done). */
@Composable
private fun MiniPane(pane: PaneInfo?, text: String?, modifier: Modifier) {
    val status = pane?.agentStatus
    val color = StatusColors.of(status)
    val known = color != androidx.compose.ui.graphics.Color.Transparent
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (known) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (known) color else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
    ) {
        val shown = text?.trimEnd()?.lines()?.takeLast(40)?.joinToString("\n")
        if (!shown.isNullOrEmpty()) {
            Text(
                shown,
                // Measured at full height and pinned to the bottom so the box clips old lines, not recent ones.
                Modifier.align(Alignment.BottomStart).wrapContentHeight(Alignment.Bottom, unbounded = true).padding(2.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 4.sp, lineHeight = 4.5.sp,
                softWrap = false, overflow = TextOverflow.Clip,
            )
        }
        val icon = when (status) {
            AgentStatus.BLOCKED -> Icons.Filled.Warning
            AgentStatus.WORKING -> Icons.Filled.Autorenew
            AgentStatus.DONE -> Icons.Filled.Check
            else -> null
        }
        if (icon != null) Icon(icon, null, Modifier.align(Alignment.TopEnd).padding(1.dp).size(12.dp), tint = color)
    }
}
