package com.shibaken.androidterminal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.ext.ScriptStore

/** Global Lua scripts: list / add / edit / delete. Saving reloads every live session that has extensions on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtScriptsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val store = app.scriptStore
    var names by remember { mutableStateOf(store.listGlobal()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    editing?.let { name ->
        ScriptEditor(
            name = name,
            initial = store.readGlobal(name) ?: "",
            onSave = { text ->
                store.writeGlobal(name, text)
                app.sessions.reloadExtensions()
                names = store.listGlobal()
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拡張スクリプト") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "スクリプトを追加") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (names.isEmpty()) item { Text("右下の + で追加", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(names, key = { it }) { n ->
                Row(Modifier.fillMaxWidth().clickable { editing = n }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f).padding(vertical = 12.dp), fontFamily = FontFamily.Monospace)
                    IconButton(onClick = { deleting = n }) { Icon(Icons.Filled.Delete, "削除") }
                }
            }
        }
    }

    if (adding) {
        var newName by remember { mutableStateOf("") }
        val full = if (newName.endsWith(".lua")) newName else "$newName.lua"
        val valid = newName.isNotBlank() && ScriptStore.isValidName(full) && full !in names
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("新しいスクリプト") },
            text = {
                OutlinedTextField(
                    newName, { newName = it.trim() }, singleLine = true, label = { Text("名前") }, suffix = { Text(".lua") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    store.writeGlobal(full, "")
                    names = store.listGlobal()
                    adding = false
                    editing = full
                }) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("キャンセル") } },
        )
    }

    deleting?.let { n ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("削除") },
            text = { Text("$n を削除します。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteGlobal(n)
                    app.sessions.reloadExtensions()
                    names = store.listGlobal()
                    deleting = null
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptEditor(name: String, initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember(name) { mutableStateOf(initial) }
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
                actions = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(8.dp)) {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
    }
}
