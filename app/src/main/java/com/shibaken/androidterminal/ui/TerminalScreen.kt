package com.shibaken.androidterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.session.ConnState
import com.shibaken.androidterminal.terminal.Modifiers
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val session = remember(sessionId) { app.sessions.get(sessionId) }
    if (session == null) {
        Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("セッションが見つかりません")
                TextButton(onClick = onBack) { Text("戻る") }
            }
        }
        return
    }

    val state by session.state.collectAsState()
    val title by session.title.collectAsState()
    val herdr = session.herdr
    val herdrState = herdr?.state?.collectAsState()?.value ?: HerdrState()

    // Re-read modifier state for the extra-keys highlight.
    var modTick by remember { mutableIntStateOf(0) }
    val modifiers = remember { Modifiers { modTick++ } }
    var directInput by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClosePane by remember { mutableStateOf(false) }

    val view = remember(sessionId) {
        TerminalCanvasView(context).apply {
            this.modifiers = modifiers
            this.textSizeSp = session.profile.fontSizeSp
            onTextSizeChanged = { size -> app.hosts.get(session.profile.id)?.let { app.hosts.save(it.copy(fontSizeSp = size)) } }
        }
    }
    DisposableEffect(view, session) {
        view.session = session
        app.sessions.visibleSessionId = session.id
        onDispose {
            view.session = null
            if (app.sessions.visibleSessionId == session.id) app.sessions.visibleSessionId = null
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, // keep edge swipes for the terminal
        drawerContent = {
            if (herdr != null) {
                ModalDrawerSheet {
                    HerdrDrawerContent(herdrState, herdr, onNavigated = { scope.launch { drawerState.close() } })
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding()
        ) {
            // --- top bar -----------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "ホスト一覧") }
                if (herdr != null) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "herdr") }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val focused = herdrState.pane(herdrState.focusedPaneId)
                    val sub = if (focused != null) {
                        listOfNotNull(herdrState.workspace(focused.workspaceId)?.title, focused.title2).joinToString(" › ")
                    } else session.profile.displayName
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (herdrState.blockedCount > 0) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusColors.blocked.copy(alpha = 0.18f))
                            .clickable { scope.launch { drawerState.open() } }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(StatusColors.blocked))
                        Spacer(Modifier.width(6.dp))
                        Text("入力待ち ${herdrState.blockedCount}", style = MaterialTheme.typography.labelMedium, color = StatusColors.blocked)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "メニュー") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (directInput) "日本語入力を使う" else "直接入力(変換なし)にする") },
                            onClick = { directInput = !directInput; view.directInput = directInput; menu = false },
                        )
                        DropdownMenuItem(text = { Text("貼り付け") }, onClick = { session.pasteFromClipboard(); menu = false })
                        DropdownMenuItem(text = { Text("文字を大きく") }, onClick = { view.textSizeSp += 1f; menu = false })
                        DropdownMenuItem(text = { Text("文字を小さく") }, onClick = { view.textSizeSp -= 1f; menu = false })
                        if (state is ConnState.Disconnected) {
                            DropdownMenuItem(text = { Text("再接続") }, onClick = { session.connect(); menu = false })
                        } else {
                            DropdownMenuItem(text = { Text("切断") }, onClick = { session.disconnect(); menu = false })
                        }
                        DropdownMenuItem(text = { Text("セッションを閉じる") }, onClick = {
                            menu = false
                            app.sessions.close(session.id)
                            onBack()
                        })
                    }
                }
            }

            // --- connection status -------------------------------------------------------
            when (val s = state) {
                is ConnState.Connecting -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(s.message, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
                is ConnState.Disconnected -> Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "切断: ${s.reason ?: "手動で切断しました"}",
                        Modifier.weight(1f).padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                    TextButton(onClick = { session.connect() }) { Text("再接続") }
                }
                ConnState.Connected -> {}
            }

            // --- terminal -----------------------------------------------------------------
            AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())

            // --- herdr quick actions ------------------------------------------------------
            if (herdr != null && herdrState.phase == HerdrState.Phase.READY) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = { herdr.split("right") }) { Icon(Icons.Filled.VerticalSplit, "左右に分割") }
                    IconButton(onClick = { herdr.split("down") }) { Icon(Icons.Filled.HorizontalSplit, "上下に分割") }
                    IconButton(onClick = { herdr.toggleZoom() }) { Icon(Icons.Filled.CenterFocusStrong, "ズーム") }
                    TextButton(onClick = { herdr.focusDirection("left") }) { Text("◀") }
                    TextButton(onClick = { herdr.focusDirection("right") }) { Text("▶") }
                    IconButton(onClick = { confirmClosePane = true }) { Icon(Icons.Filled.Close, "ペインを閉じる") }
                }
            }

            // --- extra keys ---------------------------------------------------------------
            // Reading modTick makes the bar recompose when the sticky modifiers change.
            ExtraKeysBar(
                view = view,
                ctrlOn = modTick >= 0 && modifiers.ctrl,
                altOn = modTick >= 0 && modifiers.alt,
                onCtrl = { modifiers.ctrl = !modifiers.ctrl },
                onAlt = { modifiers.alt = !modifiers.alt },
                onKeyboard = { view.toggleKeyboard() },
            )
        }
    }

    if (confirmClosePane && herdr != null) {
        val pane = herdrState.pane(herdrState.focusedPaneId)
        AlertDialog(
            onDismissRequest = { confirmClosePane = false },
            title = { Text("ペインを閉じる") },
            text = { Text("「${pane?.title2 ?: "フォーカス中のペイン"}」を閉じます。実行中のプロセスは終了します。") },
            confirmButton = {
                TextButton(onClick = {
                    pane?.let { herdr.closePane(it.paneId) }
                    confirmClosePane = false
                }) { Text("閉じる") }
            },
            dismissButton = { TextButton(onClick = { confirmClosePane = false }) { Text("キャンセル") } },
        )
    }
}
