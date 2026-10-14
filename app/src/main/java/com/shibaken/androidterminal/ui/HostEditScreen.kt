package com.shibaken.androidterminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.AuthMethod
import com.shibaken.androidterminal.data.HostProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(hostId: String?, onDone: () -> Unit, onManageKeys: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val appSettings by app.settings.settings.collectAsState()
    val extensionsAvailable = appSettings.extensionsEnabled
    val original = remember(hostId) { hostId?.let { app.hosts.get(it) } ?: HostProfile() }
    val keys by app.keys.keys.collectAsState()
    val ts by app.tailscale.status.collectAsState()

    var name by remember { mutableStateOf(original.name) }
    var host by remember { mutableStateOf(original.host) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var user by remember { mutableStateOf(original.username) }
    var auth by remember { mutableStateOf(original.authMethod) }
    var keyId by remember { mutableStateOf(original.keyId ?: keys.firstOrNull()?.id) }
    var useTs by remember { mutableStateOf(original.useTailscale && app.tailscale.available) }
    var herdr by remember { mutableStateOf(original.herdrEnabled) }
    var useMosh by remember { mutableStateOf(original.useMosh) }
    var startup by remember { mutableStateOf(original.startupCommand) }
    var herdrSession by remember { mutableStateOf(original.herdrSession) }
    var extEnabled by remember { mutableStateOf(original.extEnabled) }
    var extFromHost by remember { mutableStateOf(original.extLoadFromHost) }
    var extAgent by remember { mutableStateOf(original.extAgentCommand) }
    var confirmDelete by remember { mutableStateOf(false) }

    val valid = host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
        (auth == AuthMethod.PASSWORD || keyId != null)

    fun save() {
        app.hosts.save(
            original.copy(
                name = name.trim(), host = host.trim(), port = port.toInt(), username = user.trim(),
                authMethod = auth, keyId = if (auth == AuthMethod.KEY) keyId else null,
                useTailscale = useTs, herdrEnabled = herdr, useMosh = useMosh,
                startupCommand = startup.trim(), herdrSession = herdrSession.trim(),
                extEnabled = extEnabled, extLoadFromHost = extEnabled && extFromHost,
                extAgentCommand = extAgent.trim().ifBlank { HostProfile().extAgentCommand },
            )
        )
        onDone()
    }

    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            VeilPageHeader(if (hostId == null) "ホストを追加" else "ホストを編集", onDone, actions = {
                if (hostId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "削除", tint = CyanVeil.Muted) }
                FilledTonalButton(onClick = ::save, enabled = valid, shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("保存") }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("表示名(任意)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    host, { host = it }, label = { Text("ホスト") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (useTs) "MagicDNS 名 (mac-mini) か Tailscale IP (100.x.y.z)" else "IP アドレスまたはホスト名") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (useTs && ts.peers.isNotEmpty()) {
                    Text("tailnet のデバイス", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ts.peers.filter { it.online }.forEach { p ->
                            AssistChip(onClick = {
                                host = p.hostName.ifBlank { p.ips.firstOrNull() ?: "" }
                                if (name.isBlank()) name = p.hostName
                            }, label = { Text(p.hostName) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        user, { user = it }, label = { Text("ユーザー名") }, singleLine = true, modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text("ポート") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text("認証", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(auth == AuthMethod.KEY, { auth = AuthMethod.KEY }, label = { Text("SSH 鍵") }, shape = CircleShape)
                    FilterChip(auth == AuthMethod.PASSWORD, { auth = AuthMethod.PASSWORD }, label = { Text("パスワード") }, shape = CircleShape)
                }
                if (auth == AuthMethod.KEY) {
                    if (keys.isEmpty()) {
                        Text("SSH 鍵がまだありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onManageKeys) { Text("鍵を作成・インポートする") }
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded, { expanded = it }) {
                            OutlinedTextField(
                                value = keys.firstOrNull { it.id == keyId }?.name ?: "選択してください",
                                onValueChange = {}, readOnly = true, label = { Text("使用する鍵") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            )
                            ExposedDropdownMenu(expanded, { expanded = false }) {
                                keys.forEach { k ->
                                    DropdownMenuItem(
                                        text = { Text("${k.name}  (${k.type.label})") },
                                        onClick = { keyId = k.id; expanded = false },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text("パスワードは保存されず、接続のたびに入力します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                VeilCard(Modifier.fillMaxWidth()) {
                    SectionHeading("接続方法", "")
                    SwitchRow(
                        "Tailscale 経由で接続",
                        if (app.tailscale.available) "アプリ内蔵の Tailscale を使います(公式アプリ・VPN 不要)"
                        else "このビルドには Tailscale が組み込まれていません(端末側の VPN を使ってください)",
                        useTs, enabled = app.tailscale.available,
                    ) { useTs = it }
                    SwitchRow("マルチタブ / マルチペイン", "タブと画面分割を使い、エージェントの入力待ちを通知します", herdr) { herdr = it }
                    if (herdr) SwitchRow("Mosh", "ペインを Mosh で表示(回線が切り替わっても維持)。mosh-server が必要", useMosh) { useMosh = it }
                }
                if (extensionsAvailable) VeilCard(Modifier.fillMaxWidth()) {
                    SectionHeading("拡張スクリプト", "LUA")
                    SwitchRow("このホストで有効にする", "補助キー・コマンド・クイック返信をカスタマイズ", extEnabled) { extEnabled = it }
                    SwitchRow("ホストのスクリプトを読み込む", "接続先の Lua を共通スクリプトに追加", extFromHost && extEnabled,
                        enabled = extEnabled) { extFromHost = it }
                    OutlinedTextField(
                        extAgent, { extAgent = it }, label = { Text("生成に使う AI コマンド") }, singleLine = true,
                        enabled = extEnabled && extFromHost, modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!herdr) {
                    OutlinedTextField(
                        startup, { startup = it }, label = { Text("ログイン後に実行するコマンド") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("空欄なら通常のシェル") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                } else {
                    var advanced by remember { mutableStateOf(false) }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "詳細 ▲" else "詳細 ▼") }
                    if (advanced) {
                        OutlinedTextField(
                            herdrSession, { herdrSession = it }, label = { Text("バックエンドのセッション名") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("空欄なら android。パソコン側の作業と分けたいときに変更します") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        OutlinedTextField(
                            startup, { startup = it }, label = { Text("互換モードで実行するコマンド") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("マルチタブが使えない環境でだけ使われます") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("ホストを削除") },
            text = { Text("「${original.displayName}」を削除しますか?") },
            confirmButton = {
                TextButton(onClick = { app.hosts.delete(original.id); confirmDelete = false; onDone() }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

