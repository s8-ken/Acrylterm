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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrSessionSheet(host: String, state: HerdrSessionPickerState, connectedName: String?, onRefresh: () -> Unit,
    onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyanVeil.Background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("herdr セッション", style = MaterialTheme.typography.titleLarge)
                    Text(host, color = CyanVeil.Muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading && state.switchingTo == null) { Text("更新") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
            Text("同じホストで稼働中のセッションへ接続します。", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = CyanVeil.Muted)
            if (state.loading || state.switchingTo != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.switchingTo?.let { "$it に接続中…" } ?: "セッション一覧を取得中…",
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let {
                Text(it, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh, enabled = !state.loading && state.switchingTo == null) { Text("再試行") }
            }
            if (!state.loading && state.error == null && state.sessions.isEmpty()) Text("セッションがありません", Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(state.sessions, key = { it.name }) { entry ->
                    val current = entry.running && connectedName == entry.name
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(enabled = entry.running && !current && !state.loading && state.switchingTo == null) { onSelect(entry.name) }
                        .semantics { contentDescription = "herdr セッション ${entry.name}、${if (entry.running) "稼働中" else "停止中"}${if (current) "、このアプリで接続中" else ""}" }
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(entry.name, color = if (entry.running) CyanVeil.Text else CyanVeil.Muted)
                            Text(if (entry.running) "稼働中" else "停止中", style = MaterialTheme.typography.labelSmall, color = CyanVeil.Muted)
                        }
                        if (current) Text("接続中", style = MaterialTheme.typography.labelMedium, color = CyanVeil.Mint)
                    }
                    HorizontalDivider(color = CyanVeil.Border)
                }
            }
        }
    }
}
