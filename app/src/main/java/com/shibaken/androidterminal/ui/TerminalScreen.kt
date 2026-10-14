package com.shibaken.androidterminal.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.shibaken.androidterminal.session.GenState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.herdr.withPendingTabsHidden
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.BulkCloseKind
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
    val appSettings by app.settings.settings.collectAsState()
    val extensionsEnabled = appSettings.extensionsEnabled
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
    val selectedHerdrSession by session.selectedHerdrSession.collectAsState()
    val connectedHerdrSession by session.connectedHerdrSession.collectAsState()
    val sessionPicker by session.sessionPicker.collectAsState()
    var showSessionPicker by remember { mutableStateOf(false) }
    val herdr = session.herdr
    val rawHerdrState = herdr?.state?.collectAsState()?.value ?: HerdrState()
    val pendingCloses = herdr?.pendingTabCloses?.collectAsState()?.value.orEmpty()
    val herdrState = rawHerdrState.withPendingTabsHidden(pendingCloses)
    val workspaceRequired = herdr?.workspaceRequired?.collectAsState()?.value == true
    val creationBusy = herdr?.creationBusy?.collectAsState()?.value == true
    val bulkCloseResult = herdr?.bulkCloseResult?.collectAsState()?.value

    // Re-read modifier state for the extra-keys highlight.
    var modTick by remember { mutableIntStateOf(0) }
    val modifiers = remember { Modifiers { modTick++ } }
    var directInput by remember { mutableStateOf(false) }
    var confirmClosePane by remember { mutableStateOf(false) }
    var confirmCloseConnection by remember { mutableStateOf(false) }
    var terminalFullscreen by rememberSaveable(sessionId) { mutableStateOf(false) }

    val view = remember(sessionId) {
        TerminalCanvasView(context).apply {
            this.modifiers = modifiers
            this.textSizeSp = session.profile.fontSizeSp
            onTextSizeChanged = { size -> app.hosts.get(session.profile.id)?.let { app.hosts.save(it.copy(fontSizeSp = size)) } }
        }
    }
    val pool = remember(sessionId, selectedHerdrSession) {
        PaneViewPool(context, modifiers, session.profile.fontSizeSp) { size ->
            app.hosts.get(session.profile.id)?.let { app.hosts.save(it.copy(fontSizeSp = size)) }
        }
    }
    SideEffect {
        view.backgroundOpacity = appearance.backgroundOpacity
        pool.backgroundOpacity = appearance.backgroundOpacity
        view.directInput = directInput
        pool.directInput = directInput
    }
    DisposableEffect(pool) { onDispose { pool.clear() } }

    // PANES mode: one view per pane of the focused tab (PaneLayout). SHELL / LEGACY_TUI: the single view.
    val mode by session.mode.collectAsState()
    val paneTerminals by session.panes.collectAsState()
    val isPanes = mode == SessionMode.PANES && herdr != null
    val fullscreen = isPanes && terminalFullscreen
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
    var showExtGenerate by remember { mutableStateOf(false) }
    var extGenerateName by remember { mutableStateOf("") }
    var extGenerateRequest by remember { mutableStateOf("") }
    var extGenerateAgent by remember { mutableStateOf("") }
    var showExtRunning by remember { mutableStateOf(false) }
    var showExtResult by remember { mutableStateOf(false) }
    var extFlying by remember { mutableStateOf(false) }
    var extFlightStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var extBadgePos by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var extOverlayOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val extReveal = extRt?.pendingResultReveal?.collectAsState()?.value ?: false
    val extGenState = extRt?.genState?.collectAsState()?.value ?: GenState.Idle
    var showOverview by remember { mutableStateOf(false) }
    var showComposer by remember { mutableStateOf(false) }

    LaunchedEffect(selectedHerdrSession) {
        showComposer = false
        showOverview = false
        confirmClosePane = false
    }

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

    // The composer sheet is a separate window: its appearance and the IME it raises used to shrink the
    // terminal (resize -> reflow + window-change to the remote -> full redraw, twice per open/close). Keep the
    // terminal's height frozen while the sheet is up and until its IME has gone away again.
    var composerHold by remember { mutableStateOf(false) }
    LaunchedEffect(showComposer) { if (showComposer) composerHold = true }
    // Release only once the IME has stayed hidden for a moment: on dismissal the main window reports a transient
    // "IME hidden" (inset 0) before the real hide animation starts, and releasing then resizes the terminal ~15 times.
    LaunchedEffect(showComposer, imeVisible) {
        if (!showComposer && !imeVisible) { kotlinx.coroutines.delay(600); composerHold = false }
    }
    val imeLive = WindowInsets.ime
    val freezeTerminalHeight = showComposer || composerHold
    // IME state as the layout sees it: also frozen, because rows that hide while the IME is up change the terminal's height.
    val imeInsets = remember { FreezableImeInsets(imeLive) }
    SideEffect { imeInsets.frozen = freezeTerminalHeight }
    val imeHolder = remember { booleanArrayOf(imeVisible) }
    if (!freezeTerminalHeight) imeHolder[0] = imeVisible
    val imeForLayout = imeHolder[0]

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    // Keep fullscreen's handler active while IME insets animate, so a fast second Back
    // cannot escape to host navigation before Compose observes that the keyboard closed.
    BackHandler(enabled = fullscreen && !drawerState.isOpen && !showComposer && !showOverview) {
        val showingIme = window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
            ?.isVisible(WindowInsetsCompat.Type.ime()) ?: imeVisible
        if (showingIme) activeView.hideKeyboard() else terminalFullscreen = false
    }
    DisposableEffect(window, fullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
            }
        }
    }

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
                .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier.systemBarsPadding())
                .windowInsetsPadding(imeInsets)
        ) {
        Column(Modifier.fillMaxSize()
            .then(if (showOverview) Modifier.clearAndSetSemantics { } else Modifier)
            .padding(horizontal = if (fullscreen) 0.dp else 12.dp, vertical = if (fullscreen) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (fullscreen) 0.dp else 8.dp)) {
            val sessionMenu: @Composable () -> Unit = {
                // Local state: opening the menu recomposes only this small scope, not the whole screen.
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "メニュー") }
                    DropdownMenu(menu, { menu = false }) {
                        val tint = CyanVeil.Muted
                        @Composable fun Item(label: String, icon: ImageVector, onClick: () -> Unit) =
                            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null, tint = tint) }, onClick = { menu = false; onClick() })
                        Item("ホスト一覧", Icons.AutoMirrored.Filled.ArrowBack) { onBack() }
                        if (herdr != null) {
                            Item("ワークスペース一覧", Icons.Filled.Menu) { scope.launch { drawerState.open() } }
                            Item("タブ一覧", Icons.Filled.GridView) { showOverview = true }
                            Item("セッション一覧", Icons.Filled.Dns) { showSessionPicker = true; session.refreshHerdrSessions() }
                        }
                        if (extensionsEnabled && session.profile.extEnabled) {
                            HorizontalDivider()
                            if (extRt != null) Item("拡張一覧", Icons.AutoMirrored.Filled.ViewList) { showExtRunning = true }
                            if (session.profile.extLoadFromHost && extRt != null) {
                                Item("拡張の作成", Icons.Filled.AutoAwesome) { extGenerateName = ""; extGenerateRequest = ""; extGenerateAgent = ""; showExtGenerate = true }
                            }
                            Item("拡張の再読込", Icons.Filled.Refresh) { session.reloadExtensions(fetch = true) }
                        }
                        HorizontalDivider()
                        // Stays open so the size can be adjusted repeatedly.
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.TextFields, null, tint = tint)
                            Text("文字サイズ", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { activeView.textSizeSp -= 1f }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Remove, "文字を小さく", tint = CyanVeil.Cyan) }
                            IconButton(onClick = { activeView.textSizeSp += 1f }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "文字を大きく", tint = CyanVeil.Cyan) }
                        }
                        Item("設定", Icons.Filled.Tune) { onAppearance() }
                        HorizontalDivider()
                        if (state is ConnState.Disconnected) Item("再接続", Icons.Filled.Refresh) { session.connect() }
                        Item("切断", Icons.Filled.LinkOff) { confirmCloseConnection = true }
                    }
                }
            }
            if (!fullscreen) {
            if (isPanes) {
                HerdrTabStrip(herdrState, herdr!!, onOpenOverview = { showOverview = true }, menu = sessionMenu)
            } else {
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
                VeilIconButton(R.drawable.veil_tune, "設定", onAppearance)
                sessionMenu()
            }

            }

            }

            if (creationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

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

            val terminalModifier = Modifier.weight(1f).fillMaxWidth().then(if (fullscreen) Modifier else
                Modifier.clip(MaterialTheme.shapes.large).border(1.dp, CyanVeil.Border, MaterialTheme.shapes.large).padding(8.dp))
            if (isPanes) {
                Column(terminalModifier) {
                    Row(Modifier.fillMaxWidth().height(if (fullscreen) 48.dp else 40.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val connectionTint = if (state is ConnState.Connected) CyanVeil.Mint else CyanVeil.Amber
                        BoxWithConstraints(Modifier.weight(1f).heightIn(min = 40.dp)
                            .clickable { showSessionPicker = true; session.refreshHerdrSessions() }
                            .semantics { contentDescription = "herdr セッションを選択、${session.profile.displayName} / $selectedHerdrSession" }, contentAlignment = Alignment.CenterStart) {
                            val hostMaxWidth = (maxWidth * .42f).coerceAtLeast(24.dp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("● ", color = connectionTint, style = MaterialTheme.typography.labelSmall)
                                Text(session.profile.displayName, Modifier.widthIn(max = hostMaxWidth), color = connectionTint,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(" / ", color = CyanVeil.Muted, style = MaterialTheme.typography.labelSmall)
                                Text(selectedHerdrSession, Modifier.weight(1f, fill = false), color = CyanVeil.Muted,
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = CyanVeil.Muted)
                            }
                        }
                        if (fullscreen) {
                            IconButton(onClick = { activeView.toggleKeyboard() }, modifier = Modifier.size(48.dp)) {
                                VeilIcon(R.drawable.veil_keyboard, "キーボードの表示/非表示", CyanVeil.Cyan)
                            }
                            IconButton(onClick = { terminalFullscreen = false }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.FullscreenExit, "全画面を解除", tint = CyanVeil.Cyan)
                            }
                        } else {
                            IconButton(onClick = { terminalFullscreen = true }, modifier = Modifier.size(48.dp)) {
                                VeilIcon(R.drawable.veil_expand, "全画面表示")
                            }
                            Text(herdrState.pane(herdrState.focusedPaneId)?.title2 ?: "タブなし", Modifier.widthIn(max = 112.dp),
                                style = MaterialTheme.typography.labelSmall, color = CyanVeil.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(CyanVeil.Border))
                    if (herdrState.focusedTabId == null) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("新しいタブを追加してください", color = CyanVeil.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        LaunchedEffect(session) { session.setVisiblePanes(emptySet()) }
                    } else key(selectedHerdrSession) { PaneLayout(
                        session = session, state = herdrState, herdr = herdr!!, pool = pool,
                        onOpenComposer = { showComposer = true },
                        inputEnabled = !showOverview && !drawerState.isOpen,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                    ) }
                    val waiting = herdrState.tabs.firstOrNull { tab -> tab.tabId != herdrState.focusedTabId &&
                        herdrState.panes.any { it.tabId == tab.tabId && it.agentStatus == AgentStatus.BLOCKED } }
                    if (!fullscreen && !imeForLayout && waiting != null) Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(CyanVeil.Raised).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("! ${waiting.title} が入力待ち", Modifier.weight(1f), color = CyanVeil.Amber, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { herdr!!.focusTab(waiting.tabId) }, modifier = Modifier.height(48.dp)) { Text("確認する") }
                    }
                }
            } else {
                AndroidView(factory = { view }, modifier = terminalModifier)
            }

            // --- tab strip (+ quick actions in the legacy TUI mode) -----------------------
            if (!fullscreen && herdr != null && herdrState.phase == HerdrState.Phase.READY) {
                if (!isPanes) HerdrTabStrip(herdrState, herdr, onOpenOverview = { showOverview = true }, menu = {})
                // Legacy TUI mode only (native panes need none); hidden while the IME is up to leave rows for the terminal.
                if (!isPanes && !imeForLayout) {
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
            if (!fullscreen) ExtraKeysBar(
                view = activeView,
                ctrlOn = modTick >= 0 && modifiers.ctrl,
                altOn = modTick >= 0 && modifiers.alt,
                onCtrl = { modifiers.ctrl = !modifiers.ctrl },
                onAlt = { modifiers.alt = !modifiers.alt },
                onKeyboard = { activeView.toggleKeyboard() },
                showPrefixKey = !isPanes,
                extKeys = extRegistry?.takeIf { extensionsEnabled }?.keys?.map { it.label } ?: emptyList(),
                onExtKey = { i -> extRt?.engine?.pressKey(i, if (isPanes) focusedPaneId else "shell") },
                showExtCommands = extensionsEnabled && extRegistry != null && extRegistry.commands.isNotEmpty(),
                onExtCommands = { showExtCommands = true },
            )
            if (!fullscreen && isPanes && !imeForLayout) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(100.dp)).background(CyanVeil.Surface)
                    .clickable { showComposer = true }.semantics { contentDescription = "返信を作成" }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    VeilIcon(R.drawable.veil_edit, null, CyanVeil.Cyan)
                }
            }
        }
        if (showOverview && herdr != null) {
            PaneOverview(herdrState, herdr, onDismiss = { showOverview = false },
                localPreview = { id -> paneTerminals[id]?.visibleLines()?.joinToString("\n") },
                modifier = Modifier.fillMaxSize().zIndex(1f))
        }
        pendingCloses.lastOrNull { it.undoable }?.let { close ->
            Row(Modifier.align(Alignment.BottomCenter).zIndex(2f).padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth().clip(MaterialTheme.shapes.medium).background(CyanVeil.Raised)
                .border(1.dp, CyanVeil.Border, MaterialTheme.shapes.medium).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("「${close.title}」を閉じました", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { herdr?.undoCloseTab(close.tabId) }, modifier = Modifier.height(48.dp)) { Text("元に戻す") }
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).zIndex(3f).padding(bottom = 96.dp))
        // Extension generation activity: bottom-right edge, above the extra-keys bar (and composer pill).
        if (extensionsEnabled && !fullscreen && (extGenState !is GenState.Idle || extFlying)) {
            Box(Modifier.align(Alignment.BottomEnd).zIndex(2f).padding(end = 12.dp, bottom = if (isPanes && !imeForLayout) 120.dp else 64.dp)
                .size(48.dp).onGloballyPositioned { extBadgePos = it.positionInWindow() }) {
                if (!extFlying) ExtActivityBadge(extGenState, onClick = {
                    if (extGenState is GenState.Running) {
                        extGenerateName = extGenState.name; extGenerateRequest = extGenState.request; extGenerateAgent = extGenState.agentCommand
                        showExtGenerate = true
                    } else showExtResult = true
                })
            }
        }
        if (extFlying) {
            val prog = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) {
                prog.animateTo(1f, androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                extFlying = false
            }
            val end = extBadgePos
            val start = extFlightStart ?: end?.let { androidx.compose.ui.geometry.Offset(it.x - 400f, it.y + 200f) }
            if (start != null && end != null) {
                ExtLaunchGlyph(Modifier.align(Alignment.TopStart).zIndex(5f).onGloballyPositioned { extOverlayOrigin = it.positionInWindow() }
                    .graphicsLayer {
                        val p = prog.value
                        val o = extOverlayOrigin
                        translationX = androidx.compose.ui.util.lerp(start.x - 24.dp.toPx(), end.x, p) - o.x
                        translationY = androidx.compose.ui.util.lerp(start.y - 24.dp.toPx(), end.y, p) - o.y
                        val sc = androidx.compose.ui.util.lerp(1.3f, 1f, p); scaleX = sc; scaleY = sc
                    })
            } else extFlying = false
        }
        }
        if (showComposer && herdr != null) {
        val pid = herdrState.focusedPaneId
        if (pid == null) {
            showComposer = false
        } else {
            key(pid) {
                ComposerPanel(
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

        }
    }

    if (workspaceRequired && herdr != null) {
        WorkspaceCreationDialog(missingActiveWorkspace = true, onCreate = { herdr.createWorkspaceForNewTab(it) },
            onDismiss = { herdr.cancelWorkspaceCreation() })
    }
    if (confirmCloseConnection) {
        AlertDialog(onDismissRequest = { confirmCloseConnection = false }, title = { Text("接続を閉じますか？") },
            text = { Text(if (herdr != null)
                "SSH/Mosh接続を閉じ、ホスト一覧へ戻ります。サーバー上のタブとワークスペースは閉じません。"
                else "SSH接続を閉じ、ホスト一覧へ戻ります。この接続のシェルや実行中の処理は終了する場合があります。") },
            confirmButton = { TextButton(onClick = { confirmCloseConnection = false; app.sessions.close(session.id); onBack() }) { Text("接続を閉じる") } },
            dismissButton = { TextButton(onClick = { confirmCloseConnection = false }) { Text("キャンセル") } })
    }
    bulkCloseResult?.let { result ->
        val unit = if (result.plan.kind == BulkCloseKind.TABS) "タブ" else "ワークスペース"
        AlertDialog(onDismissRequest = { herdr?.dismissBulkCloseResult() },
            title = { Text(if (result.failures.isEmpty()) "一括終了しました" else "一部を閉じられませんでした") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${result.plan.ids.distinct().size} 件中 ${result.completed} 件の${unit}を終了しました（既に終了した対象を含みます）。")
                result.failures.take(5).forEach { failure ->
                    val label = if (result.plan.kind == BulkCloseKind.TABS) rawHerdrState.tabs.firstOrNull { it.tabId == failure.id }?.title
                        else rawHerdrState.workspace(failure.id)?.title
                    Text("${label ?: failure.id}: ${failure.reason}", style = MaterialTheme.typography.bodySmall)
                }
                if (result.failures.size > 5) Text("ほか ${result.failures.size - 5} 件", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = { herdr?.dismissBulkCloseResult() }) { Text("OK") } })
    }

    if (showSessionPicker) {
        HerdrSessionSheet(session.profile.displayName, sessionPicker, connectedHerdrSession.takeIf { rawHerdrState.phase == HerdrState.Phase.READY && state is ConnState.Connected },
            onRefresh = session::refreshHerdrSessions, onSelect = session::selectHerdrSession,
            onDismiss = { showSessionPicker = false })
    }

    if (extensionsEnabled && showExtCommands && extRt != null) {
        ExtensionCommandsSheet(commands = extRegistry?.commands.orEmpty(),
            onRun = { name -> extRt?.engine?.runCommand(name); showExtCommands = false },
            onDismiss = { showExtCommands = false })
    }

    if (extensionsEnabled && showExtGenerate) {
        extRt?.let { rt ->
            ExtGenerateSheet(ext = rt, hostId = session.profile.id, app = app, initialName = extGenerateName, initialRequest = extGenerateRequest,
                initialAgent = extGenerateAgent,
                onLaunched = { pos ->
                    val scale = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                    if (scale > 0f) { extFlightStart = pos; extFlying = true }
                },
                onDismiss = { showExtGenerate = false })
        }
    }
    if (extensionsEnabled && showExtRunning) {
        extRt?.let { rt ->
            RunningExtensionsSheet(ext = rt, session = session, app = app, onDismiss = { showExtRunning = false })
        }
    }
    LaunchedEffect(extReveal, extGenState) {
        if (extReveal && (extGenState is GenState.Done || extGenState is GenState.Failed)) {
            extRt?.consumeResultReveal(); showExtResult = true
        }
    }
    if (extensionsEnabled && showExtResult && extGenState !is GenState.Running) {
        ExtResultDialog(extGenState,
            onClose = { showExtResult = false; extRt?.clearGenState() },
            onEdit = { n -> showExtResult = false; extGenerateName = n; extGenerateRequest = ""; extGenerateAgent = ""; extRt?.clearGenState(); showExtGenerate = true },
            onRetry = {
                showExtResult = false
                val f = extGenState as? GenState.Failed
                extGenerateName = f?.name.orEmpty(); extGenerateRequest = f?.request.orEmpty(); extGenerateAgent = f?.agentCommand.orEmpty()
                extRt?.clearGenState(); showExtGenerate = true })
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

/**
 * IME insets that stop following the keyboard while [frozen] (the last value is kept). Reads are observed by the
 * layout phase, so unfreezing re-lays out.
 */
private class FreezableImeInsets(private val live: WindowInsets) : WindowInsets {
    var frozen by mutableStateOf(false)
    private var cached = 0
    override fun getBottom(density: Density): Int = if (frozen) cached else live.getBottom(density).also { cached = it }
    override fun getTop(density: Density): Int = 0
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = 0
    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = 0
}
