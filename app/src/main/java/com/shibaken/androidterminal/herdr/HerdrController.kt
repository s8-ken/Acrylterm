package com.shibaken.androidterminal.herdr

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import android.util.Log
import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.ssh.RemoteCommand
import com.shibaken.androidterminal.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

data class HerdrState(
    val phase: Phase = Phase.IDLE,
    val version: String = "",
    val message: String? = null,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    /** Per-tab geometry keyed by tab id. */
    val layouts: Map<String, TabLayout> = emptyMap(),
    val focusedWorkspaceId: String? = null,
    val focusedTabId: String? = null,
    val focusedPaneId: String? = null,
) {
    enum class Phase { IDLE, CONNECTING, READY, ERROR }

    val blockedCount: Int get() = panes.count { it.agentStatus == AgentStatus.BLOCKED }
    fun pane(id: String?): PaneInfo? = panes.firstOrNull { it.paneId == id }
    fun workspace(id: String?): WorkspaceInfo? = workspaces.firstOrNull { it.workspaceId == id }
    fun layout(tabId: String?): TabLayout? = layouts[tabId]
}

/** Callback for agent status transitions worth telling the user about. */
fun interface AgentAlertListener {
    fun onAgentStatus(pane: PaneInfo, workspace: WorkspaceInfo?, status: String)
}

/**
 * Mirrors herdr's session state over the socket API and exposes actions for the native UI.
 *
 * Protocol (see docs/herdr-api.md): subscribe to lifecycle events, read session.snapshot,
 * treat every lifecycle event as "something changed → re-read the snapshot" (debounced),
 * and keep a second subscription with one pane.agent_status_changed entry per pane
 * (herdr has no session-wide agent status subscription), rebuilt when the pane set changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HerdrController(private val alerts: AgentAlertListener) {

    // Single-threaded so the loop's bookkeeping needs no locks; blocking I/O happens in HerdrClient.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val inputQueue = PaneInputQueue(scope)
    private val _state = MutableStateFlow(HerdrState())
    val state: StateFlow<HerdrState> = _state.asStateFlow()
    private val deferredCloses = DeferredTabClosures(scope)
    val pendingTabCloses = deferredCloses.pending
    private val operationLock = Any()
    private var operationGeneration = 0L
    private var creationJob: Job? = null
    private var bulkJob: Job? = null
    private val _workspaceRequired = MutableStateFlow(false)
    val workspaceRequired: StateFlow<Boolean> = _workspaceRequired.asStateFlow()
    private val _creationBusy = MutableStateFlow(false)
    val creationBusy: StateFlow<Boolean> = _creationBusy.asStateFlow()
    private val _pendingTrust = MutableStateFlow<PendingTrust?>(null)
    /** Set when a worktree open needs the user's OK to trust the repository; retried only via [confirmTrust]. */
    val pendingTrust: StateFlow<PendingTrust?> = _pendingTrust.asStateFlow()
    private val _pendingGroupClose = MutableStateFlow<String?>(null)
    /** Workspace id whose close needs its linked worktree workspaces closed too (asks the user first). */
    val pendingGroupClose: StateFlow<String?> = _pendingGroupClose.asStateFlow()
    private val _bulkClosing = MutableStateFlow(false)
    val bulkClosing: StateFlow<Boolean> = _bulkClosing.asStateFlow()
    private val _bulkCloseResult = MutableStateFlow<BulkCloseResult?>(null)
    val bulkCloseResult: StateFlow<BulkCloseResult?> = _bulkCloseResult.asStateFlow()

    // Action failures (not connection-phase errors) for a snackbar; `message` is still set too.
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Every agent status transition: (pane after the change, previous status). Called on the controller thread. */
    @Volatile var onAgentTransition: ((PaneInfo, String) -> Unit)? = null

    private var loopJob: Job? = null
    @Volatile private var client: HerdrClient? = null

    fun attach(conn: SshConnection, herdrSession: String, socketPath: String? = null, resetState: Boolean = false) {
        cancelInteractiveOperations()
        deferredCloses.cancelAll()
        loopJob?.cancel()
        client = null
        _state.update { (if (resetState) HerdrState() else it).copy(phase = HerdrState.Phase.CONNECTING, message = null) }
        loopJob = scope.launch { runLoop(conn, herdrSession, socketPath) }
    }

    fun detach(resetState: Boolean = false) {
        cancelInteractiveOperations()
        deferredCloses.cancelAll()
        loopJob?.cancel()
        loopJob = null
        client = null
        _state.update { (if (resetState) HerdrState() else it).copy(phase = HerdrState.Phase.IDLE, message = App.str(R.string.core_disconnected)) }
    }

    fun dispose() {
        cancelInteractiveOperations()
        deferredCloses.cancelAll()
        scope.cancel()
    }

    private suspend fun resolveSocketPath(conn: SshConnection, herdrSession: String): String {
        // sshd does not expand "~" for streamlocal, so resolve the absolute path on the Mac.
        val base = "\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr"
        val expr = if (herdrSession.isNotBlank() && herdrSession != "default") {
            "$base/sessions/${herdrSession.replace(Regex("[^A-Za-z0-9._-]"), "")}/herdr.sock"
        } else if (herdrSession == "default") {
            "$base/herdr.sock"
        } else {
            "\${HERDR_SOCKET_PATH:-$base/herdr.sock}"
        }
        val path = conn.exec("printf '%s' \"$expr\"")
        if (!path.startsWith("/")) throw IOException(App.str(R.string.core_socket_path_failed, path))
        return path
    }

    private suspend fun runLoop(conn: SshConnection, herdrSession: String, knownSocketPath: String?) {
        var backoff = 1_000L
        var socketPath: String? = knownSocketPath
        while (currentCoroutineContext().isActive) {
            try {
                _state.update { it.copy(phase = HerdrState.Phase.CONNECTING, message = App.str(R.string.core_connecting)) }
                if (!conn.isConnected) throw IOException(App.str(R.string.core_ssh_disconnected))
                val path = socketPath ?: resolveSocketPath(conn, herdrSession).also { socketPath = it }
                val c = HerdrClient(conn, path)
                val pong = c.call("ping")
                currentCoroutineContext().ensureActive()
                val version = pong["version"]?.jsonPrimitive?.contentOrNull ?: ""
                client = c
                backoff = 1_000L
                _state.update { it.copy(version = version) }
                runSession(c, version)
                throw IOException(App.str(R.string.core_events_ended))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                client = null
                cancelInteractiveOperations()
                deferredCloses.cancelAll()
                val msg = when {
                    e is HerdrException && e.code == "unreachable" -> App.str(R.string.core_server_not_running)
                    else -> e.message ?: e.toString()
                }
                Log.w(TAG, "herdr loop: $msg", e)
                _state.update { it.copy(phase = HerdrState.Phase.ERROR, message = msg) }
                if (!conn.isConnected) return
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000)
            }
        }
    }

    private suspend fun runSession(c: HerdrClient, version: String) = coroutineScope {
        val legacy = !RemoteCommand.versionAtLeast(version, 0, 9) // 0.6-0.8 replay old events on subscribe
        val refresh = Channel<Unit>(Channel.CONFLATED)
        var agentJob: Job? = null
        var subscribedPanes: Set<String>? = null

        // Lifecycle events: used only as invalidation signals. A closed stream, an error line or
        // `events_lost` means events may have been missed: resync (snapshot + both subscriptions),
        // with exponential backoff so a persistently failing stream cannot spin.
        val lifecycle = launch {
            var backoff = RESYNC_MIN_MS
            var rapidFailures = 0
            while (true) {
                val startedAt = System.currentTimeMillis()
                try {
                    c.subscribe(LIFECYCLE_SUBSCRIPTIONS).collect { ev ->
                        if (isEventsLost(ev)) throw HerdrException("events_lost", "events_lost")
                        refresh.trySend(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "lifecycle subscription ended: ${e.message}")
                }
                if (System.currentTimeMillis() - startedAt > RESYNC_STABLE_MS) {
                    backoff = RESYNC_MIN_MS
                    rapidFailures = 0
                } else {
                    if (++rapidFailures >= RESYNC_MAX_RAPID) throw IOException(App.str(R.string.core_events_repeated))
                    backoff = (backoff * 2).coerceAtMost(RESYNC_MAX_MS)
                }
                delay(backoff)
                agentJob?.cancel()
                subscribedPanes = null
                refresh.trySend(Unit)
            }
        }

        refresh.trySend(Unit)
        for (signal in refresh) {
            delay(120) // debounce bursts (a focus change emits 3 events)
            val snapshot = c.call("session.snapshot")
            currentCoroutineContext().ensureActive()
            if (client !== c) return@coroutineScope
            applySnapshot(snapshot)
            val panes = _state.value.panes.map { it.paneId }.toSet()
            if (panes != subscribedPanes) {
                agentJob?.cancel()
                subscribedPanes = panes
                if (panes.isNotEmpty()) {
                    agentJob = launch {
                        val startedAt = System.currentTimeMillis()
                        try {
                            c.subscribe(panes.map { agentSubscription(it) }).collect { ev ->
                                if (isEventsLost(ev)) throw HerdrException("events_lost", "events_lost")
                                // 0.8.x replays old events on subscribe; ignore the replay window.
                                if (legacy && System.currentTimeMillis() - startedAt < 1_500) return@collect
                                onAgentEvent(ev)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "agent subscription ended: ${e.message}")
                        }
                        // A pane may have vanished between snapshot and subscribe, or events were lost: rebuild
                        // (delayed when the stream died quickly so a failing subscription cannot spin).
                        if (System.currentTimeMillis() - startedAt < RESYNC_STABLE_MS) delay(RESYNC_MIN_MS * 2)
                        subscribedPanes = null
                        refresh.trySend(Unit)
                    }
                }
            }
            if (lifecycle.isCompleted) break
            if (client === c) _state.update { it.copy(phase = HerdrState.Phase.READY, message = null) }
        }
    }

    private fun isEventsLost(ev: JsonObject): Boolean = HerdrClient.sameEvent(HerdrClient.eventName(ev), "events_lost")

    private fun applySnapshot(result: JsonObject) {
        val snapEl = result["snapshot"] ?: return
        val snap = AppJson.decodeFromJsonElement(SessionSnapshot.serializer(), snapEl)
        _state.update {
            it.copy(
                version = snap.version.ifBlank { it.version },
                workspaces = snap.workspaces.sortedBy { w -> w.number },
                tabs = snap.tabs.sortedBy { t -> t.number },
                panes = snap.panes,
                layouts = snap.layouts.associateBy { l -> l.tabId },
                focusedWorkspaceId = snap.resolvedFocusedWorkspaceId,
                focusedTabId = snap.resolvedFocusedTabId,
                focusedPaneId = snap.resolvedFocusedPaneId,
            )
        }
        deferredCloses.reconcile(snap.tabs.mapTo(HashSet()) { it.tabId })
    }

    private fun onAgentEvent(ev: JsonObject) {
        if (!HerdrClient.sameEvent(HerdrClient.eventName(ev), "pane.agent_status_changed")) return
        val data = HerdrClient.eventData(ev) ?: return
        val e = runCatching { AppJson.decodeFromJsonElement(AgentStatusEvent.serializer(), data) }.getOrNull() ?: return
        val before = _state.value.pane(e.paneId) ?: return
        if (before.agentStatus == e.agentStatus) return
        val after = before.copy(
            agentStatus = e.agentStatus,
            agent = e.agent ?: before.agent,
            displayAgent = e.displayAgent ?: before.displayAgent,
            stateLabels = e.stateLabels.ifEmpty { before.stateLabels },
        )
        _state.update { s -> s.copy(panes = s.panes.map { if (it.paneId == e.paneId) after else it }) }
        onAgentTransition?.let { runCatching { it(after, before.agentStatus) } }
        if (e.agentStatus == AgentStatus.BLOCKED || e.agentStatus == AgentStatus.DONE) {
            alerts.onAgentStatus(after, _state.value.workspace(after.workspaceId), e.agentStatus)
        }
    }

    // --- actions ------------------------------------------------------------------------

    private fun act(method: String, params: JsonObject, after: (JsonObject) -> Unit = {}) {
        val c = client ?: run {
            _state.update { it.copy(message = App.str(R.string.core_not_connected)) }
            _errors.tryEmit(App.str(R.string.core_op_failed_not_connected))
            return
        }
        scope.launch {
            try {
                if (client !== c) return@launch
                val result = c.call(method, params)
                if (client === c) after(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (client !== c) return@launch
                Log.w(TAG, "$method failed: ${e.message}", e)
                _state.update { it.copy(message = App.str(R.string.core_op_failed)) }
                _errors.tryEmit(failureText(e))
            }
        }
    }

    fun focusPane(paneId: String) = act("pane.focus", buildJsonObject { put("pane_id", paneId) })
    fun focusTab(tabId: String) = act("tab.focus", buildJsonObject { put("tab_id", tabId) })

    /** Moves to the previous/next tab of the focused workspace (clamped, no wrap). */
    fun focusTabRelative(delta: Int) {
        val s = _state.value
        val visible = s.withPendingTabsHidden(pendingTabCloses.value)
        val tabs = visible.tabs.filter { it.workspaceId == visible.focusedWorkspaceId }
        val i = tabs.indexOfFirst { it.tabId == visible.focusedTabId }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, tabs.lastIndex)
        if (j != i) focusTab(tabs[j].tabId)
    }

    fun focusWorkspace(id: String) = act("workspace.focus", buildJsonObject { put("workspace_id", id) })

    /** direction: "right" (side by side) or "down" (stacked). */
    fun split(direction: String, paneId: String? = null) = act("pane.split", buildJsonObject {
        (paneId ?: _state.value.focusedPaneId)?.let { put("target_pane_id", it) }
        put("direction", direction)
        put("focus", true)
    })

    fun toggleZoom(paneId: String? = null) = act("pane.zoom", buildJsonObject {
        (paneId ?: _state.value.focusedPaneId)?.let { put("pane_id", it) }
        put("mode", "toggle")
    })

    fun focusDirection(direction: String) = act("pane.focus_direction", buildJsonObject {
        put("direction", direction)
    })

    fun closePane(paneId: String) = act("pane.close", buildJsonObject { put("pane_id", paneId) })

    fun closeTab(tabId: String) = act("tab.close", buildJsonObject { put("tab_id", tabId) })

    fun closeTabWithUndo(tabId: String) {
        if (_bulkClosing.value) return
        val visible = _state.value.withPendingTabsHidden(pendingTabCloses.value)
        val tab = visible.tabs.firstOrNull { it.tabId == tabId } ?: return
        val peers = visible.tabs.filter { it.workspaceId == tab.workspaceId }
        val i = peers.indexOf(tab)
        val next = peers.getOrNull(i + 1) ?: peers.getOrNull(i - 1)
            ?: visible.tabs.firstOrNull { it.tabId != tabId }
        val capturedClient = client
        val close = PendingTabClose(tabId, tab.title, visible.focusedTabId == tabId, next?.tabId)
        if (!deferredCloses.request(close) {
            if (capturedClient == null || client !== capturedClient) {
                _errors.tryEmit(App.str(R.string.core_tab_close_not_connected))
                false
            } else try {
                capturedClient.call("tab.close", buildJsonObject { put("tab_id", tabId) })
                true
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                _errors.tryEmit(App.str(R.string.core_tab_close_timeout))
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(failureText(e))
                false
            }
        }) return
        if (close.restoreFocus && next != null && capturedClient != null) focusTab(next.tabId)
    }

    fun undoCloseTab(tabId: String) {
        val close = deferredCloses.undo(tabId) ?: return
        // Preserve a deliberate navigation elsewhere while the Undo snackbar was visible.
        if (close.restoreFocus && _state.value.focusedTabId in listOf(tabId, close.replacementId) && client != null) focusTab(tabId)
    }


    /** Commits a divider drag. [path]: false = first child, true = second child, from the root split. */
    fun setSplitRatio(tabId: String, path: List<Boolean>, ratio: Float) = act("layout.set_split_ratio", buildJsonObject {
        put("tab_id", tabId)
        put("path", JsonArray(path.map { JsonPrimitive(it) }))
        put("ratio", ratio)
    })

    private fun cancelInteractiveOperations() = synchronized(operationLock) {
        operationGeneration++
        creationJob?.cancel(); creationJob = null
        bulkJob?.cancel(); bulkJob = null
        if (_bulkClosing.value) _errors.tryEmit(App.str(R.string.core_bulk_aborted))
        _workspaceRequired.value = false
        _pendingTrust.value = null
        _pendingGroupClose.value = null
        _creationBusy.value = false
        _bulkClosing.value = false
        _bulkCloseResult.value = null
    }

    private fun launchCreation(timeoutMs: Long = 10_000, action: suspend (HerdrRpc) -> TabCreationOutcome) = synchronized(operationLock) {
        if (_creationBusy.value || _bulkClosing.value) return@synchronized
        val c = client ?: run { _errors.tryEmit(App.str(R.string.core_op_failed_not_connected)); return@synchronized }
        val generation = ++operationGeneration
        _creationBusy.value = true
        creationJob = scope.launch {
            try {
                if (client !== c) return@launch
                val outcome = action(HerdrRpc { method, params -> c.call(method, params, timeoutMs) })
                synchronized(operationLock) {
                    if (generation == operationGeneration && client === c && outcome == TabCreationOutcome.NEEDS_WORKSPACE)
                        _workspaceRequired.value = true
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (client === c) _errors.tryEmit(App.str(R.string.core_op_failed_timeout))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (client === c) _errors.tryEmit(failureText(error))
            } finally {
                synchronized(operationLock) { if (generation == operationGeneration) _creationBusy.value = false }
            }
        }
    }

    fun newTab(workspaceId: String? = _state.value.focusedWorkspaceId) {
        if (_creationBusy.value || _bulkClosing.value) return
        val noWorkspaces = _state.value.workspaces.isEmpty()
        if (workspaceId == null && noWorkspaces) {
            _workspaceRequired.value = true
            return
        }
        launchCreation { rpc -> requestNewTab(workspaceId, noWorkspaces, rpc) }
    }

    /** Creates a tab, then starts [kind] in its root pane without holding the creation flag (agent.start can take ~30s). */
    fun newAgentTab(kind: String, workspaceId: String? = _state.value.focusedWorkspaceId) {
        if (_creationBusy.value || _bulkClosing.value) return
        val c = client ?: run { _errors.tryEmit(App.str(R.string.core_op_failed_not_connected)); return }
        val noWorkspaces = _state.value.workspaces.isEmpty()
        launchCreation { rpc ->
            val r = requestNewTabWithPane(workspaceId, noWorkspaces, rpc)
            // No workspace yet: make one (its root pane hosts the agent) rather than dropping the chosen kind.
            val pane = if (r.outcome == TabCreationOutcome.NEEDS_WORKSPACE) rootPaneId(createWorkspaceWithInitialTab(null, rpc))
                else r.rootPaneId
            if (pane != null) scope.launch {
                try {
                    if (client !== c) return@launch
                    startAgent(kind, pane, { _state.value.panes.flatMapTo(HashSet()) { listOfNotNull(it.agent, it.displayAgent) } },
                        HerdrRpc { method, params -> c.call(method, params, timeoutMs = 45_000) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (client === c) _errors.tryEmit(failureText(e))
                }
            }
            TabCreationOutcome.CREATED
        }
    }

    fun renameTab(tabId: String, label: String) = act("tab.rename", buildJsonObject {
        put("tab_id", tabId)
        put("label", label.trim())
    })

    fun renameWorkspace(workspaceId: String, label: String) = act("workspace.rename", buildJsonObject {
        put("workspace_id", workspaceId)
        put("label", label.trim())
    })

    // --- worktrees ----------------------------------------------------------------------

    /** Lists worktrees of [workspaceId]'s repository. Throws [HerdrException]; check [isTrustError] via [needsTrust]. */
    suspend fun listWorktrees(workspaceId: String, trust: Boolean = false): List<WorktreeEntry> {
        val c = client ?: throw IOException(App.str(R.string.core_not_connected))
        return parseWorktrees(c.call("worktree.list", worktreeListParams(workspaceId, trust)))
    }

    fun needsTrust(e: Exception) = isTrustError(e)

    fun openWorktree(sourceWorkspaceId: String, path: String, trust: Boolean = false) = launchCreation(WORKTREE_TIMEOUT_MS) { rpc ->
        if (requestWorktreeOpen(sourceWorkspaceId, path, trust, rpc) == WorktreeOutcome.NEEDS_TRUST)
            _pendingTrust.value = PendingTrust.Open(sourceWorkspaceId, path)
        TabCreationOutcome.CREATED
    }

    fun confirmTrust() {
        val p = _pendingTrust.value ?: return
        _pendingTrust.value = null
        when (p) {
            is PendingTrust.Open -> openWorktree(p.sourceWorkspaceId, p.path, trust = true)
        }
    }

    fun dismissTrust() { _pendingTrust.value = null }

    /** Closes one workspace; if it has linked worktree workspaces, asks via [pendingGroupClose] before closing them too. */
    fun closeWorkspace(workspaceId: String, group: Boolean = false) {
        val c = client ?: run { _errors.tryEmit(App.str(R.string.core_op_failed_not_connected)); return }
        _pendingGroupClose.value = null
        scope.launch {
            try {
                if (client !== c) return@launch
                val needsGroup = requestWorkspaceClose(workspaceId, group, HerdrRpc { m, p -> c.call(m, p) })
                if (needsGroup && client === c) _pendingGroupClose.value = workspaceId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (client === c) _errors.tryEmit(failureText(e))
            }
        }
    }

    fun confirmGroupClose() {
        val id = _pendingGroupClose.value ?: return
        closeWorkspace(id, group = true)
    }

    fun dismissGroupClose() { _pendingGroupClose.value = null }

    fun cancelWorkspaceCreation() { _workspaceRequired.value = false }

    fun createWorkspaceForNewTab(label: String?) {
        if (!_workspaceRequired.value) return
        _workspaceRequired.value = false
        newWorkspace(label)
    }

    fun newWorkspace(label: String?) = launchCreation { rpc -> createWorkspaceWithInitialTab(label, rpc); TabCreationOutcome.CREATED }

    fun dismissBulkCloseResult() { _bulkCloseResult.value = null }

    fun closeAllConfirmed(plan: BulkClosePlan) = synchronized(operationLock) {
        if (_bulkClosing.value || _creationBusy.value || plan.ids.isEmpty()) return@synchronized
        val c = client ?: run { _errors.tryEmit(App.str(R.string.core_bulk_not_connected)); return@synchronized }
        val generation = ++operationGeneration
        _bulkClosing.value = true
        _bulkCloseResult.value = null
        bulkJob = scope.launch {
            try {
                val affectedTabs = if (plan.kind == BulkCloseKind.TABS) plan.ids.toSet()
                    else _state.value.tabs.filter { it.workspaceId in plan.ids }.mapTo(HashSet()) { it.tabId }
                val alreadyClosing = deferredCloses.prepareForBulk(affectedTabs)
                if (client !== c) return@launch
                val result = runBulkClose(plan, alreadyClosing,
                    existingIds = { if (plan.kind == BulkCloseKind.TABS) _state.value.tabs.mapTo(HashSet()) { it.tabId }
                        else _state.value.workspaces.mapTo(HashSet()) { it.workspaceId } },
                    closeGroup = { id ->
                        if (client !== c) throw CancellationException("connection changed")
                        c.call("workspace.close", workspaceCloseParams(id, group = true))
                    },
                    close = { id ->
                        if (client !== c) throw CancellationException("connection changed")
                        c.call(if (plan.kind == BulkCloseKind.TABS) "tab.close" else "workspace.close", buildJsonObject {
                            put(if (plan.kind == BulkCloseKind.TABS) "tab_id" else "workspace_id", id)
                        })
                    })
                if (client === c) {
                    // Refresh even when some items failed, so the list reflects partial completion.
                    try {
                        val snapshot = c.call("session.snapshot")
                        currentCoroutineContext().ensureActive()
                        if (client === c) applySnapshot(snapshot)
                    }
                    catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* The subscription loop will refresh after transport recovery. */ }
                    if (client === c) _bulkCloseResult.value = result
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (client === c) _errors.tryEmit(failureText(error)) }
            finally { synchronized(operationLock) { if (generation == operationGeneration) _bulkClosing.value = false } }
        }
    }

    fun renamePane(paneId: String, label: String) = act("pane.rename", buildJsonObject {
        put("pane_id", paneId)
        put("label", label)
    })

    /** Types text into a pane without focusing it (e.g. answering a blocked agent). */
    fun sendText(paneId: String, text: String, pressEnter: Boolean) =
        sendInput(paneId, text, if (pressEnter) listOf("enter") else emptyList())

    /** Types [text] (if any) into a pane, then presses [keys] (herdr key names such as "enter", "esc"). */
    fun sendInput(paneId: String, text: String?, keys: List<String>) {
        val pending = enqueueInput(paneId, text, keys)
        scope.launch { awaitInputChecked(pending) }
    }

    private fun enqueueInput(paneId: String, text: String?, keys: List<String>, raw: Boolean = false): Deferred<Unit> {
        val c = client
        val frozenKeys = keys.toList()
        return inputQueue.enqueue(paneId) {
            if (c == null) throw IOException(App.str(R.string.core_not_connected))
            if (client !== c) throw IOException(App.str(R.string.core_connection_switched))
            sendPaneInput(HerdrRpc { method, params -> c.call(method, params) }, paneId, text, frozenKeys, raw)
        }
    }

    /** Lua waits for acknowledgement so errors propagate and subsequent calls cannot overtake. */
    suspend fun sendInputAwait(paneId: String, text: String?, keys: List<String>) {
        enqueueInput(paneId, text, keys).await()
    }

    suspend fun sendRawAwait(paneId: String, text: String) {
        enqueueInput(paneId, text, emptyList(), raw = true).await()
    }

    /** Like [sendInput] but suspends for the result; emits the failure to [errors] and returns false on error. */
    suspend fun sendInputChecked(paneId: String, text: String?, keys: List<String>): Boolean =
        awaitInputChecked(enqueueInput(paneId, text, keys))

    private suspend fun awaitInputChecked(pending: Deferred<Unit>): Boolean {
        return try {
            pending.await()
            true
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            _errors.tryEmit(App.str(R.string.core_op_failed_timeout))
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pane.send_input failed: ${e.message}", e)
            _errors.tryEmit(failureText(e))
            false
        }
    }

    private fun failureText(e: Exception): String {
        val reason = e.message?.lineSequence()?.firstOrNull()?.take(60)?.takeIf { it.isNotBlank() }
        return if (reason != null) App.str(R.string.core_op_failed_reason, reason) else App.str(R.string.core_op_failed)
    }

    private val lastScrollFix = HashMap<String, Long>()

    /**
     * herdr >= 0.9: if [paneId]'s herdr-side scrollback is scrolled up (snapshot offset > 0), jump to the
     * bottom so text the user is typing is visible. Cheap: no call unless the snapshot says offset > 0,
     * and at most one per pane per second (the local state is updated optimistically).
     */
    fun scrollToBottomIfScrolled(paneId: String) {
        val s = _state.value
        if (!RemoteCommand.versionAtLeast(s.version, 0, 9)) return
        val offset = s.pane(paneId)?.scroll?.offsetFromBottom ?: 0
        if (offset <= 0) return
        val now = System.currentTimeMillis()
        synchronized(lastScrollFix) {
            if (now - (lastScrollFix[paneId] ?: 0L) < 1_000) return
            lastScrollFix[paneId] = now
        }
        val c = client ?: return
        scope.launch {
            try {
                c.call("pane.scroll", buildJsonObject {
                    put("pane_id", paneId)
                    put("offset_from_bottom", 0)
                })
                if (client !== c) return@launch
                _state.update { st ->
                    st.copy(panes = st.panes.map { p ->
                        if (p.paneId == paneId) p.copy(scroll = p.scroll?.copy(offsetFromBottom = 0)) else p
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pane.scroll failed: ${e.message}")
            }
        }
    }

    /** Reads the last lines of a pane (for previews). */
    suspend fun readPane(paneId: String, lines: Int = 40): String {
        val c = client ?: throw IOException(App.str(R.string.core_not_connected))
        val r = c.call("pane.read", buildJsonObject {
            put("pane_id", paneId)
            put("source", "recent")
            put("lines", lines)
        })
        return r["read"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    }

    /**
     * Recent output of a pane including scrollback, with ANSI colours (falls back to plain text if the server
     * rejects the ANSI request). Null on any failure. Used by the local history scroller.
     */
    suspend fun readPaneHistory(paneId: String, lines: Int): String? {
        val c = client ?: return null
        suspend fun read(ansi: Boolean): String? {
            val r = c.call("pane.read", buildJsonObject {
                put("pane_id", paneId)
                put("source", "recent")
                put("lines", lines)
                put("format", if (ansi) "ansi" else "text")
                put("strip_ansi", !ansi)
            }, 8_000)
            return r["read"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        }
        return try {
            try { read(true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "pane.read ansi failed, retrying as text: ${e.message}")
                read(false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pane.read history failed: ${e.message}")
            null
        }
    }

    /** Current visible screen text of a pane (for overview miniatures); null on any failure. */
    suspend fun readPaneVisible(paneId: String): String? {
        val c = client ?: return null
        return try {
            val r = c.call("pane.read", buildJsonObject {
                put("pane_id", paneId)
                put("source", "visible")
                put("format", "text")
                put("strip_ansi", true)
            }, 5_000)
            r["read"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "pane.read failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "HerdrController"
        private const val RESYNC_MIN_MS = 500L
        private const val RESYNC_MAX_MS = 8_000L
        private const val RESYNC_STABLE_MS = 10_000L
        private const val RESYNC_MAX_RAPID = 6
        /** git worktree add / remove can be slow on large repositories. */
        private const val WORKTREE_TIMEOUT_MS = 60_000L

        private fun sub(type: String) = buildJsonObject { put("type", type) }

        private fun agentSubscription(paneId: String) = buildJsonObject {
            put("type", "pane.agent_status_changed")
            put("pane_id", paneId)
        }

        private val LIFECYCLE_SUBSCRIPTIONS = listOf(
            "workspace.created", "workspace.updated", "workspace.renamed", "workspace.closed",
            "workspace.focused", "workspace.moved", "workspace.reordered",
            "tab.created", "tab.closed", "tab.focused", "tab.renamed", "tab.moved",
            "pane.created", "pane.closed", "pane.updated", "pane.focused", "pane.moved",
            "pane.exited", "pane.agent_detected", "layout.updated",
        ).map { sub(it) }
    }
}

