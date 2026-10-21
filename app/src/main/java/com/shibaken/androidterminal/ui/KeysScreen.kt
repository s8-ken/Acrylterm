package com.shibaken.androidterminal.ui

import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.KeyType
import com.shibaken.androidterminal.data.SshKeyEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val keys by app.keys.keys.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshKeyEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_keys_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_keys_generate_new)) }, onClick = { menu = false; showGenerate = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_keys_import_existing)) }, onClick = { menu = false; showImport = true })
                }
                FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, stringResource(R.string.settings_keys_add)) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.settings_keys_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(keys, key = { it.id }) { k ->
                KeyCard(
                    k,
                    onCopy = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("ssh public key", k.publicKey))
                        Toast.makeText(context, context.getString(R.string.settings_keys_copied), Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.settings_keys_share_chooser)))
                    },
                    onBiometric = { app.keys.setRequireBiometric(k.id, it) },
                    onDelete = { deleting = k },
                )
            }
        }
    }

    if (showGenerate) GenerateDialog(onDismiss = { showGenerate = false })
    if (showImport) ImportDialog(onDismiss = { showImport = false })
    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_keys_delete_title)) },
            text = { Text(stringResource(R.string.settings_keys_delete_message, k.name)) },
            confirmButton = {
                TextButton(onClick = {
                    app.keys.delete(k.id)
                    app.hosts.clearKeyReferences(k.id)
                    deleting = null
                }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun KeyCard(
    k: SshKeyEntry,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        k.type.label + if (k.hasPassphrase) " · " + stringResource(R.string.settings_keys_with_passphrase) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.settings_keys_copy_public)) }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, stringResource(R.string.settings_keys_share)) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.settings_delete)) }
            }
            Text(k.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                k.publicKey,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(k.requireBiometric, onBiometric)
                Text(stringResource(R.string.settings_keys_require_biometric), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun GenerateDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("android") }
    var type by remember { mutableStateOf(KeyType.ED25519) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_keys_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_keys_name)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KeyType.ED25519, KeyType.RSA_4096).forEach { t ->
                        FilterChip(type == t, { type = t }, label = { Text(t.label) })
                    }
                }
                Text(stringResource(R.string.settings_keys_ed25519_hint), style = MaterialTheme.typography.bodySmall)
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        app.keys.generate(name.trim(), type)
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.settings_keys_generate)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var savePass by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.onSuccess { text = it ?: "" }.onFailure { error = it.message }
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_keys_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_keys_name)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileOpen, null)
                        Text(" " + stringResource(R.string.settings_keys_pick_file))
                    }
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.settings_keys_private_key_label)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text(stringResource(R.string.settings_keys_passphrase_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (passphrase.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(savePass, { savePass = it })
                        Text(stringResource(R.string.settings_keys_save_passphrase), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        app.keys.import(name.trim(), text, passphrase.takeIf { it.isNotEmpty() }?.toCharArray(), savePass)
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.settings_keys_import)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
