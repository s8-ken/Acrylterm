package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import com.shibaken.androidterminal.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.ext.ExtSummary
import com.shibaken.androidterminal.ext.ScriptStore
import com.shibaken.androidterminal.session.ConnState
import com.shibaken.androidterminal.session.SessionExt
import com.shibaken.androidterminal.session.SshTerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extensions this session loads, with a per-host on/off switch and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningExtensionsSheet(ext: SessionExt, session: SshTerminalSession, app: App, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hosts by app.hosts.hosts.collectAsState()
    val profile = hosts.firstOrNull { it.id == session.profile.id } ?: session.profile
    val disabled = profile.extDisabled.toSet()
    val revision by ext.loadRevision.collectAsState()
    val errors by ext.loadErrors.collectAsState()
    val connState by session.state.collectAsState()
    val connected = connState is ConnState.Connected
    var entries by remember { mutableStateOf<List<ScriptStore.Entry>>(emptyList()) }
    LaunchedEffect(revision, profile.id, profile.extLoadFromHost) {
        entries = withContext(Dispatchers.IO) { app.scriptStore.entries(profile.id, profile.extLoadFromHost) }
            .filter { !it.duplicateOfGlobal }
    }
    // 有効な同梱テンプレート(アプリ全体の設定)。ホスト別の無効化とは別なので、ここの切り替えは全ホストに効く。
    val appSettings by app.settings.settings.collectAsState()
    val templateEntries = app.extTemplates.filter { it.fileName in appSettings.enabledTemplates }.map {
        ScriptStore.Entry(it.loadName, ScriptStore.Scope.GLOBAL, it.fileName, it.source)
    }
    var confirmDelete by remember { mutableStateOf<ScriptStore.Entry?>(null) }
    val hostEntries = entries.filter { it.scope == ScriptStore.Scope.HOST }
    val globalEntries = entries.filter { it.scope == ScriptStore.Scope.GLOBAL }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyanVeil.Surface, contentColor = CyanVeil.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = CyanVeil.Background.copy(alpha = .72f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = CyanVeil.Border) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(CyanVeil.Raised, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Code, null, tint = CyanVeil.Cyan)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ext_list_title), fontSize = 23.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.ext_toggle_only_host, profile.displayName), color = CyanVeil.Muted, fontSize = 11.sp)
                }
                StatusPill("${entries.count { it.loadName !in disabled } + templateEntries.size} / ${entries.size + templateEntries.size}")
            }
            if (entries.isEmpty() && templateEntries.isEmpty()) Text(stringResource(R.string.ext_none), color = CyanVeil.Muted, fontSize = 14.sp)
            if (profile.extLoadFromHost && hostEntries.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ext_section_host))
                hostEntries.forEach { e ->
                    ExtRow(e, e.loadName !in disabled, errors[e.loadName],
                        deleteEnabled = connected,
                        onToggle = { ext.setEnabled(e.loadName, it) }, onDelete = { confirmDelete = e })
                }
            }
            if (globalEntries.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ext_section_global))
                globalEntries.forEach { e ->
                    ExtRow(e, e.loadName !in disabled, errors[e.loadName],
                        deleteEnabled = true,
                        onToggle = { ext.setEnabled(e.loadName, it) }, onDelete = { confirmDelete = e })
                }
            }
            if (templateEntries.isNotEmpty()) {
                SectionLabel(stringResource(R.string.ext_section_templates))
                templateEntries.forEach { e ->
                    ExtRow(e, true, errors[e.loadName], deleteEnabled = false, showDelete = false,
                        onToggle = { on ->
                            app.settings.setTemplateEnabled(e.fileName, on)
                            app.sessions.reloadExtensions()
                        }, onDelete = {})
                }
            }
        }
    }

    confirmDelete?.let { e ->
        val host = e.scope == ScriptStore.Scope.HOST
        AlertDialog(onDismissRequest = { confirmDelete = null }, containerColor = CyanVeil.Surface,
            title = { Text(e.fileName, fontFamily = FontFamily.Monospace, fontSize = 18.sp) },
            text = { Text(if (host) stringResource(R.string.ext_delete_host_confirm, e.fileName) else stringResource(R.string.ext_delete_global_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        if (host) ext.deleteHostScript(e.fileName) else ext.deleteGlobalScript(e.fileName)
                    }
                }) { Text(stringResource(R.string.ext_delete), color = CyanVeil.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.ext_cancel)) } })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = CyanVeil.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ExtRow(
    e: ScriptStore.Entry,
    enabled: Boolean,
    error: String?,
    deleteEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean = true,
) {
    val summary = remember(e.source) { ExtSummary.parse(e.source) }
    var expanded by remember { mutableStateOf(false) }
    val toggleDesc = stringResource(R.string.ext_toggle_named, e.fileName)
    val line = summary.summary.orEmpty()
    VeilCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Code, null, Modifier.size(20.dp).alpha(if (enabled) 1f else .4f),
                    tint = if (error != null && enabled) CyanVeil.Rose else CyanVeil.Cyan)
                Column(Modifier.weight(1f).alpha(if (enabled) 1f else .5f)) {
                    Text(e.fileName, fontFamily = FontFamily.Monospace, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (summary.summary != null) Text(line, color = CyanVeil.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ExtTags(summary, Modifier.padding(top = 4.dp))
                }
                if (error != null && enabled) StatusPill(stringResource(R.string.ext_error), CyanVeil.Rose)
                Switch(enabled, onToggle, modifier = Modifier.semantics { contentDescription = toggleDesc })
                if (showDelete) IconButton(onClick = onDelete, enabled = deleteEnabled) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.ext_delete_named, e.fileName), tint = if (deleteEnabled) CyanVeil.Muted else CyanVeil.Border)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                if (error != null && enabled) Text(error, color = CyanVeil.Rose, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                ExtSummaryContent(e.fileName, summary)
            }
        }
    }
}
