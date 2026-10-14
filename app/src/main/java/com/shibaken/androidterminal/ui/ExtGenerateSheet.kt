package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.ext.ScriptStore
import com.shibaken.androidterminal.session.GenState
import com.shibaken.androidterminal.session.SessionExt

/** Ask the host's AI CLI to write (or modify) a host-scoped extension script. Generation continues after closing. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExtGenerateSheet(ext: SessionExt, hostId: String, scriptStore: ScriptStore, onDismiss: () -> Unit) {
    val state by ext.genState.collectAsState()
    val existing = remember(hostId, state) { scriptStore.listHost(hostId) }
    var name by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    val full = if (name.endsWith(".lua")) name else "$name.lua"
    val nameValid = name.isNotBlank() && ScriptStore.isValidName(full)
    val running = state is GenState.Running
    val modify = full in existing

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyanVeil.Surface, contentColor = CyanVeil.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = CyanVeil.Background.copy(alpha = .72f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = CyanVeil.Border) }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("拡張を作成", fontSize = 23.sp, fontWeight = FontWeight.Medium)
            if (existing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                existing.forEach { n ->
                    FilterChip(selected = n == full, onClick = { name = n.removeSuffix(".lua") }, label = { Text(n) })
                }
            }
            OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text("ファイル名") },
                suffix = { if (!name.endsWith(".lua")) Text(".lua") }, isError = name.isNotBlank() && !nameValid,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(request, { request = it }, label = { Text("やりたいこと") }, minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { ext.clearGenState(); ext.generate(full, request.trim()) },
                enabled = nameValid && request.isNotBlank() && !running,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (modify) "修正を依頼" else "作成を依頼") }
            when (val s = state) {
                is GenState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanVeil.Cyan)
                    Text("ホストで生成中… 閉じても続きます", color = CyanVeil.Muted, fontSize = 13.sp)
                }
                is GenState.Done -> Text("反映しました: ${s.name}", color = CyanVeil.Mint, fontSize = 13.sp)
                is GenState.Failed -> Text(s.message, color = CyanVeil.Rose, fontSize = 13.sp)
                GenState.Idle -> {}
            }
        }
    }
}
