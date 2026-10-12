package com.shibaken.androidterminal.herdr

import android.util.Log
import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
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
    val focusedWorkspaceId: String? = null,
    val focusedTabId: String? = null,
    val focusedPaneId: String? = null,
) {
    enum class Phase { IDLE, CONNECTING, READY, ERROR }

    val blockedCount: Int get() = panes.count { it.agentStatus == AgentStatus.BLOCKED }
    fun pane(id: String?): PaneInfo? = panes.firstOrNull { it.paneId == id }
    fun workspace(id: String?): WorkspaceInfo? = workspaces.firstOrNull { it.workspaceId == id }
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
    private val _state = MutableStateFlow(HerdrState())
    val state: StateFlow<HerdrState> = _state.asStateFlow()

    private var loopJob: Job? = null
    @Volatile private var client: HerdrClient? = null

    fun attach(conn: SshConnection, herdrSession: String) {
        loopJob?.cancel()
        loopJob = scope.launch { runLoop(conn, herdrSession) }
    }

    fun detach() {
        loopJob?.cancel()
        loopJob = null
        client = null
        _state.update { it.copy(phase = HerdrState.Phase.IDLE, message = "切断されました") }
    }

    fun dispose() {
        scope.cancel()
    }

    private suspend fun resolveSocketPath(conn: SshConnection, herdrSession: String): String {
        // sshd does not expand "~" for streamlocal, so resolve the absolute path on the Mac.
        val base = "\${XDG_CONFIG_HOME:-\$HOME/.config}/herdr"
        val expr = if (herdrSession.isNotBlank()) {
            "$base/sessions/${herdrSession.replace(Regex("[^A-Za-z0-9._-]"), "")}/herdr.sock"
        } else {
            "\${HERDR_SOCKET_PATH:-$base/herdr.sock}"
        }
        val path = conn.exec("printf '%s' \"$expr\"")
        if (!path.startsWith("/")) throw IOException("herdr のソケットパスを取得できませんでした: $path")
        return path
    }

    private suspend fun runLoop(conn: SshConnection, herdrSession: String) {
        var backoff = 1_000L
        var socketPath: String? = null
        while (scope.isActive) {
            try {
                _state.update { it.copy(phase = HerdrState.Phase.CONNECTING, message = "herdr に接続中…") }
                if (!conn.isConnected) throw IOException("SSH が切断されています")
                val path = socketPath ?: resolveSocketPath(conn, herdrSession).also { socketPath = it }
                val c = HerdrClient(conn, path)
                val pong = c.call("ping")
                val version = pong["version"]?.jsonPrimitive?.contentOrNull ?: ""
                client = c
                backoff = 1_000L
                _state.update { it.copy(version = version) }
                runSession(c, version)
                throw IOException("herdr のイベント購読が終了しました")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                client = null
                val msg = when {
                    e is HerdrException && e.code == "unreachable" -> "herdr が起動していません(起動を待っています)"
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
        val legacy = version.startsWith("0.8") || version.startsWith("0.7") || version.startsWith("0.6")
        val refresh = Channel<Unit>(Channel.CONFLATED)

        // Lifecycle events: used only as invalidation signals.
        val lifecycle = launch {
            c.subscribe(LIFECYCLE_SUBSCRIPTIONS).collect { refresh.trySend(Unit) }
            throw IOException("lifecycle subscription closed")
        }

        var agentJob: Job? = null
        var subscribedPanes: Set<String>? = null

        refresh.trySend(Unit)
        for (signal in refresh) {
            delay(120) // debounce bursts (a focus change emits 3 events)
            applySnapshot(c.call("session.snapshot"))
            val panes = _state.value.panes.map { it.paneId }.toSet()
            if (panes != subscribedPanes) {
                agentJob?.cancel()
                subscribedPanes = panes
                if (panes.isNotEmpty()) {
                    agentJob = launch {
                        val startedAt = System.currentTimeMillis()
                        try {
                            c.subscribe(panes.map { agentSubscription(it) }).collect { ev ->
                                // 0.8.x replays old events on subscribe; ignore the replay window.
                                if (legacy && System.currentTimeMillis() - startedAt < 1_500) return@collect
                                onAgentEvent(ev)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "agent subscription ended: ${e.message}")
                        }
                        // A pane may have vanished between snapshot and subscribe: rebuild.
                        subscribedPanes = null
                        refresh.trySend(Unit)
                    }
                }
            }
            if (lifecycle.isCompleted) break
            _state.update { it.copy(phase = HerdrState.Phase.READY, message = null) }
        }
    }

    private fun applySnapshot(result: JsonObject) {
        val snapEl = result["snapshot"] ?: return
        val snap = AppJson.decodeFromJsonElement(SessionSnapshot.serializer(), snapEl)
        _state.update {
            it.copy(
                version = snap.version.ifBlank { it.version },
                workspaces = snap.workspaces.sortedBy { w -> w.number },
                tabs = snap.tabs.sortedBy { t -> t.number },
                panes = snap.panes,
                focusedWorkspaceId = snap.focusedWorkspaceId,
                focusedTabId = snap.focusedTabId,
                focusedPaneId = snap.focusedPaneId,
            )
        }
    }

    private fun onAgentEvent(ev: JsonObject) {
        if (HerdrClient.eventName(ev) != "pane.agent_status_changed") return
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
        if (e.agentStatus == AgentStatus.BLOCKED || e.agentStatus == AgentStatus.DONE) {
            alerts.onAgentStatus(after, _state.value.workspace(after.workspaceId), e.agentStatus)
        }
    }

    // --- actions ------------------------------------------------------------------------

    private fun act(method: String, params: JsonObject, after: (JsonObject) -> Unit = {}) {
        val c = client ?: run {
            _state.update { it.copy(message = "herdr に接続されていません") }
            return
        }
        scope.launch {
            try {
                after(c.call(method, params))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "${method}: ${e.message}") }
            }
        }
    }

    fun focusPane(paneId: String) = act("pane.focus", buildJsonObject { put("pane_id", paneId) })
    fun focusTab(tabId: String) = act("tab.focus", buildJsonObject { put("tab_id", tabId) })
    fun focusWorkspace(id: String) = act("workspace.focus", buildJsonObject { put("workspace_id", id) })

    /** direction: "right" (side by side) or "down" (stacked). */
    fun split(direction: String) = act("pane.split", buildJsonObject {
        _state.value.focusedPaneId?.let { put("target_pane_id", it) }
        put("direction", direction)
        put("focus", true)
    })

    fun toggleZoom() = act("pane.zoom", buildJsonObject {
        _state.value.focusedPaneId?.let { put("pane_id", it) }
        put("mode", "toggle")
    })

    fun focusDirection(direction: String) = act("pane.focus_direction", buildJsonObject {
        put("direction", direction)
    })

    fun closePane(paneId: String) = act("pane.close", buildJsonObject { put("pane_id", paneId) })

    fun newTab() = act("tab.create", buildJsonObject {
        _state.value.focusedWorkspaceId?.let { put("workspace_id", it) }
        put("focus", true)
    })

    fun newWorkspace(label: String?) = act("workspace.create", buildJsonObject {
        put("focus", true)
        if (!label.isNullOrBlank()) put("label", label)
    })

    fun renamePane(paneId: String, label: String) = act("pane.rename", buildJsonObject {
        put("pane_id", paneId)
        put("label", label)
    })

    /** Types text into a pane without focusing it (e.g. answering a blocked agent). */
    fun sendText(paneId: String, text: String, pressEnter: Boolean) = act("pane.send_input", buildJsonObject {
        put("pane_id", paneId)
        put("text", text)
        if (pressEnter) put("keys", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("enter"))))
    })

    /** Reads the last lines of a pane (for previews). */
    suspend fun readPane(paneId: String, lines: Int = 40): String {
        val c = client ?: throw IOException("herdr に接続されていません")
        val r = c.call("pane.read", buildJsonObject {
            put("pane_id", paneId)
            put("source", "recent")
            put("lines", lines)
        })
        return r["read"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    }

    companion object {
        private const val TAG = "HerdrController"

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

