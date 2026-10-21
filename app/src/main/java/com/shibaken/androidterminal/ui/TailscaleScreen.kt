package com.shibaken.androidterminal.ui

import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val ts by app.tailscale.status.collectAsState()
    var authKey by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }

    var logs by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { app.tailscale.refreshStatus() } }
    LaunchedEffect(showLogs) {
        if (showLogs) logs = withContext(Dispatchers.IO) { app.tailscale.recentLogs() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tailscale") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!app.tailscale.available) {
                item {
                    Text(
                        stringResource(R.string.settings_tailscale_not_built),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val c = when {
                                ts.isConnected -> StatusColors.done
                                ts.running -> StatusColors.blocked
                                else -> StatusColors.idle
                            }
                            Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(10.dp))
                            Text(stateLabel(ts.backendState), style = MaterialTheme.typography.titleMedium)
                        }
                        if (ts.selfDNSName.isNotBlank()) Text(stringResource(R.string.settings_tailscale_this_device, ts.selfDNSName.trimEnd('.')), style = MaterialTheme.typography.bodySmall)
                        if (ts.tailscaleIPs.isNotEmpty()) Text(ts.tailscaleIPs.joinToString("  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        ts.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        ts.health.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.blocked) }
                    }
                }
            }

            if (ts.authURL.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_tailscale_login_prompt))
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authURL)))
                        }) { Text(stringResource(R.string.settings_tailscale_login_browser)) }
                    }
                }
            }

            if (!ts.running) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.settings_tailscale_intro), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            authKey, { authKey = it }, label = { Text(stringResource(R.string.settings_tailscale_authkey_label)) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { app.tailscale.start(authKey.trim().ifBlank { null }) }) { Text(stringResource(R.string.settings_tailscale_start)) }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.tailscale.stop() }) { Text(stringResource(R.string.settings_tailscale_stop)) }
                        OutlinedButton(onClick = { app.tailscale.logout() }) { Text(stringResource(R.string.settings_tailscale_logout)) }
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) stringResource(R.string.settings_tailscale_hide_logs) else stringResource(R.string.settings_tailscale_logs)) }
                    }
                }
            }

            if (ts.peers.isNotEmpty()) {
                item { Text(stringResource(R.string.settings_tailscale_devices), style = MaterialTheme.typography.labelLarge) }
                items(ts.peers, key = { it.dnsName.ifBlank { it.hostName } }) { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (p.online) StatusColors.done else StatusColors.idle))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(p.hostName + if (p.os.isNotBlank()) "  (${p.os})" else "")
                            Text(p.ips.joinToString("  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (showLogs) {
                item {
                    Text(logs, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun stateLabel(s: String): String = when (s) {
    "Running" -> stringResource(R.string.settings_tailscale_state_running)
    "NeedsLogin" -> stringResource(R.string.settings_tailscale_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.settings_tailscale_state_needs_machine_auth)
    "Starting" -> stringResource(R.string.settings_tailscale_state_starting)
    "Stopped" -> stringResource(R.string.settings_tailscale_state_stopped)
    "NoState" -> stringResource(R.string.settings_tailscale_state_no_state)
    else -> s
}
