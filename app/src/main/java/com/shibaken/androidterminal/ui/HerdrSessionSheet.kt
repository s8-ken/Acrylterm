package com.shibaken.androidterminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.herdr.HerdrSessionPickerState
import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrSessionSheet(host: String, state: HerdrSessionPickerState, connectedName: String?, onRefresh: () -> Unit,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyanVeil.Background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.herdrui_session_title), style = MaterialTheme.typography.titleLarge)
                    Text(host, color = CyanVeil.Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading && state.switchingTo == null) { Text(stringResource(R.string.herdrui_refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.herdrui_close)) }
            }
            Text(stringResource(R.string.herdrui_session_hint), Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = CyanVeil.Muted)
            if (state.loading || state.switchingTo != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.switchingTo?.let { stringResource(R.string.herdrui_connecting_to, it) } ?: stringResource(R.string.herdrui_loading_sessions),
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Text(it, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh, enabled = !state.loading && state.switchingTo == null) { Text(stringResource(R.string.herdrui_retry)) }
            }
            if (!state.loading && state.error == null && state.sessions.isEmpty()) Text(stringResource(R.string.herdrui_no_sessions), Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(state.sessions, key = { it.name }) { entry ->
                    val current = entry.running && connectedName == entry.name
                    val runningLabel = stringResource(R.string.herdrui_running)
                    val stoppedLabel = stringResource(R.string.herdrui_stopped)
                    val connectedHere = stringResource(R.string.herdrui_connected_here)
                    val sessionDesc = stringResource(R.string.herdrui_session_desc, entry.name, if (entry.running) runningLabel else stoppedLabel, if (current) connectedHere else "")
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(enabled = entry.running && !current && !state.loading && state.switchingTo == null) { onSelect(entry.name) }
                        .semantics { contentDescription = sessionDesc }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(entry.name, color = if (entry.running) CyanVeil.Text else CyanVeil.Muted)
                            Text(if (entry.running) runningLabel else stoppedLabel, style = MaterialTheme.typography.labelSmall, color = CyanVeil.Muted)
                        }
                        if (current) Text(stringResource(R.string.herdrui_connected), style = MaterialTheme.typography.labelMedium, color = CyanVeil.Mint)
                    }
                    HorizontalDivider(color = CyanVeil.Border)
                }
            }
        }
    }
}
