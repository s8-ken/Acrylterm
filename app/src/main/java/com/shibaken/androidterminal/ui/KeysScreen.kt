package com.shibaken.androidterminal.ui

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
                title = { Text("SSH 鍵") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("新しい鍵を生成") }, onClick = { menu = false; showGenerate = true })
                    DropdownMenuItem(text = { Text("既存の鍵をインポート") }, onClick = { menu = false; showImport = true })
                }
                FloatingActionButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "追加") }
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
                    "公開鍵を Mac の ~/.ssh/authorized_keys に追記すると、その鍵でログインできます。秘密鍵は Android Keystore で暗号化して端末内に保存されます。",
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
                        Toast.makeText(context, "公開鍵をコピーしました", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, k.publicKey)
                        context.startActivity(Intent.createChooser(send, "公開鍵を共有"))
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
            title = { Text("鍵を削除") },
            text = { Text("「${k.name}」を削除します。この鍵を使っているホストは鍵の再設定が必要になります。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    app.keys.delete(k.id)
                    app.hosts.clearKeyReferences(k.id)
                    deleting = null
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("キャンセル") } },
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
                        k.type.label + if (k.hasPassphrase) " · パスフレーズ付き" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "公開鍵をコピー") }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "共有") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "削除") }
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
                Text("使用時に生体認証/画面ロックを要求", style = MaterialTheme.typography.bodyMedium)
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
        title = { Text("鍵を生成") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(KeyType.ED25519, KeyType.RSA_4096).forEach { t ->
                        FilterChip(type == t, { type = t }, label = { Text(t.label) })
                    }
                }
                Text("特別な理由がなければ Ed25519 がおすすめです。", style = MaterialTheme.typography.bodySmall)
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
            }) { Text("生成") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") } },
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
        title = { Text("鍵をインポート") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileOpen, null)
                        Text(" ファイルから選ぶ")
                    }
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("秘密鍵(-----BEGIN ... を貼り付け)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    passphrase, { passphrase = it }, label = { Text("パスフレーズ(あれば)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (passphrase.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(savePass, { savePass = it })
                        Text("パスフレーズを保存する(暗号化)", style = MaterialTheme.typography.bodyMedium)
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
            }) { Text("インポート") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("キャンセル") } },
    )
}
