package com.shibaken.androidterminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.session.ConnState
import com.shibaken.androidterminal.session.SshTerminalSession

@Composable
fun HostListScreen(
    onConnect: (HostProfile) -> Unit,
    onOpenSession: (String) -> Unit,
    onEdit: (String?) -> Unit,
    onKeys: () -> Unit,
    onTailscale: () -> Unit,
    onAppearance: () -> Unit,
    onExtScripts: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val hosts by app.hosts.hosts.collectAsState()
    val appSettings by app.settings.settings.collectAsState()
    val sessions by app.sessions.sessions.collectAsState()
    val ts by app.tailscale.status.collectAsState()
    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), fontSize = 25.sp, fontWeight = FontWeight.Medium)
                    Text("YOUR PRIVATE CONNECTION", fontSize = 10.sp, letterSpacing = 1.sp, color = CyanVeil.Muted)
                }
                VeilIconButton(R.drawable.veil_tune, stringResource(R.string.settings_hostlist_settings), onAppearance)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (sessions.isNotEmpty()) {
                        item { SectionHeading(stringResource(R.string.settings_hostlist_connected_section), "${sessions.size} SESSION${if (sessions.size > 1) "S" else ""}") }
                        items(sessions, key = { "session-${it.id}" }) { session ->
                            SessionRow(session, onClick = { onOpenSession(session.id) })
                        }
                    }
                    item { SectionHeading(stringResource(R.string.settings_hostlist_saved_hosts), "${hosts.size} HOST${if (hosts.size != 1) "S" else ""}", Modifier.padding(top = 4.dp)) }
                    if (hosts.isEmpty()) {
                        item {
                            VeilCard(Modifier.fillMaxWidth()) {
                                Box(Modifier.size(48.dp).background(CyanVeil.Raised, CircleShape), contentAlignment = Alignment.Center) {
                                    VeilIcon(R.drawable.veil_terminal, null, CyanVeil.Cyan)
                                }
                                Text(stringResource(R.string.settings_hostlist_empty_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.settings_hostlist_empty_body), color = CyanVeil.Muted, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.settings_hostlist_empty_hint), color = CyanVeil.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                    items(hosts.sortedByDescending { it.lastConnectedAt }, key = { "host-${it.id}" }) { host ->
                        HostRow(host, onConnect = { onConnect(host) }, onEdit = { onEdit(host.id) })
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onTailscale, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                                VeilIcon(R.drawable.veil_network, null, if (ts.isConnected) CyanVeil.Mint else CyanVeil.Cyan)
                                Spacer(Modifier.width(8.dp)); Text("Tailscale", fontSize = 12.sp)
                            }
                            FilledTonalButton(onKeys, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                                VeilIcon(R.drawable.veil_key, null, CyanVeil.Cyan)
                                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_hostlist_ssh_keys), fontSize = 12.sp)
                            }
                        }
                    }
                    if (appSettings.extensionsEnabled) item {
                        FilledTonalButton(onExtScripts, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Code, null, tint = CyanVeil.Cyan)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_hostlist_extensions))
                        }
                    }
                }
                Button(onClick = { onEdit(null) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).heightIn(min = 56.dp), shape = CircleShape) {
                    VeilIcon(R.drawable.veil_plus, null, CyanVeil.Ink)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.settings_hostlist_add_host))
                }
            }
        }
    }
}

@Composable
internal fun HostRow(h: HostProfile, onConnect: () -> Unit, onEdit: () -> Unit) {
    Surface(onClick = onConnect, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = CyanVeil.Surface, border = BorderStroke(1.dp, CyanVeil.Border)) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VeilIcon(R.drawable.veil_terminal, null, CyanVeil.Cyan)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(h.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${h.username}@${h.host}:${h.port}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = CyanVeil.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildList { add(if (h.useTailscale) "Tailscale" else "SSH"); if (h.herdrEnabled) add(stringResource(R.string.settings_hostlist_multitab)); if (h.useMosh) add("Mosh") }.joinToString(" · "), fontSize = 10.sp, color = CyanVeil.Muted)
            }
            IconButton(onEdit) { VeilIcon(R.drawable.veil_edit, stringResource(R.string.settings_hostlist_edit_host, h.displayName), CyanVeil.Muted) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRow(s: SshTerminalSession, onClick: () -> Unit) {
    val state by s.state.collectAsState()
    val title by s.title.collectAsState()
    val herdr = s.herdr?.state?.collectAsState()?.value
    val (status, color) = when (state) {
        is ConnState.Connected -> stringResource(R.string.settings_hostlist_state_connected) to CyanVeil.Mint
        is ConnState.Connecting -> stringResource(R.string.settings_hostlist_state_connecting) to CyanVeil.Cyan
        is ConnState.Disconnected -> stringResource(R.string.settings_hostlist_state_disconnected) to CyanVeil.Rose
    }
    VeilCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(CyanVeil.Raised, CircleShape), contentAlignment = Alignment.Center) {
                VeilIcon(R.drawable.veil_terminal, null, CyanVeil.Cyan)
            }
            Column(Modifier.weight(1f)) {
                Text(s.profile.displayName, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title.takeUnless { it.isBlank() || it == s.profile.displayName } ?: "${s.profile.username} @ ${s.profile.host}",
                    fontSize = 12.sp, color = CyanVeil.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(status, color)
            if (s.profile.useTailscale) StatusPill("Tailscale")
            val blocked = herdr?.blockedCount ?: 0
            if (blocked > 0) StatusPill(stringResource(R.string.settings_hostlist_waiting_input, blocked), CyanVeil.Amber)
        }
        Button(onClick, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 52.dp)) { Text(stringResource(R.string.settings_hostlist_open_session)) }
    }
}
