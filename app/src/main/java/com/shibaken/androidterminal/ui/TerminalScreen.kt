package com.shibaken.androidterminal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.session.ConnState
import com.shibaken.androidterminal.session.SessionMode
import com.shibaken.androidterminal.terminal.TerminalBackend
import com.shibaken.androidterminal.terminal.Modifiers
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(sessionId: String, onBack: () -> Unit, onAppearance: () -> Unit) {
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

    val appearance = LocalAppearance.current
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
    val pool = remember(sessionId) {
        PaneViewPool(context, modifiers, session.profile.fontSizeSp) { size ->
            app.hosts.get(session.profile.id)?.let { app.hosts.save(it.copy(fontSizeSp = size)) }
        }
    }
    SideEffect {
        view.backgroundOpacity = appearance.backgroundOpacity
        pool.backgroundOpacity = appearance.backgroundOpacity
    }
    DisposableEffect(pool) { onDispose { pool.clear() } }

    // PANES mode: one view per pane of the focused tab (PaneLayout). SHELL / LEGACY_TUI: the single view.
    val mode by session.mode.collectAsState()
    val paneTerminals by session.panes.collectAsState()
    val isPanes = mode == SessionMode.PANES && herdr != null
    val focusedPaneId = herdrState.focusedPaneId
    /** The view that receives extra keys and IME input. */
    val activeView: TerminalCanvasView = if (isPanes && focusedPaneId != null) pool.get(focusedPaneId) else view
    val pasteTarget: TerminalBackend? = if (isPanes) paneTerminals[focusedPaneId] else session.shell
    val shell = session.shell // not observable: re-read on every recomposition like before
    DisposableEffect(view, isPanes, shell) {
        view.session = if (isPanes) null else shell
        onDispose { view.session = null }
    }
    DisposableEffect(session) {
        app.sessions.visibleSessionId = session.id
        onDispose {
            session.setVisiblePanes(emptySet())
            if (app.sessions.visibleSessionId == session.id) app.sessions.visibleSessionId = null
        }
    }
    val extRt by session.ext.collectAsState()
    val extRegistry = extRt?.engine?.registry?.collectAsState()?.value
    var showExtCommands by remember { mutableStateOf(false) }
    var showOverview by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(herdr) {
        herdr?.errors?.collect { msg ->
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(session) {
        session.notices.collect { msg ->
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }
    val imeVisible = WindowInsets.isImeVisible

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen, // keep edge swipes for the terminal
        drawerContent = {
            if (herdr != null) {
                ModalDrawerSheet {
                    HerdrDrawerContent(herdrState, herdr, title = session.profile.displayName, showVersion = !isPanes, onNavigated = { scope.launch { drawerState.close() } })
                }
            }
        },
    ) {
        VeilBackdrop(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
        Column(Modifier.fillMaxSize()
            .then(if (showOverview) Modifier.clearAndSetSemantics { } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // --- top bar -----------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VeilIconButton(R.drawable.veil_back, "ホスト一覧", onBack)
                Spacer(Modifier.width(10.dp))
                if (herdr != null && !isPanes) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Filled.Menu, "ワークスペースを開く") }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isPanes) {
                        Text(if (state is ConnState.Connected) "● 接続中" + if (session.profile.useTailscale) " / Tailscale" else " / SSH" else "セッション",
                            style = MaterialTheme.typography.labelSmall, color = CyanVeil.Mint)
                    }
                    if (!isPanes) {
                        val focused = herdrState.pane(herdrState.focusedPaneId)
                        val sub = if (focused != null) {
                            listOfNotNull(herdrState.workspace(focused.workspaceId)?.title, focused.title2).joinToString(" › ")
                        } else session.profile.displayName
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // PANES mode shows the waiting count as a badge on the tab strip's overview icon instead.
                if (!isPanes && herdrState.blockedCount > 0) {
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
                VeilIconButton(R.drawable.veil_tune, "外観設定", onAppearance)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "メニュー") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("外観設定") }, onClick = { menu = false; onAppearance() })
                        DropdownMenuItem(
                            text = { Text(if (directInput) "日本語入力を使う" else "直接入力(変換なし)にする") },
                            onClick = { directInput = !directInput; view.directInput = directInput; pool.directInput = directInput; menu = false },
                        )
                        if (extRegistry != null && extRegistry.commands.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("拡張コマンド") }, onClick = { showExtCommands = true; menu = false })
                        }
                        if (session.profile.extEnabled) {
                            DropdownMenuItem(text = { Text("拡張を再読込") }, onClick = { session.reloadExtensions(fetch = true); menu = false })
                        }
                        DropdownMenuItem(text = { Text("貼り付け") }, onClick = { pasteTarget?.pasteFromClipboard(); menu = false })
                        DropdownMenuItem(text = { Text("文字を大きく") }, onClick = { activeView.textSizeSp += 1f; menu = false })
                        DropdownMenuItem(text = { Text("文字を小さく") }, onClick = { activeView.textSizeSp -= 1f; menu = false })
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

            if (!imeVisible && isPanes) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VeilIcon(R.drawable.veil_folder, null, CyanVeil.Muted)
                    Spacer(Modifier.width(8.dp))
                    Text(listOfNotNull(herdrState.workspace(herdrState.focusedWorkspaceId)?.title,
                        herdrState.tabs.firstOrNull { it.tabId == herdrState.focusedTabId }?.title).joinToString(" / "),
                        style = MaterialTheme.typography.labelMedium, color = CyanVeil.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val terminalModifier = Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large)
                .border(1.dp, CyanVeil.Border, MaterialTheme.shapes.large).padding(8.dp)
            // --- terminal -----------------------------------------------------------------
            if (herdr != null && mode == SessionMode.PANES) {
                PaneLayout(
                    session = session, state = herdrState, herdr = herdr, pool = pool,
                    onOpenComposer = { showComposer = true },
                    inputEnabled = !showOverview && !drawerState.isOpen,
                    modifier = terminalModifier,
                )
            } else {
                AndroidView(factory = { view }, modifier = terminalModifier)
            }

            // --- tab strip (+ quick actions in the legacy TUI mode) -----------------------
            if (herdr != null && herdrState.phase == HerdrState.Phase.READY) {
                HerdrTabStrip(
                    herdrState, herdr,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenOverview = { showOverview = true },
                    onOpenComposer = { showComposer = true },
                )
                // Legacy TUI mode only (native panes need none); hidden while the IME is up to leave rows for the terminal.
                if (!isPanes && !imeVisible) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = { herdr.focusDirection("left") }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "左のペインへ", tint = MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = { herdr.focusDirection("up") }) { Icon(Icons.Filled.KeyboardArrowUp, "上のペインへ", tint = MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = { herdr.focusDirection("down") }) { Icon(Icons.Filled.KeyboardArrowDown, "下のペインへ", tint = MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = { herdr.focusDirection("right") }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "右のペインへ", tint = MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = { herdr.toggleZoom() }) { Icon(Icons.Filled.CenterFocusStrong, "ズーム", tint = MaterialTheme.colorScheme.onSurface) }
                        IconButton(onClick = { confirmClosePane = true }) { Icon(Icons.Filled.Close, "ペインを閉じる", tint = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }

            // --- extra keys ---------------------------------------------------------------
            // Reading modTick makes the bar recompose when the sticky modifiers change.
            ExtraKeysBar(
                view = activeView,
                ctrlOn = modTick >= 0 && modifiers.ctrl,
                altOn = modTick >= 0 && modifiers.alt,
                onCtrl = { modifiers.ctrl = !modifiers.ctrl },
                onAlt = { modifiers.alt = !modifiers.alt },
                onKeyboard = { activeView.toggleKeyboard() },
                showPrefixKey = !isPanes,
                extKeys = extRegistry?.keys?.map { it.label } ?: emptyList(),
                onExtKey = { i -> extRt?.engine?.pressKey(i, if (isPanes) focusedPaneId else "shell") },
            )
            if (isPanes && !imeVisible) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(100.dp)).background(CyanVeil.Surface)
                    .clickable { showComposer = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    VeilIcon(R.drawable.veil_edit, null, CyanVeil.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Text("日本語で返信を作成…", color = CyanVeil.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showOverview && herdr != null) {
            PaneOverview(herdrState, herdr, onDismiss = { showOverview = false }, modifier = Modifier.fillMaxSize().zIndex(1f))
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
        }
    }

    if (showExtCommands && extRt != null) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showExtCommands = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                extRegistry?.commands?.forEach { c ->
                    Text(
                        c.name,
                        Modifier.fillMaxWidth().clickable { extRt?.engine?.runCommand(c.name); showExtCommands = false }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    if (showComposer && herdr != null) {
        val pid = herdrState.focusedPaneId
        if (pid == null) {
            showComposer = false
        } else {
            key(pid) {
                ComposerSheet(
                    draftKey = "${session.id}/$pid",
                    onSend = { t ->
                        val bt = paneTerminals[pid]
                        if (bt != null && bt.attached.value) {
                            // paste() wraps in bracketed paste when the app enabled it and normalises newlines.
                            bt.emulator.paste(t)
                            delay(40) // keep the paste end and Enter in separate writes
                            bt.sendText("\r")
                            true
                        } else {
                            herdr.sendInputChecked(pid, t.replace("\r\n", "\r").replace('\n', '\r'), listOf("enter"))
                        }
                    },
                    onDismiss = {
                        showComposer = false
                        activeView.post { activeView.requestFocus() }
                    },
                )
            }
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
