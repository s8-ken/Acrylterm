package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** B2: one quiet 56dp row. Title dragging only scrolls; it never changes the active tab. */
@Composable
fun HerdrTabStrip(state: HerdrState, herdr: HerdrController, onOpenOverview: () -> Unit, menu: @Composable () -> Unit) {
    val tabs = state.tabs.filter { it.workspaceId == state.focusedWorkspaceId }
    val listState = rememberLazyListState()
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
                    Row(Modifier.height(48.dp).clickable { herdr.focusTab(tab.tabId) }.semantics {
                        selected = focused
                        contentDescription = tab.title + if (blocked) "、入力待ち" else ""
                    }.padding(start = 12.dp, end = if (focused) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tab.title, Modifier.widthIn(min = if (focused) 36.dp else 24.dp, max = if (focused) (titleViewport - 60.dp - if (blocked) 20.dp else 0.dp).coerceIn(36.dp, 132.dp) else 132.dp), color = if (focused) CyanVeil.Cyan else CyanVeil.Muted,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (blocked) Text("!", Modifier.padding(start = 8.dp), color = CyanVeil.Amber, fontSize = 12.sp)
                        if (focused) IconButton(onClick = { herdr.closeTabWithUndo(tab.tabId) }, modifier = Modifier.size(48.dp)) {
                            VeilIcon(R.drawable.veil_close, "${tab.title}を閉じる")
                        }
                    }
                }
            }
        }
        IconButton(onClick = { herdr.newTab() }, modifier = Modifier.size(48.dp)) { VeilIcon(R.drawable.veil_plus, "新しいタブ") }
        IconButton(onClick = onOpenOverview, modifier = Modifier.size(48.dp).semantics { contentDescription = "タブ一覧、${state.tabs.size}個のタブ" }) {
            Box(Modifier.size(24.dp, 26.dp).border(1.7.dp, CyanVeil.Text, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(state.tabs.size.toString(), color = CyanVeil.Text, fontSize = 14.sp)
            }
        }
        menu()
    }
    }
}
