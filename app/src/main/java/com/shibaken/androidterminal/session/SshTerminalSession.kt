package com.shibaken.androidterminal.session

import android.util.Log
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.herdr.AgentAlertListener
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.herdr.HerdrClient
import com.shibaken.androidterminal.herdr.HerdrSessionInfo
import com.shibaken.androidterminal.herdr.HerdrSessionPickerState
import com.shibaken.androidterminal.herdr.parseHerdrSessions
import com.shibaken.androidterminal.herdr.herdrSessionPreferenceKey
import com.shibaken.androidterminal.herdr.resolveRunningHerdrSession
import com.shibaken.androidterminal.ssh.MoshBootstrap
import com.shibaken.androidterminal.ssh.MoshBridge
import com.shibaken.androidterminal.ssh.MoshChannel
import com.shibaken.androidterminal.ssh.MoshPids
import com.shibaken.androidterminal.ssh.PaneStream
import com.shibaken.androidterminal.ssh.RemoteCommand
import com.shibaken.androidterminal.ssh.SshCancelledException
import com.shibaken.androidterminal.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.concurrent.thread

sealed class ConnState {
    data class Connecting(val message: String) : ConnState()
    data object Connected : ConnState()
    data class Disconnected(val reason: String?) : ConnState()
}

/** How a session renders the remote side. */
enum class SessionMode {
    /** herdr disabled: one plain shell ([SshTerminalSession.shell]). */
    SHELL,

    /** herdr panes rendered natively, one [PaneTerminal] per pane ([SshTerminalSession.panes]). */
    PANES,

    /** Fallback (old herdr / server not startable): shell with herdr's own TUI ([SshTerminalSession.shell]). */
    LEGACY_TUI,
}

/**
 * One terminal tab on a host: an SSH connection plus either a single shell ([SessionMode.SHELL],
 * [SessionMode.LEGACY_TUI]) or one `herdr terminal attach` channel per visible pane
 * ([SessionMode.PANES]), and (optionally) a [HerdrController] talking to herdr over the same
 * SSH connection.
 *
 * Threading: all public members are main-thread. Blocking SSH work runs on Dispatchers.IO.
 * The [PaneTerminal] emulators are main-thread only (see [PaneTerminal]).
 */
class SshTerminalSession(
    private val app: App,
    profile: HostProfile,
    alerts: AgentAlertListener,
) {
    val id: String = UUID.randomUUID().toString()
    var profile: HostProfile = profile
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting("準備中…"))
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _title = MutableStateFlow(profile.displayName)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off user-visible notes (e.g. Mosh fell back to SSH) for a snackbar. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()
    private var moshNoticeShown = false

    private val sessionPreferences = app.getSharedPreferences("herdr_sessions", 0)
    private var selectionKey = herdrSessionPreferenceKey(profile)
    private var target: HerdrSessionInfo? = null
    private val _selectedHerdrSession = MutableStateFlow(configuredHerdrSession())
    val selectedHerdrSession = _selectedHerdrSession.asStateFlow()
    private val _connectedHerdrSession = MutableStateFlow<String?>(null)
    val connectedHerdrSession = _connectedHerdrSession.asStateFlow()
    private val _sessionPicker = MutableStateFlow(HerdrSessionPickerState())
    val sessionPicker = _sessionPicker.asStateFlow()
    private var discoveryJob: Job? = null
    private var switchJob: Job? = null

    private suspend fun listHerdrSessions(c: SshConnection): List<HerdrSessionInfo> =
        parseHerdrSessions(c.execAll(RemoteCommand.viaLoginShell("herdr session list --json"), 10_000))

    fun refreshHerdrSessions() {
        if (discoveryJob?.isActive == true || switchJob?.isActive == true) return
        val c = conn
        if (c == null || !c.isConnected) {
            _sessionPicker.value = HerdrSessionPickerState(error = "SSH に接続されていません。再接続してください。")
            return
        }
        val gen = generation
        _sessionPicker.value = _sessionPicker.value.copy(loading = true, error = null)
        discoveryJob = scope.launch {
            try {
                val entries = listHerdrSessions(c)
                if (gen == generation && conn === c) _sessionPicker.value = HerdrSessionPickerState(sessions = entries)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen == generation && conn === c) _sessionPicker.value = HerdrSessionPickerState(error = "一覧を取得できませんでした: ${e.message.orEmpty().take(160)}")
            }
        }
    }

    /** Validate and ping before detaching. This action never starts, creates, stops or deletes a server. */
    fun selectHerdrSession(name: String) {
        if (switchJob?.isActive == true || name == _connectedHerdrSession.value) return
        val c = conn ?: return
        val gen = generation
        _sessionPicker.value = _sessionPicker.value.copy(switchingTo = name, error = null)
        switchJob = scope.launch {
            try {
                val entry = resolveRunningHerdrSession(name, { listHerdrSessions(c) }) { HerdrClient(c, it.socketPath).call("ping"); Unit }
                if (gen != generation || conn !== c) return@launch
                bootstrapJob?.cancel()
                discoveryJob?.cancel()
                generation++
                val nextGen = generation
                herdr?.detach(resetState = true)
                _ext.value?.onDisconnected()
                panesReady = false
                closeAllSlots()
                shell?.close(); shell = null
                visible = emptySet()
                target = entry
                _selectedHerdrSession.value = entry.name
                _connectedHerdrSession.value = null
                _mode.value = SessionMode.PANES
                _state.value = ConnState.Connecting("${entry.name} に接続中…")
                herdr?.attach(c, entry.name, entry.socketPath, resetState = true)
                bootstrapJob = scope.launch { bootstrapPanes(c, nextGen, existingOnly = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gen == generation && conn === c) _sessionPicker.value = _sessionPicker.value.copy(switchingTo = null, error = e.message ?: "接続できませんでした")
            }
        }
    }

    private val _ext = MutableStateFlow<SessionExt?>(null)
    /** Lua extension runtime; non-null after the first successful connect of a host with extensions on. */
    val ext: StateFlow<SessionExt?> = _ext.asStateFlow()

    internal fun postNotice(text: String) {
        _notices.tryEmit(text)
    }

    internal fun currentConn(): SshConnection? = conn

    /** Re-evaluates the scripts (no host fetch) if extensions are running. */
    fun reloadExtensions(fetch: Boolean = false) {
        _ext.value?.reloadNow(fetch)
    }

    private fun afterConnected(c: SshConnection) {
        if (!profile.extEnabled || !app.settings.extensionsEnabled) {
            _ext.value?.let { it.close(); _ext.value = null }
            return
        }
        val e = _ext.value ?: SessionExt(this, app, scope).also { _ext.value = it }
        e.onConnected(c)
    }

    val herdr: HerdrController? = if (profile.herdrEnabled) HerdrController(alerts) else null

    private val _mode = MutableStateFlow(if (profile.herdrEnabled) SessionMode.PANES else SessionMode.SHELL)
    /**
     * Starts as PANES when herdr is enabled and may switch to LEGACY_TUI during connect.
     * [shell] is assigned before the switch to LEGACY_TUI.
     */
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    /** The single shell for SHELL / LEGACY_TUI (null in PANES mode until a fallback happens). */
    var shell: PaneTerminal? = null
        private set(value) {
            field = value
            _shellFlow.value = value
        }
    private val _shellFlow = MutableStateFlow<PaneTerminal?>(null)
    internal val shellFlow: StateFlow<PaneTerminal?> = _shellFlow.asStateFlow()

    /** Last size reported by any terminal of this session; used to size new terminals. */
    private var defCols = 80
    private var defRows = 24
    private var defCellW = 10
    private var defCellH = 20

    private var conn: SshConnection? = null
    private var connectJob: Job? = null
    private var bootstrapJob: Job? = null
    /** Bumped on every (re)connect/teardown so stale callbacks cannot touch a newer transport. */
    private var generation = 0

    // --- shell (SHELL / LEGACY_TUI) ----------------------------------------------------

    private fun newTerminal(escapeCtrlB: Boolean, tag: String): PaneTerminal =
        PaneTerminal(app, scope, escapeCtrlB, defCols, defRows, defCellW, defCellH, tag).also { t ->
            t.onResized = { c, r, w, h -> defCols = c; defRows = r; defCellW = w; defCellH = h }
        }

    private fun ensureShell(): PaneTerminal {
        shell?.let { return it }
        val t = newTerminal(false, "shell")
        shell = t
        scope.launch { t.title.collect { _title.value = it ?: this@SshTerminalSession.profile.displayName } }
        return t
    }

    private suspend fun startShell(c: SshConnection, gen: Int, legacy: Boolean) {
        val term = ensureShell()
        val ch = withContext(Dispatchers.IO) {
            c.openShell(term.cols, term.rows, term.cols * term.cellW, term.rows * term.cellH)
        }
        if (gen != generation) {
            thread(name = "pty-close", isDaemon = true) { ch.close() }
            return
        }
        term.onChannelClosed = { if (gen == generation) onTransportClosed() }
        term.bind(ch)
        if (legacy) _mode.value = SessionMode.LEGACY_TUI
        _state.value = ConnState.Connected
        if (profile.startupCommand.isNotBlank()) {
            term.send((legacyStartup() + "\r").toByteArray())
        }
        herdr?.attach(c, effectiveHerdrSession, target?.socketPath)
    }

    /** In LEGACY_TUI the typed `herdr` must talk to the same session the controller watches. */
    private fun legacyStartup(): String {
        val cmd = profile.startupCommand.trim()
        return if (_mode.value == SessionMode.LEGACY_TUI && cmd == "herdr")
            "${RemoteCommand.herdrEnvironment(effectiveHerdrSession, target?.socketPath)} herdr" else profile.startupCommand
    }

    // --- connect -----------------------------------------------------------------------

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { doConnect() }
    }

    private suspend fun doConnect() {
        closeTransport(keepMosh = true)
        profile = app.hosts.get(profile.id) ?: profile
        val key = herdrSessionPreferenceKey(profile)
        if (key != selectionKey) {
            selectionKey = key
            target = null
            herdr?.detach(resetState = true)
            closeAllSlots()
        }
        _selectedHerdrSession.value = target?.name ?: sessionPreferences.getString(selectionKey, null) ?: configuredHerdrSession()
        val gen = generation
        val c = SshConnection(app, profile)
        conn = c
        try {
            c.connect { msg -> _state.value = ConnState.Connecting(msg) }
            if (profile.useMosh) reapOrphans(c)
            if (herdr != null) {
                _state.value = ConnState.Connecting("マルチペインを準備中…")
                val existingOnly = target != null || sessionPreferences.contains(selectionKey)
                if (existingOnly) {
                    target = resolveRunningHerdrSession(_selectedHerdrSession.value, { listHerdrSessions(c) }) { HerdrClient(c, it.socketPath).call("ping"); Unit }
                }
                if (existingOnly || ensureHerdrServer(c)) {
                    if (gen != generation) return
                    _mode.value = SessionMode.PANES
                    _state.value = ConnState.Connected
                    panesReady = true
                    herdr.attach(c, effectiveHerdrSession, target?.socketPath)
                    bootstrapJob = scope.launch { bootstrapPanes(c, gen, existingOnly) }
                    reconcile()
                    if (!existingOnly) afterConnected(c)
                    return
                }
                Log.w(TAG, "herdr panes unavailable; falling back to the herdr TUI")
                closeAllSlots()
                startShell(c, gen, legacy = true)
            } else {
                startShell(c, gen, legacy = false)
            }
            if (gen == generation) afterConnected(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            // Keep a healthy SSH transport available so a stopped remembered target can be changed.
            if (!c.isConnected) {
                if (conn === c) conn = null
                thread(name = "ssh-close", isDaemon = true) { c.close() }
            }
            val msg = if (e is SshCancelledException) e.message else (e.message ?: e.javaClass.simpleName)
            _state.value = ConnState.Disconnected(msg)
        }
    }

    // --- herdr server / bootstrap ----------------------------------------------------------

    private fun configuredHerdrSession(): String = RemoteCommand.sanitizeSessionName(profile.herdrSession.ifBlank { DEFAULT_HERDR_SESSION })
            .ifBlank { DEFAULT_HERDR_SESSION }

    private val effectiveHerdrSession: String get() = _selectedHerdrSession.value

    /** True when `herdr terminal attach` is supported and the (phone) session's server is running. */
    private suspend fun ensureHerdrServer(c: SshConnection): Boolean {
        try {
            val version = c.exec(RemoteCommand.viaLoginShell("herdr --version"))
            if (!RemoteCommand.supportsTerminalAttach(version)) {
                Log.i(TAG, "herdr too old or missing: '$version'")
                return false
            }
            val env = RemoteCommand.herdrEnvironment(effectiveHerdrSession)
            suspend fun running() =
                RemoteCommand.isServerRunning(c.exec(RemoteCommand.viaLoginShell("$env herdr status server")))
            if (running()) return true
            c.exec(RemoteCommand.viaLoginShell("$env nohup herdr server >/dev/null 2>&1 </dev/null &"))
            repeat(20) {
                delay(500)
                if (running()) return true
            }
            Log.w(TAG, "herdr server did not come up")
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ensureHerdrServer failed", e)
            return false
        }
    }

    /** Waits for the first snapshot; creates a workspace if the session is empty, else falls back to the TUI. */
    private suspend fun bootstrapPanes(c: SshConnection, gen: Int, existingOnly: Boolean = false) {
        val h = herdr ?: return
        val ready = withTimeoutOrNull(15_000) { h.state.first { it.phase == HerdrState.Phase.READY } }
        if (gen != generation) return
        if (ready == null) {
            if (existingOnly) {
                h.detach()
                _state.value = ConnState.Disconnected("${effectiveHerdrSession} の応答を確認できませんでした。セッションを選び直してください。")
                _sessionPicker.value = _sessionPicker.value.copy(switchingTo = null, error = "接続を確認できませんでした。一覧を更新して選び直してください。")
                return
            }
            Log.w(TAG, "herdr API not ready; falling back to the herdr TUI")
            h.detach()
            panesReady = false
            closeAllSlots()
            try {
                startShell(c, gen, legacy = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "legacy fallback failed", e)
                _state.value = ConnState.Disconnected(e.message ?: e.javaClass.simpleName)
            }
            return
        }
        _connectedHerdrSession.value = effectiveHerdrSession
        sessionPreferences.edit().putString(selectionKey, effectiveHerdrSession).apply()
        _sessionPicker.value = _sessionPicker.value.copy(switchingTo = null)
        panesReady = true
        _state.value = ConnState.Connected
        reconcile()
        if (existingOnly) afterConnected(c)
        if (!existingOnly && ready.workspaces.isEmpty()) h.newWorkspace(null)
    }

    // --- panes -------------------------------------------------------------------------------

    private inner class Slot(val paneId: String, var terminalId: String, val term: PaneTerminal) {
        var opening = false
        /** Bumped to invalidate an in-flight open (transport teardown, terminal id change). */
        var openToken = 0

        fun invalidateOpen() {
            openToken++
            opening = false
        }
        var detachJob: Job? = null
        var retryJob: Job? = null
        var backoff = 1_000L
        var boundAt = 0L
        /** The bound stream is a Mosh session (survives SSH drops). */
        var viaMosh = false
        /** Mosh failed for this pane: use the SSH PTY attach from now on. */
        var moshFailed = false

        fun cancelJobs() {
            detachJob?.cancel(); detachJob = null
            retryJob?.cancel(); retryJob = null
        }
    }

    private val slots = LinkedHashMap<String, Slot>()
    private val _panes = MutableStateFlow<Map<String, PaneTerminal>>(emptyMap())
    /** One terminal per herdr pane that has a terminal id (attached or not). Main thread. */
    val panes: StateFlow<Map<String, PaneTerminal>> = _panes.asStateFlow()

    private var visible: Set<String> = emptySet()
    private var foreground = true
    private var panesReady = false

    /** UI: the panes currently on screen. Missing ones get attached; ones no longer visible detach after ~2s. */
    fun setVisiblePanes(paneIds: Set<String>) {
        if (paneIds == visible) return
        visible = paneIds.toSet()
        reconcile()
    }

    /** UI/app visibility. false → all panes detach after the grace period; true → visible panes reattach. */
    fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        reconcile()
    }

    private fun wanted(slot: Slot) = panesReady && foreground && slot.paneId in visible && slots[slot.paneId] === slot

    /** A Mosh-backed pane that stays attached while only the SSH transport is down. */
    private fun moshHold(slot: Slot) =
        !panesReady && foreground && slot.paneId in visible && slots[slot.paneId] === slot &&
            slot.term.boundStream?.survivesTransport == true

    private fun syncPanes(ids: List<Pair<String, String>>) {
        if (_mode.value != SessionMode.PANES) return
        val want = ids.toMap()
        for (id in slots.keys.toList()) {
            if (id in want) continue
            slots.remove(id)?.let { s ->
                s.cancelJobs()
                s.term.close()
            }
        }
        for ((paneId, terminalId) in ids) {
            val existing = slots[paneId]
            if (existing == null) {
                val term = newTerminal(true, paneId)
                val slot = Slot(paneId, terminalId, term)
                term.onChannelClosed = { onPaneChannelEnded(slot) }
                term.onUserInput = { herdr?.scrollToBottomIfScrolled(paneId) }
                slots[paneId] = slot
            } else if (existing.terminalId != terminalId) {
                existing.terminalId = terminalId
                existing.invalidateOpen()
                existing.cancelJobs()
                existing.term.release()?.let { ch -> thread(name = "pty-close", isDaemon = true) { ch.close() } }
            }
        }
        val next = LinkedHashMap<String, PaneTerminal>()
        slots.forEach { (k, v) -> next[k] = v.term }
        if (next != _panes.value) _panes.value = next
        reconcile()
    }

    private fun reconcile() {
        for (slot in slots.values.toList()) {
            if (moshHold(slot)) {
                // SSH is down (reconnecting) but the Mosh session keeps running on its own.
                slot.detachJob?.cancel(); slot.detachJob = null
            } else if (wanted(slot)) {
                slot.detachJob?.cancel(); slot.detachJob = null
                if (!slot.term.attached.value && !slot.opening && slot.retryJob == null) openAttach(slot)
            } else {
                slot.retryJob?.cancel(); slot.retryJob = null
                if (slot.term.attached.value && slot.detachJob == null) {
                    slot.detachJob = scope.launch {
                        delay(if (slot.viaMosh && !foreground) MOSH_BACKGROUND_GRACE_MS else DETACH_GRACE_MS)
                        slot.detachJob = null
                        if (!wanted(slot) && !moshHold(slot)) slot.term.detach()
                    }
                }
            }
        }
    }

    private fun openAttach(slot: Slot) {
        val c = conn ?: return
        val gen = generation
        val term = slot.term
        slot.opening = true
        val token = ++slot.openToken
        val terminalId = slot.terminalId
        scope.launch {
            try {
                val cmd = RemoteCommand.herdrAttach(effectiveHerdrSession, terminalId, target?.socketPath)
                val mosh = tryOpenMosh(c, slot, term, terminalId)
                val ch: PaneStream = mosh ?: withContext(Dispatchers.IO) {
                    c.openPtyExec(cmd, term.cols, term.rows, term.cols * term.cellW, term.rows * term.cellH)
                }
                if (token != slot.openToken) {
                    // Superseded (teardown or terminal id change): not ours to touch any more.
                    thread(name = "pty-close", isDaemon = true) { ch.close() }
                    return@launch
                }
                slot.opening = false
                if (gen != generation || !wanted(slot) || term.closed.value || slot.terminalId != terminalId) {
                    thread(name = "pty-close", isDaemon = true) { ch.close() }
                    reconcile()
                    return@launch
                }
                slot.boundAt = System.currentTimeMillis()
                slot.viaMosh = mosh != null
                term.bind(ch)
            } catch (e: CancellationException) {
                if (token == slot.openToken) slot.opening = false
                throw e
            } catch (e: Exception) {
                if (token != slot.openToken) return@launch
                slot.opening = false
                Log.w(TAG, "attach ${slot.paneId} failed: ${e.message}")
                if (gen == generation && wanted(slot)) scheduleRetry(slot, livedMs = 0)
            }
        }
    }

    /** Mosh-specific failure (not an SSH transport error): the pane falls back to SSH. */
    private class MoshUnavailable(msg: String) : Exception(msg)

    /**
     * Starts mosh-server for this pane over SSH and opens the datagram session; null (after a one-time
     * notice) when Mosh is off, unavailable or failed in a Mosh-specific way, in which case the caller uses
     * the SSH PTY attach. SSH transport errors propagate (normal retry).
     */
    private suspend fun tryOpenMosh(c: SshConnection, slot: Slot, term: PaneTerminal, terminalId: String): MoshChannel? {
        if (!profile.useMosh || slot.moshFailed || !MoshBridge.available) return null
        val hostId = profile.id
        var pid: Int? = null
        var ch: MoshChannel? = null
        try {
            val target = MoshBootstrap.chooseTarget(
                c.execAll("printf '%s' \"\$SSH_CONNECTION\"", 5_000), profile.host,
            )
            var info: MoshBootstrap.Info? = null
            var out = ""
            for (locale in MoshBootstrap.LOCALES) {
                out = c.execAll(
                    RemoteCommand.viaLoginShell(
                        MoshBootstrap.serverCommand(effectiveHerdrSession, terminalId, target.bindToSshAddress, locale, this@SshTerminalSession.target?.socketPath),
                    ),
                    MOSH_BOOTSTRAP_TIMEOUT_MS,
                )
                info = MoshBootstrap.parse(out)
                if (info != null || !MoshBootstrap.isLocaleError(out)) break
            }
            if (info == null) {
                if (!c.isConnected) throw java.io.IOException("SSH connection lost")
                throw MoshUnavailable("mosh-server が見つかりません(MOSH CONNECT なし): " + out.trim().takeLast(120))
            }
            val i: MoshBootstrap.Info = info
            pid = i.pid
            pid?.let { moshPids.add(hostId, it); moshPids.markLive(hostId, it) }
            ch = try {
                withContext(NonCancellable + Dispatchers.IO) {
                    MoshChannel.open(i, target.host, profile.useTailscale, term.cols, term.rows) { p -> killMoshServer(hostId, p) }
                }
            } catch (e: Exception) {
                throw MoshUnavailable(e.message ?: e.javaClass.simpleName)
            }
            currentCoroutineContext().ensureActive() // cancelled after the blocking open: closed below
            return ch
        } catch (e: CancellationException) {
            closeOrKill(ch, hostId, pid)
            throw e
        } catch (e: MoshUnavailable) {
            Log.w(TAG, "mosh ${slot.paneId} failed: ${e.message}")
            closeOrKill(ch, hostId, pid)
            slot.moshFailed = true
            if (!moshNoticeShown && _notices.tryEmit("Mosh を使えないため SSH で接続します: ${e.message}")) moshNoticeShown = true
            return null
        } catch (e: Exception) {
            closeOrKill(ch, hostId, pid) // SSH transport error: regular retry, pane not marked
            throw e
        }
    }

    private fun closeOrKill(ch: MoshChannel?, hostId: String, pid: Int?) {
        if (ch != null) thread(name = "mosh-close", isDaemon = true) { ch.close() }
        else if (pid != null) thread(name = "mosh-kill", isDaemon = true) { killMoshServer(hostId, pid) }
    }

    private val moshPids by lazy { MoshPids(app) }

    /** Best-effort kill of a mosh-server over the live (or just-closing) SSH connection. Blocking, bounded. */
    private fun killMoshServer(hostId: String, pid: Int) {
        moshPids.markDead(hostId, pid)
        val c = conn ?: lastConn ?: return
        if (!c.isConnected) return
        try {
            runBlocking { c.execAll(MoshBootstrap.killCommand(pid), 5_000) }
            moshPids.remove(hostId, pid)
        } catch (e: Exception) {
            Log.i(TAG, "kill mosh-server $pid failed: ${e.message}")
        }
    }

    /** After a successful SSH connect: kill mosh-servers of this host left behind by earlier runs. */
    private fun reapOrphans(c: SshConnection) {
        val hostId = profile.id
        val orphans = moshPids.get(hostId).filter { !moshPids.isLive(hostId, it) }
        if (orphans.isEmpty()) return
        thread(name = "mosh-reap", isDaemon = true) {
            for (pid in orphans) {
                try {
                    runBlocking { c.execAll(MoshBootstrap.killCommand(pid), 5_000) }
                    moshPids.remove(hostId, pid)
                } catch (e: Exception) {
                    Log.i(TAG, "reap $pid failed: ${e.message}")
                    break
                }
            }
        }
    }

    @Volatile private var lastConn: SshConnection? = null

    private fun onPaneChannelEnded(slot: Slot) {
        val c = conn
        if (slot.viaMosh) {
            // The Mosh session ended on its own; SSH being down is not (yet) a reason to give up the session.
            slot.viaMosh = false
            if (c == null || !c.isConnected) return
            if (!wanted(slot)) return
            val lived = System.currentTimeMillis() - slot.boundAt
            if (lived < 5_000) slot.moshFailed = true // dying right away: do not loop on Mosh
            scheduleRetry(slot, lived)
            return
        }
        if (c == null || !c.isConnected) {
            onTransportClosed()
            return
        }
        if (!wanted(slot)) return
        scheduleRetry(slot, System.currentTimeMillis() - slot.boundAt)
    }

    /** Retry with backoff 1s → 10s; a channel that lived > 5s resets the backoff. */
    private fun scheduleRetry(slot: Slot, livedMs: Long) {
        if (livedMs > 5_000) slot.backoff = 1_000L
        val delayMs = slot.backoff
        slot.backoff = (slot.backoff * 2).coerceAtMost(10_000L)
        slot.retryJob?.cancel()
        slot.retryJob = scope.launch {
            delay(delayMs)
            slot.retryJob = null
            reconcile()
        }
    }

    /** Pane terminals are useless in LEGACY_TUI: close them (channels are closed in the background). */
    private fun closeAllSlots() {
        for (sl in slots.values) {
            sl.invalidateOpen()
            sl.cancelJobs()
            sl.term.close()
        }
        slots.clear()
        _panes.value = emptyMap()
    }

    /** (paneId, terminalId) of every pane that can be attached. */
    private fun paneIds(s: HerdrState): List<Pair<String, String>> =
        s.panes.mapNotNull { p -> p.terminalId?.let { p.paneId to it } }

    // --- teardown -----------------------------------------------------------------------------

    private fun onTransportClosed() {
        if (_state.value is ConnState.Connected) {
            _state.value = ConnState.Disconnected("接続が切れました")
        }
        herdr?.detach()
        closeTransport(keepMosh = true)
    }

    /** Main thread. Socket writes (channel close / disconnect) happen on a background thread. */
    private fun closeTransport(keepMosh: Boolean = false) {
        generation++
        herdr?.detach()
        _connectedHerdrSession.value = null
        discoveryJob?.cancel()
        switchJob?.cancel()
        _sessionPicker.value = HerdrSessionPickerState()
        _ext.value?.onDisconnected()
        panesReady = false
        bootstrapJob?.cancel(); bootstrapJob = null
        val chans = ArrayList<PaneStream>()
        shell?.let { it.release()?.let { ch -> chans.add(ch) } }
        for (s in slots.values) {
            s.cancelJobs()
            s.invalidateOpen()
            // Mosh panes live on without SSH; they are closed when the pane goes away or the user disconnects.
            if (keepMosh && s.term.boundStream?.survivesTransport == true) continue
            s.term.release()?.let { chans.add(it) }
        }
        val c = conn
        if (c != null) lastConn = c
        conn = null
        if (chans.isNotEmpty() || c != null) {
            thread(name = "ssh-close", isDaemon = true) {
                chans.forEach { it.close() }
                c?.close()
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        herdr?.detach()
        closeTransport()
        _state.value = ConnState.Disconnected(null)
    }

    fun dispose() {
        disconnect()
        herdr?.dispose()
        _ext.value?.close()
        _ext.value = null
        shell?.close()
        slots.values.forEach { it.term.close() }
        slots.clear()
        _panes.value = emptyMap()
        scope.cancel()
    }

    init {
        if (!profile.herdrEnabled) ensureShell()
        if (herdr != null) {
            scope.launch {
                herdr.state
                    .map { s -> paneIds(s) }
                    .distinctUntilChanged()
                    .collect { syncPanes(it) }
            }
            scope.launch {
                // herdr's loop gives up once SSH is gone; use that to notice a dead transport in PANES mode.
                herdr.state.collect { s ->
                    if (s.phase == HerdrState.Phase.ERROR && panesReady && conn?.isConnected == false) onTransportClosed()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SshTerminalSession"
        const val DEFAULT_HERDR_SESSION = "android"
        private const val DETACH_GRACE_MS = 2_000L
        private const val MOSH_BACKGROUND_GRACE_MS = 10 * 60_000L
        private const val MOSH_BOOTSTRAP_TIMEOUT_MS = 10_000L
    }
}
