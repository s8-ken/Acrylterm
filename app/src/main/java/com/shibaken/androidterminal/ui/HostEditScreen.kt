package com.shibaken.androidterminal.ui

import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R
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
import com.shibaken.androidterminal.ssh.RemoteCommand

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
    var confirmDelete by remember { mutableStateOf(false) }

    val valid = host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
        (auth == AuthMethod.PASSWORD || keyId != null)

    fun save() {
        app.hosts.save(
            (app.hosts.get(original.id) ?: original).copy(
                name = name.trim(), host = host.trim(), port = port.toInt(), username = user.trim(),
                authMethod = auth, keyId = if (auth == AuthMethod.KEY) keyId else null,
                useTailscale = useTs, herdrEnabled = herdr, useMosh = useMosh,
                startupCommand = startup.trim(), herdrSession = herdrSession.trim(),
                extEnabled = extEnabled, extLoadFromHost = extEnabled && extFromHost,
            )
        )
        onDone()
    }

    VeilBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            VeilPageHeader(if (hostId == null) stringResource(R.string.settings_hostedit_add_title) else stringResource(R.string.settings_hostedit_edit_title), onDone, actions = {
                if (hostId != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.settings_delete), tint = CyanVeil.Muted) }
                FilledTonalButton(onClick = ::save, enabled = valid, shape = CircleShape,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_hostedit_save)) }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.settings_hostedit_display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    host, { host = it }, label = { Text(stringResource(R.string.settings_hostedit_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (useTs) stringResource(R.string.settings_hostedit_host_hint_ts) else stringResource(R.string.settings_hostedit_host_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (useTs && ts.peers.isNotEmpty()) {
                    Text(stringResource(R.string.settings_hostedit_tailnet_devices), style = MaterialTheme.typography.labelMedium)
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
                        user, { user = it }, label = { Text(stringResource(R.string.settings_hostedit_username)) }, singleLine = true, modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.settings_hostedit_port)) }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                Text(stringResource(R.string.settings_hostedit_auth), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(auth == AuthMethod.KEY, { auth = AuthMethod.KEY }, label = { Text(stringResource(R.string.settings_hostedit_auth_key)) }, shape = CircleShape)
                    FilterChip(auth == AuthMethod.PASSWORD, { auth = AuthMethod.PASSWORD }, label = { Text(stringResource(R.string.settings_hostedit_auth_password)) }, shape = CircleShape)
                }
                if (auth == AuthMethod.KEY) {
                    if (keys.isEmpty()) {
                        Text(stringResource(R.string.settings_hostedit_no_keys), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onManageKeys) { Text(stringResource(R.string.settings_hostedit_manage_keys)) }
                    } else {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded, { expanded = it }) {
                            OutlinedTextField(
                                value = keys.firstOrNull { it.id == keyId }?.name ?: stringResource(R.string.settings_hostedit_choose),
                                onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.settings_hostedit_key_label)) },
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
                    Text(stringResource(R.string.settings_hostedit_password_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                VeilCard(Modifier.fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_hostedit_connection), "")
                    SwitchRow(
                        stringResource(R.string.settings_hostedit_via_tailscale),
                        if (app.tailscale.available) stringResource(R.string.settings_hostedit_ts_builtin)
                        else stringResource(R.string.settings_hostedit_ts_missing),
                        useTs, enabled = app.tailscale.available,
                    ) { useTs = it }
                    SwitchRow(stringResource(R.string.settings_hostedit_multitab), stringResource(R.string.settings_hostedit_multitab_sub), herdr) {
                        herdr = it
                        if (!it && RemoteCommand.invokesHerdr(startup)) startup = ""
                        else if (it && startup.isBlank()) startup = "herdr"
                    }
                    if (herdr) SwitchRow("Mosh", stringResource(R.string.settings_hostedit_mosh_sub), useMosh) { useMosh = it }
                }
                if (extensionsAvailable) VeilCard(Modifier.fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_hostedit_extensions), "LUA")
                    SwitchRow(stringResource(R.string.settings_hostedit_ext_enable), stringResource(R.string.settings_hostedit_ext_enable_sub), extEnabled) { extEnabled = it }
                    SwitchRow(stringResource(R.string.settings_hostedit_ext_load_host), stringResource(R.string.settings_hostedit_ext_load_host_sub), extFromHost && extEnabled,
                        enabled = extEnabled) { extFromHost = it }
                }

                if (!herdr) {
                    OutlinedTextField(
                        startup, { startup = it }, label = { Text(stringResource(R.string.settings_hostedit_startup_label)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.settings_hostedit_startup_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    )
                } else {
                    var advanced by remember { mutableStateOf(false) }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) stringResource(R.string.settings_hostedit_advanced_open) else stringResource(R.string.settings_hostedit_advanced_closed)) }
                    if (advanced) {
                        OutlinedTextField(
                            herdrSession, { herdrSession = it }, label = { Text(stringResource(R.string.settings_hostedit_backend_session)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.settings_hostedit_backend_session_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                        OutlinedTextField(
                            startup, { startup = it }, label = { Text(stringResource(R.string.settings_hostedit_compat_label)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.settings_hostedit_compat_hint)) },
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
            title = { Text(stringResource(R.string.settings_hostedit_delete_title)) },
            text = { Text(stringResource(R.string.settings_hostedit_delete_message, original.displayName)) },
            confirmButton = {
                TextButton(onClick = { app.hosts.delete(original.id); confirmDelete = false; onDone() }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.settings_cancel)) } },
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

