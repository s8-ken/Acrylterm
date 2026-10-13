package com.shibaken.androidterminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.session.ConnState
import com.shibaken.androidterminal.session.SshTerminalSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    onConnect: (HostProfile) -> Unit,
    onOpenSession: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onKeys: () -> Unit,
    onTailscale: () -> Unit,
    onExtScripts: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.hosts.hosts.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val ts by app.tailscale.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("androidTerminal") },
                actions = {
                    IconButton(onClick = onTailscale) {
                        Box {
                            Icon(Icons.Filled.Lan, contentDescription = "Tailscale")
                            val dot = when {
                                !app.tailscale.available -> Color.Transparent
                                ts.isConnected -> StatusColors.done
                                ts.running -> StatusColors.blocked
                                else -> StatusColors.idle
                            }
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).background(dot)
                                    .align(Alignment.TopEnd)
                            )
                        }
                    }
                    IconButton(onClick = onKeys) { Icon(Icons.Filled.Key, contentDescription = "SSH 鍵") }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "メニュー") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text("拡張スクリプト") }, onClick = { more = false; onExtScripts() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, contentDescription = "ホストを追加") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sessions.isNotEmpty()) {
                item { SectionLabel("接続中") }
                items(sessions, key = { it.id }) { s -> SessionRow(s, onClick = { onOpenSession(s.id) }) }
                item { Spacer(Modifier.size(8.dp)) }
            }
            item { SectionLabel("ホスト") }
            if (hosts.isEmpty()) {
                item {
                    Text(
                        "右下の + から Mac mini を登録してください。\nTailscale 経由なら、ホスト名は MagicDNS 名(例: mac-mini)か 100.x.y.z の IP です。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(hosts.sortedByDescending { it.lastConnectedAt }, key = { it.id }) { h ->
                HostRow(h, onConnect = { onConnect(h) }, onEdit = { onEdit(h.id) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HostRow(h: HostProfile, onConnect: () -> Unit, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onConnect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(h.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val tags = buildList {
                    if (h.useTailscale) add("Tailscale")
                    if (h.herdrEnabled) add("マルチタブ")
                }.joinToString(" · ")
                Text(
                    "${h.username}@${h.host}:${h.port}" + if (tags.isNotEmpty()) "  $tags" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "編集") }
        }
    }
}

@Composable
private fun SessionRow(s: SshTerminalSession, onClick: () -> Unit) {
    val state by s.state.collectAsState()
    val title by s.title.collectAsState()
    val herdr = s.herdr?.state?.collectAsState()?.value
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = when (state) {
                is ConnState.Connected -> StatusColors.done
                is ConnState.Connecting -> StatusColors.working
                is ConnState.Disconnected -> MaterialTheme.colorScheme.error
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val blocked = herdr?.blockedCount ?: 0
            if (blocked > 0) {
                Text("入力待ち $blocked", color = StatusColors.blocked, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
