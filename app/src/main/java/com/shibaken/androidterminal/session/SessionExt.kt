package com.shibaken.androidterminal.session

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.ext.ExtEngine
import com.shibaken.androidterminal.ext.ExtHost
import com.shibaken.androidterminal.ext.ExtLoadResult
import com.shibaken.androidterminal.ext.ExtPane
import com.shibaken.androidterminal.ext.ExtSync
import com.shibaken.androidterminal.ext.OutputWatcher
import com.shibaken.androidterminal.herdr.PaneInfo
import com.shibaken.androidterminal.ssh.SshConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One session's Lua extension runtime: owns the [ExtEngine], implements [ExtHost] against the session,
 * syncs scripts from the host, and feeds screen / agent events to the engine.
 * Created by [SshTerminalSession] when the host has extensions enabled. Main-thread API except where noted.
 */
@OptIn(FlowPreview::class)
class SessionExt(
    private val session: SshTerminalSession,
    private val app: App,
    private val scope: CoroutineScope,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuf = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Recent `log()` output and load results (newest last, at most [LOG_MAX]). */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val host = object : ExtHost {
        override fun panes(): List<ExtPane> =
            if (session.mode.value == SessionMode.PANES && session.herdr != null) {
                session.herdr.state.value.panes.map(::toExtPane)
            } else {
                listOf(ExtPane(SHELL_ID, session.title.value, null, "unknown", null))
            }

        override fun focusedPaneId(): String? =
            if (isPanes()) session.herdr?.state?.value?.focusedPaneId else SHELL_ID

        override fun sendText(paneId: String, text: String) {
            if (isPanes()) session.herdr?.sendInput(paneId, text, emptyList()) else shellSend(text)
        }

        override fun sendKeys(paneId: String, keys: List<String>) {
            if (isPanes()) session.herdr?.sendInput(paneId, null, keys)
            else shellSend(keys.joinToString("") { keyBytes(it) })
        }

        override fun readPane(paneId: String, lines: Int): String {
            val n = lines.coerceIn(1, 500)
            if (isPanes()) {
                val h = session.herdr ?: error("not connected")
                return runBlocking { withTimeout(10_000) { h.readPane(paneId, n) } }
            }
            return runBlocking {
                withTimeout(5_000) { withContext(Dispatchers.Main) { session.shell?.tailText(n) ?: "" } }
            }
        }

        override fun notify(title: String, body: String, paneId: String?) {
            app.sessions.notify(session, title, body, paneId)
        }

        override fun toast(text: String) {
            session.postNotice(text)
        }

        override fun exec(command: String, timeoutSec: Int): String {
            val c = session.currentConn()?.takeIf { it.isConnected } ?: error("not connected")
            val t = timeoutSec.coerceIn(1, 120)
            return runBlocking {
                withTimeout((t + 5) * 1000L) {
                    c.execAll(com.shibaken.androidterminal.ssh.RemoteCommand.viaLoginShell(command), t * 1000L)
                }
            }
        }

        override fun log(line: String) = appendLog(line)
    }

    val engine = ExtEngine(host)

    private fun isPanes() = session.mode.value == SessionMode.PANES && session.herdr != null

    private fun shellSend(text: String) {
        mainHandler.post { session.shell?.sendText(text) }
    }

    // --- log -------------------------------------------------------------------------------

    private fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logBuf) {
            line.lines().forEach { logBuf.addLast("$ts $it") }
            while (logBuf.size > LOG_MAX) logBuf.removeFirst()
            _log.value = logBuf.toList()
        }
    }

    // --- load / sync -----------------------------------------------------------------------

    private val reloadLock = Mutex()
    private var syncJob: Job? = null
    private var watchJob: Job? = null
    private var syncErrorLogged = false

    /** Start (or restart) everything that depends on a live connection. Called after each successful connect. */
    fun onConnected(c: SshConnection) {
        syncJob?.cancel()
        val fromHost = session.profile.extLoadFromHost
        syncJob = scope.launch {
            syncErrorLogged = false
            var lastCk = ""
            if (fromHost) {
                lastCk = runCatching { remote(c, ExtSync.CKSUM) }.getOrDefault("")
            }
            reload(c, fetch = fromHost)
            engine.dispatchConnected()
            if (!fromHost) return@launch
            writeBack(c, "API.md", loadApiDoc())
            writeLog(c)
            launch {
                _log.drop(1).debounce(LOG_FLUSH_MS).collect { writeLog(c) }
            }
            while (isActive && c.isConnected) {
                delay(POLL_MS)
                val ck = try {
                    remote(c, ExtSync.CKSUM)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logOnce("poll", e)
                    continue
                }
                if (ck != lastCk) {
                    lastCk = ck
                    reload(c, fetch = true)
                    writeLog(c)
                }
            }
        }
        startWatching()
        startAgentHook()
    }

    /** Stops polling (disconnect). Engine and registry stay so keys keep showing. */
    fun onDisconnected() {
        syncJob?.cancel(); syncJob = null
    }

    /** Menu: re-fetch from the host (if enabled) and re-evaluate. Also used after a global script was saved. */
    fun reloadNow(fetch: Boolean) {
        scope.launch {
            val c = session.currentConn()?.takeIf { it.isConnected }
            reload(c, fetch = fetch && session.profile.extLoadFromHost)
            if (c != null && session.profile.extLoadFromHost) writeLog(c)
        }
    }

    private suspend fun reload(c: SshConnection?, fetch: Boolean) = reloadLock.withLock {
        if (fetch && c != null) {
            try {
                val nonce = ExtSync.newNonce()
                val out = remote(c, ExtSync.fetchCommand(nonce))
                val scripts = ExtSync.parseBundle(out, nonce)
                withContext(Dispatchers.IO) { app.scriptStore.replaceHost(session.profile.id, scripts) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logOnce("fetch", e)
            }
        }
        val scripts = withContext(Dispatchers.IO) {
            app.scriptStore.loadOrder(session.profile.id, session.profile.extLoadFromHost)
        }
        val r: ExtLoadResult = engine.reload(scripts)
        if (r.ok) {
            appendLog("読み込み完了: ${scripts.size} 本")
        } else {
            r.errors.forEach { appendLog("読み込みエラー: $it") }
            session.postNotice("拡張の読み込みエラー: ${r.errors.first().take(100)}")
        }
    }

    private fun logOnce(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
        if (!syncErrorLogged) {
            syncErrorLogged = true
            appendLog("$what に失敗: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun remote(c: SshConnection, cmd: String): String = c.execAll(cmd, 8_000)

    private suspend fun writeBack(c: SshConnection, name: String, text: String) {
        try {
            for (cmd in ExtSync.writeCommands(name, text)) c.execAll(cmd, 8_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logOnce("書き込み", e)
        }
    }

    private suspend fun writeLog(c: SshConnection) {
        val text = synchronized(logBuf) { logBuf.joinToString("\n") } + "\n"
        writeBack(c, "log.txt", text)
    }

    private suspend fun loadApiDoc(): String = withContext(Dispatchers.IO) {
        app.assets.open("ext/API.md").bufferedReader().use { it.readText() }
    }

    // --- output triggers ---------------------------------------------------------------------

    private fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            combine(
                engine.registry.map { it.hasOutputTriggers }.distinctUntilChanged(),
                session.panes,
                session.mode,
                session.shellFlow,
            ) { on, panes, mode, shell -> Watch(on, panes, mode, shell) }
                .distinctUntilChanged()
                .collectLatest { (on, panes, mode, shell) ->
                    if (!on) return@collectLatest
                    coroutineScope {
                        if (mode == SessionMode.PANES) {
                            panes.forEach { (id, t) -> launch { watch(id, t) } }
                        } else {
                            shell?.let { t -> launch { watch(SHELL_ID, t) } }
                        }
                    }
                }
        }
    }

    private data class Watch(val on: Boolean, val panes: Map<String, PaneTerminal>, val mode: SessionMode, val shell: PaneTerminal?)

    private suspend fun watch(id: String, term: PaneTerminal) {
        val w = OutputWatcher()
        var size = term.cols to term.rows
        term.screenRevision.debounce(OUTPUT_DEBOUNCE_MS).collect {
            val now = term.cols to term.rows
            if (now != size) {
                size = now
                w.reset()
            }
            val changed = w.update(term.visibleLines())
            if (changed.isNotEmpty()) engine.dispatchOutput(id, changed)
        }
    }

    // --- agent status ----------------------------------------------------------------------------

    private fun startAgentHook() {
        session.herdr?.onAgentTransition = { pane, prev ->
            engine.dispatchAgentStatus(toExtPane(pane), pane.agentStatus.lowercase(), prev.lowercase())
        }
    }

    fun close() {
        syncJob?.cancel(); syncJob = null
        watchJob?.cancel(); watchJob = null
        session.herdr?.onAgentTransition = null
        engine.close()
    }

    companion object {
        private const val TAG = "SessionExt"
        const val SHELL_ID = "shell"
        private const val LOG_MAX = 200
        private const val POLL_MS = 3_000L
        private const val LOG_FLUSH_MS = 1_000L
        private const val OUTPUT_DEBOUNCE_MS = 200L

        private fun toExtPane(p: PaneInfo) = ExtPane(
            id = p.paneId, title = p.title2, agent = p.agentName,
            status = p.agentStatus.lowercase(), cwd = p.foregroundCwd ?: p.cwd,
        )

        /** herdr key name → bytes typed into a plain shell. */
        internal fun keyBytes(name: String): String {
            val k = name.lowercase()
            Regex("""^(?:ctrl|c)[-+]([a-z])$""").find(k)?.let { return (it.groupValues[1][0] - 'a' + 1).toChar().toString() }
            return when (k) {
                "enter", "return" -> "\r"
                "esc", "escape" -> "\u001b"
                "tab" -> "\t"
                "backspace" -> "\u007f"
                "space" -> " "
                "up" -> "\u001b[A"
                "down" -> "\u001b[B"
                "right" -> "\u001b[C"
                "left" -> "\u001b[D"
                "home" -> "\u001b[H"
                "end" -> "\u001b[F"
                "pageup" -> "\u001b[5~"
                "pagedown" -> "\u001b[6~"
                "delete" -> "\u001b[3~"
                else -> if (name.length == 1) name else error("unknown key: $name")
            }
        }
    }
}
