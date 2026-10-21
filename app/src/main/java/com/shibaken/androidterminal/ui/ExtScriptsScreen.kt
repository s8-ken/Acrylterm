package com.shibaken.androidterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.widget.Toast
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.ext.ExtSummary
import com.shibaken.androidterminal.ext.ExtTemplates
import com.shibaken.androidterminal.ext.ScriptStore

/** Global Lua scripts. Saving reloads every live session that has extensions enabled. */
@Composable
fun ExtScriptsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val store = app.scriptStore
    var names by remember { mutableStateOf(store.listGlobal()) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    // "hostId/name" (host ids never contain '/'); viewing is read-only, importing drives the import dialogs.
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by rememberSaveable { mutableStateOf<String?>(null) }
    // テンプレートの閲覧(ファイル名)。読み取り専用で、「コピーして編集」で全体スクリプトに複製する。
    var viewingTemplate by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val appSettings by app.settings.settings.collectAsState()
    val hosts by app.hosts.hosts.collectAsState()
    val hostScripts = hosts.mapNotNull { h ->
        val list = runCatching { store.listHost(h.id) }.getOrDefault(emptyList())
        if (list.isEmpty()) null else h to list
    }

    viewingTemplate?.let { file ->
        val t = app.extTemplates.firstOrNull { it.fileName == file }
        if (t == null) { viewingTemplate = null } else {
            ScriptEditor(stringResource(R.string.ext_template_title, file), t.source, onSave = {}, onCancel = { viewingTemplate = null }, readOnly = true,
                extraAction = stringResource(R.string.ext_copy_and_edit) to {
                    val copy = ExtTemplates.copyName(file, store.listGlobal())
                    store.writeGlobal(copy, t.source)
                    // 二重に動かさないよう、コピー元のテンプレートは off にする。
                    app.settings.setTemplateEnabled(file, false)
                    app.sessions.reloadExtensions()
                    names = store.listGlobal()
                    viewingTemplate = null
                    editing = copy
                })
            return
        }
    }

    viewing?.let { key ->
        val hostId = key.substringBefore('/')
        val name = key.substringAfter('/')
        val label = (hosts.firstOrNull { it.id == hostId }?.displayName ?: hostId) + " / " + name
        ScriptEditor(label, runCatching { store.readHost(hostId, name) }.getOrNull() ?: "", onSave = {},
            onCancel = { viewing = null }, readOnly = true)
        return
    }

    editing?.let { name ->
        ScriptEditor(name, store.readGlobal(name) ?: "", onSave = { text ->
            store.writeGlobal(name, text)
            app.sessions.reloadExtensions()
            names = store.listGlobal()
            editing = null
        }, onCancel = { editing = null })
        return
    }

    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            VeilPageHeader(stringResource(R.string.ext_scripts_title), onBack, stringResource(R.string.ext_scripts_subtitle))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    VeilCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).background(CyanVeil.Raised, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Code, null, tint = CyanVeil.Cyan)
                            }
                            Column {
                                Text(stringResource(R.string.ext_common_scripts), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("LUA EXTENSIONS", color = CyanVeil.Cyan, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                        }
                        Text(stringResource(R.string.ext_common_desc), color = CyanVeil.Muted, fontSize = 13.sp)
                        Text(stringResource(R.string.ext_common_note_enable),
                            color = CyanVeil.Muted, fontSize = 11.sp)
                        Text(stringResource(R.string.ext_common_note_import), color = CyanVeil.Muted, fontSize = 11.sp)
                    }
                }
                item { SectionHeading(stringResource(R.string.ext_saved_scripts), countItems(context, names.size), Modifier.padding(top = 4.dp)) }
                if (names.isEmpty()) item {
                    VeilCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ext_add_first), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.ext_add_first_hint), color = CyanVeil.Muted, fontSize = 13.sp)
                    }
                }
                items(names, key = { it }) { name ->
                    Surface(onClick = { editing = name }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        color = CyanVeil.Surface, border = BorderStroke(1.dp, CyanVeil.Border)) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.Code, null, tint = CyanVeil.Cyan)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val tags = remember(name, names) { store.readGlobal(name)?.let { ExtSummary.parse(it) } }
                                if (tags != null) ExtTags(tags)
                                Text(stringResource(R.string.ext_tap_to_edit), fontSize = 11.sp, color = CyanVeil.Muted)
                            }
                            IconButton(onClick = { deleting = name }) {
                                Icon(Icons.Filled.DeleteOutline, stringResource(R.string.ext_delete_named, name), tint = CyanVeil.Muted)
                            }
                        }
                    }
                }
                item { SectionHeading(stringResource(R.string.ext_templates), countItems(context, app.extTemplates.size), Modifier.padding(top = 4.dp)) }
                item {
                    Text(stringResource(R.string.ext_templates_hint),
                        color = CyanVeil.Muted, fontSize = 11.sp)
                }
                items(app.extTemplates, key = { "t:${it.fileName}" }) { t ->
                    val on = t.fileName in appSettings.enabledTemplates
                    val toggleDesc = stringResource(R.string.ext_toggle_named, t.fileName)
                    val tags = remember(t.fileName) { ExtSummary.parse(t.source) }
                    Surface(onClick = { viewingTemplate = t.fileName }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        color = CyanVeil.Surface, border = BorderStroke(1.dp, CyanVeil.Border)) {
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.Code, null, tint = if (on) CyanVeil.Cyan else CyanVeil.Muted)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tags.summary ?: t.fileName, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                ExtTags(tags)
                                Text(t.fileName, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyanVeil.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(on, { v ->
                                app.settings.setTemplateEnabled(t.fileName, v)
                                app.sessions.reloadExtensions()
                            }, modifier = Modifier.semantics { contentDescription = toggleDesc })
                        }
                    }
                }
                for ((host, list) in hostScripts) {
                    item(key = "hh:${host.id}") { SectionHeading(stringResource(R.string.ext_host_section, host.displayName), countItems(context, list.size), Modifier.padding(top = 4.dp)) }
                    items(list, key = { "h:${host.id}/$it" }) { name ->
                        Surface(onClick = { viewing = "${host.id}/$name" }, modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large, color = CyanVeil.Surface, border = BorderStroke(1.dp, CyanVeil.Border)) {
                            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Filled.Code, null, tint = CyanVeil.Muted)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(name, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.ext_synced_from_host), fontSize = 11.sp, color = CyanVeil.Muted)
                                }
                                IconButton(onClick = {
                                    val key = "${host.id}/$name"
                                    if (name in names) importing = key
                                    else if (runCatching { store.importHostToGlobal(host.id, name) }.getOrDefault(false)) {
                                        names = store.listGlobal()
                                        app.sessions.reloadExtensions()
                                        Toast.makeText(context, context.getString(R.string.ext_imported, name), Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Filled.Download, stringResource(R.string.ext_import_to_global, name), tint = CyanVeil.Cyan)
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = { adding = true }, shape = CircleShape,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp).heightIn(min = 52.dp)) {
                VeilIcon(R.drawable.veil_plus, null, CyanVeil.Ink)
                Text(stringResource(R.string.ext_add_script), Modifier.padding(start = 8.dp))
            }
        }
    }

    if (adding) {
        var newName by rememberSaveable { mutableStateOf("") }
        val full = if (newName.endsWith(".lua")) newName else "$newName.lua"
        val valid = newName.isNotBlank() && ScriptStore.isValidName(full) && full !in names
        AlertDialog(onDismissRequest = { adding = false }, title = { Text(stringResource(R.string.ext_new_script)) }, text = {
            OutlinedTextField(newName, { newName = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.ext_file_name)) },
                suffix = { if (!newName.endsWith(".lua")) Text(".lua") },
                supportingText = { Text(if (full in names) stringResource(R.string.ext_name_duplicate) else stringResource(R.string.ext_name_allowed)) },
                isError = newName.isNotEmpty() && !valid, shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }, confirmButton = {
            TextButton(enabled = valid, onClick = {
                store.writeGlobal(full, "")
                names = store.listGlobal()
                adding = false
                editing = full
            }) { Text(stringResource(R.string.ext_create)) }
        }, dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.ext_cancel)) } })
    }
    importing?.let { key ->
        val hostId = key.substringBefore('/')
        val name = key.substringAfter('/')
        var renaming by rememberSaveable { mutableStateOf(false) }
        var newName by rememberSaveable { mutableStateOf("") }
        val full = if (newName.endsWith(".lua")) newName else "$newName.lua"
        val valid = newName.isNotBlank() && ScriptStore.isValidName(full) && full !in names
        fun finish(asName: String, overwrite: Boolean) {
            if (runCatching { store.importHostToGlobal(hostId, name, asName, overwrite) }.getOrDefault(false)) {
                names = store.listGlobal()
                app.sessions.reloadExtensions()
                Toast.makeText(context, context.getString(R.string.ext_imported, asName), Toast.LENGTH_SHORT).show()
            }
            importing = null
        }
        if (!renaming) AlertDialog(onDismissRequest = { importing = null }, title = { Text(stringResource(R.string.ext_conflict_title)) },
            text = { Text(stringResource(R.string.ext_conflict_text, name)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { newName = name.removeSuffix(".lua"); renaming = true }) { Text(stringResource(R.string.ext_import_renamed)) }
                    TextButton(onClick = { finish(name, true) }) { Text(stringResource(R.string.ext_overwrite), color = CyanVeil.Rose) }
                }
            }, dismissButton = { TextButton(onClick = { importing = null }) { Text(stringResource(R.string.ext_cancel)) } })
        else AlertDialog(onDismissRequest = { importing = null }, title = { Text(stringResource(R.string.ext_import_renamed)) }, text = {
            OutlinedTextField(newName, { newName = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.ext_file_name)) },
                suffix = { if (!newName.endsWith(".lua")) Text(".lua") },
                supportingText = { Text(if (full in names) stringResource(R.string.ext_name_duplicate) else stringResource(R.string.ext_name_allowed)) },
                isError = newName.isNotEmpty() && !valid, shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }, confirmButton = { TextButton(enabled = valid, onClick = { finish(full, false) }) { Text(stringResource(R.string.ext_import)) } },
            dismissButton = { TextButton(onClick = { importing = null }) { Text(stringResource(R.string.ext_cancel)) } })
    }
    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.ext_delete_script)) },
            text = { Text(stringResource(R.string.ext_delete_script_text, name)) }, confirmButton = {
                TextButton(onClick = {
                    store.deleteGlobal(name)
                    app.sessions.reloadExtensions()
                    names = store.listGlobal()
                    deleting = null
                }) { Text(stringResource(R.string.ext_delete), color = CyanVeil.Rose) }
            }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.ext_cancel)) } })
    }
}

@Composable
private fun ScriptEditor(
    name: String,
    initial: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    readOnly: Boolean = false,
    /** 読み取り専用のときに右下へ出す追加操作(ラベル to 実行内容)。 */
    extraAction: Pair<String, () -> Unit>? = null,
) {
    var text by rememberSaveable(name) { mutableStateOf(initial) }
    val lineCount = text.count { it == '\n' } + 1
    val background = CyanVeil.Background.copy(alpha = LocalAppearance.current.backgroundOpacity)
    BackHandler(onBack = onCancel)
    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            VeilPageHeader(if (readOnly) stringResource(R.string.ext_view_script) else stringResource(R.string.ext_edit_script), onCancel, name)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                StatusPill("LUA", CyanVeil.Cyan)
                if (readOnly) Text(stringResource(R.string.ext_read_only), fontSize = 11.sp, color = CyanVeil.Muted)
                else Text(if (text != initial) stringResource(R.string.ext_unsaved) else stringResource(R.string.ext_saved), fontSize = 11.sp,
                    color = if (text != initial) CyanVeil.Amber else CyanVeil.Muted)
            }
            OutlinedTextField(text, { text = it }, readOnly = readOnly, modifier = Modifier.weight(1f).fillMaxWidth(),
                label = { Text(stringResource(R.string.ext_lua_script_label)) }, shape = MaterialTheme.shapes.large,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp, color = CyanVeil.Text),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = background, unfocusedContainerColor = background,
                    focusedBorderColor = CyanVeil.Cyan, unfocusedBorderColor = CyanVeil.Border, cursorColor = CyanVeil.Cyan),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(LocalContext.current.resources.getQuantityString(R.plurals.ext_lines, lineCount, lineCount), fontSize = 11.sp, color = CyanVeil.Muted)
                if (readOnly && extraAction != null) Button(onClick = extraAction.second, shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(extraAction.first, Modifier.padding(horizontal = 8.dp))
                }
                if (!readOnly) Button(onClick = { onSave(text) }, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ext_save_apply), Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

private fun countItems(context: android.content.Context, n: Int): String =
    context.resources.getQuantityString(R.plurals.ext_count_items, n, n)
