package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp

@Composable
internal fun WorkspaceCreationDialog(missingActiveWorkspace: Boolean = false, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (missingActiveWorkspace) "アクティブなワークスペースがありません" else "新しいワークスペース") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (missingActiveWorkspace) "新規作成しますか？最初のタブも作成します。" else "最初のタブを含むワークスペースを作成します。")
            OutlinedTextField(label, { label = it }, label = { Text("名前（任意）") }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onCreate(label) }) { Text("作成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}
