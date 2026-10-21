package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.shibaken.androidterminal.herdr.TabInfo
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

/** B2: one quiet 56dp row. Title dragging only scrolls; it never changes the active tab. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HerdrTabStrip(state: HerdrState, herdr: HerdrController, onOpenOverview: () -> Unit, menu: @Composable () -> Unit) {
    val tabs = state.tabs.filter { it.workspaceId == state.focusedWorkspaceId }
    val listState = rememberLazyListState()
    var renaming by remember { mutableStateOf<TabInfo?>(null) }
    var newTabMenu by remember { mutableStateOf(false) }
    val newTabLabel = stringResource(R.string.herdrui_new_tab)
    val chooseTypeLabel = stringResource(R.string.herdrui_choose_tab_type)
    val renameLabel = stringResource(R.string.herdrui_rename)
    val waitingSuffix = stringResource(R.string.herdrui_waiting_suffix)
    val overviewDesc = pluralStringResource(R.plurals.herdrui_tab_overview_desc, state.tabs.size, state.tabs.size)
    LaunchedEffect(state.focusedTabId, tabs.map { it.tabId }) {
        val index = tabs.indexOfFirst { it.tabId == state.focusedTabId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val titleViewport = (maxWidth - 156.dp).coerceAtLeast(48.dp)
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(Modifier.weight(1f).height(48.dp), state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items(tabs, key = { it.tabId }) { tab ->
                val focused = tab.tabId == state.focusedTabId
                val blocked = state.panes.any { it.tabId == tab.tabId && it.agentStatus == AgentStatus.BLOCKED }
                Box(Modifier.height(48.dp).drawBehind {
                    if (focused) drawLine(CyanVeil.Cyan, Offset(12.dp.toPx(), size.height - 2.dp.toPx()),
                        Offset((size.width - 12.dp.toPx()).coerceAtLeast(12.dp.toPx()), size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                }) {
                    Row(Modifier.height(48.dp).combinedClickable(onLongClick = { renaming = tab }) { herdr.focusTab(tab.tabId) }.semantics {
                        customActions = listOf(CustomAccessibilityAction(renameLabel) { renaming = tab; true })
                        selected = focused
                        contentDescription = tab.title + if (blocked) waitingSuffix else ""
                    }.padding(start = 12.dp, end = if (focused) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.title, Modifier.widthIn(min = if (focused) 36.dp else 24.dp, max = if (focused) (titleViewport - 60.dp - if (blocked) 20.dp else 0.dp).coerceIn(36.dp, 132.dp) else 132.dp), color = if (focused) CyanVeil.Cyan else CyanVeil.Muted,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (blocked) Text("!", Modifier.padding(start = 8.dp), color = CyanVeil.Amber, fontSize = 12.sp)
                        if (focused) IconButton(onClick = { herdr.closeTabWithUndo(tab.tabId) }, modifier = Modifier.size(48.dp)) {
                            VeilIcon(R.drawable.veil_close, stringResource(R.string.herdrui_close_named, tab.title))
                        }
                    }
                }
            }
        }
        // Tap = shell tab, long-press = shell / agent menu.
        Box(Modifier.size(48.dp).combinedClickable(onLongClick = { newTabMenu = true }) { herdr.newTab() }.semantics {
            contentDescription = newTabLabel
            customActions = listOf(CustomAccessibilityAction(chooseTypeLabel) { newTabMenu = true; true })
        }, contentAlignment = Alignment.Center) {
            VeilIcon(R.drawable.veil_plus, null)
            NewTabMenu(newTabMenu, { newTabMenu = false }, herdr, state.focusedWorkspaceId)
        }
        IconButton(onClick = onOpenOverview, modifier = Modifier.size(48.dp).semantics { contentDescription = overviewDesc }) {
            Box(Modifier.size(24.dp, 26.dp).border(1.7.dp, CyanVeil.Text, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(state.tabs.size.toString(), color = CyanVeil.Text, fontSize = 14.sp)
            }
        }
        menu()
    }
    }
    renaming?.let { t -> RenameDialog(stringResource(R.string.herdrui_rename_tab_title), t.label, { herdr.renameTab(t.tabId, it) }, { renaming = null }) }
}
