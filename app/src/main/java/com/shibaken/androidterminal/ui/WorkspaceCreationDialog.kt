package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R

@Composable
internal fun WorkspaceCreationDialog(missingActiveWorkspace: Boolean = false, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (missingActiveWorkspace) stringResource(R.string.herdrui_no_active_workspace) else stringResource(R.string.herdrui_new_workspace)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (missingActiveWorkspace) stringResource(R.string.herdrui_create_missing_q) else stringResource(R.string.herdrui_create_ws_desc))
            OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.herdrui_name_optional_wide)) }, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onCreate(label) }) { Text(stringResource(R.string.herdrui_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.herdrui_cancel)) } })
}
