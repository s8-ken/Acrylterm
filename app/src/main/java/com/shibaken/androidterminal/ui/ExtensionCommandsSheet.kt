package com.shibaken.androidterminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.ext.ExtCommand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionCommandsSheet(commands: List<ExtCommand>, onRun: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CyanVeil.Surface, contentColor = CyanVeil.Text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), scrimColor = CyanVeil.Background.copy(alpha = .72f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = CyanVeil.Border) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).background(CyanVeil.Raised, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Code, null, tint = CyanVeil.Cyan)
                }
                Column(Modifier.weight(1f)) {
                    Text("拡張コマンド", fontSize = 23.sp, fontWeight = FontWeight.Medium)
                    Text("スクリプトに登録した操作を実行", color = CyanVeil.Muted, fontSize = 11.sp)
                }
                StatusPill("${commands.size} 件")
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (commands.isEmpty()) item {
                    Text("登録されたコマンドはありません", Modifier.padding(vertical = 12.dp), color = CyanVeil.Muted)
                }
                items(commands, key = { it.name }) { command ->
                    Surface(onClick = { onRun(command.name) }, modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium, color = CyanVeil.Raised, border = BorderStroke(1.dp, CyanVeil.Border)) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(command.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                            Text("実行", fontSize = 12.sp, color = CyanVeil.Cyan)
                        }
                    }
                }
            }
        }
    }
}
