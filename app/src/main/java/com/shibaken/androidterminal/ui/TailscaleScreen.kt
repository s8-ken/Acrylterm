package com.shibaken.androidterminal.ui

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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
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
                        "このビルドには Tailscale が組み込まれていません。\n" +
                            "リポジトリの tsbridge/build.sh を実行して app/libs/tsbridge.aar を作り、もう一度ビルドしてください。\n" +
                            "それまでは端末の Tailscale アプリ(VPN)経由で接続できます(ホスト設定で「Tailscale 経由」をオフ)。",
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
                        if (ts.selfDNSName.isNotBlank()) Text("この端末: ${ts.selfDNSName.trimEnd('.')}", style = MaterialTheme.typography.bodySmall)
                        if (ts.tailscaleIPs.isNotEmpty()) Text(ts.tailscaleIPs.joinToString("  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        ts.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        ts.health.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = StatusColors.blocked) }
                    }
                }
            }

            if (ts.authURL.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ブラウザで tailnet にログインして、この端末を承認してください。")
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ts.authURL)))
                        }) { Text("ブラウザでログイン") }
                    }
                }
            }

            if (!ts.running) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("アプリ内に Tailscale ノードを起動します。端末の VPN 枠は使わないので、公式アプリと併用できます。", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            authKey, { authKey = it }, label = { Text("認証キー(任意: tskey-auth-…)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { app.tailscale.start(authKey.trim().ifBlank { null }) }) { Text("Tailscale を開始") }
                    }
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.tailscale.stop() }) { Text("停止") }
                        OutlinedButton(onClick = { app.tailscale.logout() }) { Text("ログアウト") }
                        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "ログを隠す" else "ログ") }
                    }
                }
            }

            if (ts.peers.isNotEmpty()) {
                item { Text("デバイス", style = MaterialTheme.typography.labelLarge) }
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

private fun stateLabel(s: String): String = when (s) {
    "Running" -> "接続済み"
    "NeedsLogin" -> "ログインが必要"
    "NeedsMachineAuth" -> "管理者の承認待ち"
    "Starting" -> "接続中…"
    "Stopped" -> "停止中"
    "NoState" -> "起動中…"
    else -> s
}
