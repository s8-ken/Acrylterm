package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrException
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.herdr.AGENT_KINDS
import com.shibaken.androidterminal.herdr.PaneInfo
import com.shibaken.androidterminal.herdr.PendingTrust
import com.shibaken.androidterminal.herdr.TabInfo
import com.shibaken.androidterminal.herdr.WorkspaceInfo
import com.shibaken.androidterminal.herdr.WorktreeEntry
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.input.ImeAction

/** Workspace → tab → pane tree with agent status; tap to focus. [title] is the host / session name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HerdrDrawerContent(state: HerdrState, herdr: HerdrController, title: String, showVersion: Boolean, onNavigated: () -> Unit) {
    var newWorkspace by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PaneInfo?>(null) }
    var closing by remember { mutableStateOf<PaneInfo?>(null) }
    var renaming by remember { mutableStateOf<PaneInfo?>(null) }
    var renamingTab by remember { mutableStateOf<TabInfo?>(null) }
    var renamingWs by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var closingWs by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var removingWs by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var worktreeWs by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var newTabMenu by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = buildString {
                    if (showVersion && state.version.isNotBlank()) append("v${state.version}  ")
                    append(
                        when (state.phase) {
                            HerdrState.Phase.READY -> "接続済み"
                            HerdrState.Phase.CONNECTING -> "接続中…"
                            HerdrState.Phase.ERROR -> "エラー"
                            HerdrState.Phase.IDLE -> "未接続"
                        }
                    )
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { newWorkspace = true }) { Icon(Icons.Filled.Add, null); Text(" ワークスペース") }
                    // Tap = plain shell tab; the arrow opens the agent menu.
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { herdr.newTab(); onNavigated() }) { Icon(Icons.Filled.Add, null); Text(" タブ") }
                            IconButton(onClick = { newTabMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ArrowDropDown, "タブの種類") }
                        }
                        NewTabMenu(newTabMenu, { newTabMenu = false }, herdr, state.focusedWorkspaceId, onNavigated)
                    }
                }
            }
            HorizontalDivider()
        }

        state.workspaces.forEach { ws ->
            item(key = "ws-${ws.workspaceId}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { herdr.focusWorkspace(ws.workspaceId); onNavigated() }
                        .background(if (ws.workspaceId == state.focusedWorkspaceId) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(ws.agentStatus)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ws.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ws.worktree?.repoName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("${ws.number}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var wsMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { wsMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "ワークスペースの操作") }
                        DropdownMenu(wsMenu, { wsMenu = false }) {
                            DropdownMenuItem(text = { Text("名前を変更") }, onClick = { wsMenu = false; renamingWs = ws })
                            DropdownMenuItem(text = { Text("ワークツリーを作成") }, onClick = { wsMenu = false; worktreeWs = ws })
                            if (ws.worktree?.isLinkedWorktree == true) {
                                DropdownMenuItem(text = { Text("ワークツリーを削除") }, onClick = { wsMenu = false; removingWs = ws })
                            }
                            DropdownMenuItem(text = { Text("閉じる") }, onClick = { wsMenu = false; closingWs = ws })
                        }
                    }
                }
            }
            state.tabs.filter { it.workspaceId == ws.workspaceId }.forEach { tab ->
                item(key = "tab-${tab.tabId}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onLongClick = { renamingTab = tab }) { herdr.focusTab(tab.tabId); onNavigated() }
                            .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tab.tabId == state.focusedTabId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.panes.filter { it.tabId == tab.tabId }.forEach { pane ->
                    item(key = "pane-${pane.paneId}") {
                        PaneRow(
                            pane,
                            focused = pane.paneId == state.focusedPaneId,
                            onClick = { herdr.focusPane(pane.paneId); onNavigated() },
                            onPreview = { preview = pane },
                            onRename = { renaming = pane },
                            onClose = { closing = pane },
                            onEnter = { herdr.sendText(pane.paneId, "", pressEnter = true) },
                        )
                    }
                }
            }
        }
    }

    if (newWorkspace) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newWorkspace = false },
            title = { Text("新しいワークスペース") },
            text = { OutlinedTextField(label, { label = it }, label = { Text("名前(任意)") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { herdr.newWorkspace(label); newWorkspace = false; onNavigated() }) { Text("作成") }
            },
            dismissButton = { TextButton(onClick = { newWorkspace = false }) { Text("キャンセル") } },
        )
    }
    closing?.let { p ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("ペインを閉じる") },
            text = { Text("「${p.title2}」を閉じます。実行中のプロセスは終了します。") },
            confirmButton = { TextButton(onClick = { herdr.closePane(p.paneId); closing = null }) { Text("閉じる") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("キャンセル") } },
        )
    }
    renaming?.let { p -> RenameDialog("ペイン名を変更", p.label ?: "", { herdr.renamePane(p.paneId, it) }, { renaming = null }) }
    renamingTab?.let { t -> RenameDialog("タブ名を変更", t.label, { herdr.renameTab(t.tabId, it) }, { renamingTab = null }) }
    renamingWs?.let { w -> RenameDialog("ワークスペース名を変更", w.label, { herdr.renameWorkspace(w.workspaceId, it) }, { renamingWs = null }) }
    closingWs?.let { w ->
        AlertDialog(
            onDismissRequest = { closingWs = null },
            title = { Text("ワークスペースを閉じる") },
            text = { Text("「${w.title}」を閉じます。実行中のプロセスは終了します。") },
            confirmButton = { TextButton(onClick = { herdr.closeWorkspace(w.workspaceId); closingWs = null }) { Text("閉じる") } },
            dismissButton = { TextButton(onClick = { closingWs = null }) { Text("キャンセル") } },
        )
    }
    removingWs?.let { w ->
        AlertDialog(
            onDismissRequest = { removingWs = null },
            title = { Text("ワークツリーを削除") },
            text = { Text("「${w.title}」のワークツリーを削除します。") },
            confirmButton = { TextButton(onClick = { herdr.removeWorktree(w.workspaceId, force = false); removingWs = null }) { Text("削除") } },
            dismissButton = { TextButton(onClick = { removingWs = null }) { Text("キャンセル") } },
        )
    }
    worktreeWs?.let { w -> WorktreeDialog(w.workspaceId, w.title, herdr, onDismiss = { worktreeWs = null }, onNavigated = onNavigated) }
    preview?.let { p -> PanePreviewDialog(p, herdr, onDismiss = { preview = null }, onFocus = { herdr.focusPane(p.paneId); preview = null; onNavigated() }) }
}

@Composable
private fun PaneRow(
    pane: PaneInfo,
    focused: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onEnter: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 40.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(pane.agentStatus)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                pane.title2,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                pane.statusLabel.takeIf { it.isNotBlank() },
                (pane.foregroundCwd ?: pane.cwd)?.replace(Regex("^/Users/[^/]+"), "~"),
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("内容をプレビュー") }, onClick = { menu = false; onPreview() })
                if (pane.agentStatus == AgentStatus.BLOCKED) {
                    DropdownMenuItem(text = { Text("Enter を送る(承認)") }, onClick = { menu = false; onEnter() })
                }
                DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("閉じる") }, onClick = { menu = false; onClose() })
            }
        }
    }
}

@Composable
fun StatusDot(status: String?) {
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(StatusColors.of(status).takeIf { status != AgentStatus.UNKNOWN } ?: MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun PanePreviewDialog(pane: PaneInfo, herdr: HerdrController, onDismiss: () -> Unit, onFocus: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf("") }
    LaunchedEffect(pane.paneId) {
        text = try {
            herdr.readPane(pane.paneId, 60)
        } catch (e: Exception) {
            "読み込めませんでした: ${e.message}"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pane.title2, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Box(
                    Modifier
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        .padding(8.dp)
                ) {
                    Text(text ?: "読み込み中…", fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    reply, { reply = it },
                    label = { Text("このペインに送信(Enter 付き)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                if (reply.isNotEmpty()) {
                    TextButton(onClick = { herdr.sendText(pane.paneId, reply, pressEnter = true); reply = ""; onDismiss() }) { Text("送信") }
                }
                TextButton(onClick = onFocus) { Text("このペインへ移動") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** One small rename dialog shared by pane / tab / workspace. */
@Composable
internal fun RenameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var label by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(label, { label = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(label); onDismiss() }, enabled = label.isNotBlank()) { Text("変更") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** Shell + agent entries for creating a tab. */
@Composable
internal fun NewTabMenu(expanded: Boolean, onDismiss: () -> Unit, herdr: HerdrController, workspaceId: String?, onNavigated: () -> Unit = {}) {
    DropdownMenu(expanded, onDismiss) {
        DropdownMenuItem(text = { Text("シェル") }, onClick = { onDismiss(); herdr.newTab(workspaceId); onNavigated() })
        AGENT_KINDS.forEach { (kind, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { onDismiss(); herdr.newAgentTab(kind, workspaceId); onNavigated() })
        }
    }
}

/** Create a worktree from [workspaceId]'s repository, or open / focus an existing one. */
@Composable
internal fun WorktreeDialog(workspaceId: String, workspaceTitle: String, herdr: HerdrController, onDismiss: () -> Unit, onNavigated: () -> Unit) {
    var branch by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<WorktreeEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askTrust by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0 to false) }
    LaunchedEffect(workspaceId, reload) {
        list = null; error = null
        try {
            list = herdr.listWorktrees(workspaceId, trust = reload.second)
        } catch (e: Exception) {
            error = when {
                herdr.needsTrust(e) -> { askTrust = true; null }
                e is HerdrException && e.code == "not_git_worktree" -> "Git リポジトリの外です"
                else -> e.message?.lineSequence()?.firstOrNull()?.take(80) ?: "読み込めませんでした"
            }
        }
    }
    if (askTrust) {
        AlertDialog(
            onDismissRequest = { askTrust = false; onDismiss() },
            title = { Text("このリポジトリを信頼して続行しますか？") },
            confirmButton = { TextButton(onClick = { askTrust = false; reload = reload.first + 1 to true }) { Text("信頼して続行") } },
            dismissButton = { TextButton(onClick = { askTrust = false; onDismiss() }) { Text("キャンセル") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ワークツリー · $workspaceTitle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(branch, { branch = it }, label = { Text("ブランチ名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(base, { base = it }, label = { Text("元にするブランチ(任意)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { herdr.createWorktree(workspaceId, branch, base.ifBlank { null }); onDismiss(); onNavigated() },
                    enabled = branch.isNotBlank() && list != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("作成") }
                HorizontalDivider()
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    val items = list
                    when {
                        error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        items == null -> if (!askTrust) CircularProgressIndicator(Modifier.size(24.dp))
                        else -> items.filter { !it.isBare }.forEach { w ->
                            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                if (w.openWorkspaceId != null) herdr.focusWorkspace(w.openWorkspaceId)
                                else herdr.openWorktree(workspaceId, w.path)
                                onDismiss(); onNavigated()
                            }.padding(vertical = 6.dp), verticalArrangement = Arrangement.Center) {
                                Text(w.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (w.openWorkspaceId != null) FontWeight.SemiBold else FontWeight.Normal)
                                Text(w.dirName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** Confirmations driven by controller state (trust, group close, forced worktree removal). Mount once per screen. */
@Composable
fun HerdrPendingDialogs(herdr: HerdrController, state: HerdrState) {
    val trust by herdr.pendingTrust.collectAsState()
    val groupClose by herdr.pendingGroupClose.collectAsState()
    val removeFailure by herdr.worktreeRemoveFailure.collectAsState()
    if (trust != null) {
        AlertDialog(
            onDismissRequest = { herdr.dismissTrust() },
            title = { Text("このリポジトリを信頼して続行しますか？") },
            confirmButton = { TextButton(onClick = { herdr.confirmTrust() }) { Text("信頼して続行") } },
            dismissButton = { TextButton(onClick = { herdr.dismissTrust() }) { Text("キャンセル") } },
        )
    }
    if (groupClose != null) {
        AlertDialog(
            onDismissRequest = { herdr.dismissGroupClose() },
            title = { Text("紐づくワークツリーのワークスペースもまとめて閉じますか？") },
            confirmButton = { TextButton(onClick = { herdr.confirmGroupClose() }) { Text("まとめて閉じる") } },
            dismissButton = { TextButton(onClick = { herdr.dismissGroupClose() }) { Text("キャンセル") } },
        )
    }
    removeFailure?.let { (id, message) ->
        AlertDialog(
            onDismissRequest = { herdr.dismissWorktreeRemoveFailure() },
            title = { Text("ワークツリーを削除できませんでした") },
            text = { Text("${state.workspace(id)?.title.orEmpty()}\n$message\n\n強制削除すると未コミットの変更は失われます。") },
            confirmButton = { TextButton(onClick = { herdr.removeWorktree(id, force = true) }) { Text("強制削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { herdr.dismissWorktreeRemoveFailure() }) { Text("キャンセル") } },
        )
    }
}
