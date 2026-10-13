package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrState
import kotlin.math.abs

/**
 * Tabs of the focused workspace as a bottom-reachable strip: ☰ (drawer) | tab chips | grid (overview) | edit (composer) | +.
 * Tap a chip to focus it; swiping the strip left/right moves to the next/previous tab, swiping it up opens
 * the overview. The overview icon carries a count badge while agents wait for input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrTabStrip(
    state: HerdrState,
    herdr: HerdrController,
    onOpenDrawer: () -> Unit,
    onOpenOverview: () -> Unit,
    onOpenComposer: () -> Unit,
) {
    val tabs = state.tabs.filter { it.workspaceId == state.focusedWorkspaceId }
    val listState = rememberLazyListState()
    val focusedIndex = tabs.indexOfFirst { it.tabId == state.focusedTabId }
    LaunchedEffect(state.focusedTabId, tabs.size) {
        if (focusedIndex >= 0) listState.animateScrollToItem(focusedIndex)
    }
    val tint = MaterialTheme.colorScheme.onSurface
    val swipeThreshold = LocalViewConfiguration.current.touchSlop * 6
    val currentHerdr = rememberUpdatedState(herdr)
    val currentOverview = rememberUpdatedState(onOpenOverview)

    Column {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(Unit) {
                // Observe only (no consume) so taps still work; act on release.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach {
                            dx += it.position.x - it.previousPosition.x
                            dy += it.position.y - it.previousPosition.y
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    if (abs(dy) > abs(dx) && dy <= -swipeThreshold) currentOverview.value()
                    else if (dx <= -swipeThreshold) currentHerdr.value.focusTabRelative(+1)
                    else if (dx >= swipeThreshold) currentHerdr.value.focusTabRelative(-1)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer, Modifier.size(40.dp)) { Icon(Icons.Filled.Menu, "ワークスペースを開く", tint = tint) }
        LazyRow(
            Modifier.weight(1f),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(tabs, key = { _, t -> t.tabId }) { _, tab ->
                val focused = tab.tabId == state.focusedTabId
                val blocked = state.panes.any { it.tabId == tab.tabId && it.agentStatus == AgentStatus.BLOCKED }
                val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .widthIn(min = 48.dp, max = 160.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                        .clickable { herdr.focusTab(tab.tabId) }
                        .semantics {
                            selected = focused
                            contentDescription = tab.title + if (blocked) "、入力待ち" else ""
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (blocked) {
                        // Text mark in addition to the colour so it is not colour-only.
                        Text("!", color = if (focused) fg else StatusColors.blocked, fontWeight = FontWeight.Bold)
                        Text(" ", color = fg)
                    }
                    Text(
                        tab.title,
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onOpenOverview, Modifier.size(40.dp)) {
            val n = state.blockedCount
            BadgedBox(badge = {
                if (n > 0) Badge(containerColor = StatusColors.blocked, contentColor = MaterialTheme.colorScheme.background) { Text("$n") }
            }) { Icon(Icons.Filled.GridView, "タブ一覧" + if (n > 0) "(入力待ち $n 件)" else "", tint = tint) }
        }
        IconButton(onClick = onOpenComposer, Modifier.size(40.dp)) { Icon(Icons.Filled.Edit, "メッセージを作成", tint = tint) }
        IconButton(onClick = { herdr.newTab() }, Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "新しいタブ", tint = tint) }
    }
    }
}
