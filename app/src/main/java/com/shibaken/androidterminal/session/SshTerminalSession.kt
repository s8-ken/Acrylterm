package com.shibaken.androidterminal.session

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.herdr.AgentAlertListener
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.ssh.SshCancelledException
import com.shibaken.androidterminal.ssh.SshConnection
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

sealed class ConnState {
    data class Connecting(val message: String) : ConnState()
    data object Connected : ConnState()
    data class Disconnected(val reason: String?) : ConnState()
}

/**
 * One terminal tab: an SSH shell feeding a Termux [TerminalEmulator], plus (optionally) a
 * [HerdrController] talking to herdr over the same SSH connection.
 *
 * Threading: the emulator is only touched on the main thread. SSH reads happen on a
 * dedicated thread and are posted to the main thread; writes are queued to an IO coroutine.
 */
class SshTerminalSession(
    private val app: App,
    profile: HostProfile,
    alerts: AgentAlertListener,
) {
    val id: String = UUID.randomUUID().toString()
    var profile: HostProfile = profile
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ConnState>(ConnState.Connecting("準備中…"))
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _title = MutableStateFlow(profile.displayName)
    val title: StateFlow<String> = _title.asStateFlow()

    val herdr: HerdrController? = if (profile.herdrEnabled) HerdrController(alerts) else null

    // --- emulator --------------------------------------------------------------------
    private var cols = 80
    private var rows = 24
    private var cellW = 10
    private var cellH = 20

    /** Called on the main thread after new output has been applied to the emulator. */
    var onScreenUpdate: (() -> Unit)? = null
    var onBell: (() -> Unit)? = null

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            send(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            _title.value = newTitle?.takeIf { it.isNotBlank() } ?: this@SshTerminalSession.profile.displayName
        }

        override fun onCopyTextToClipboard(text: String?) {
            if (text == null) return
            val cm = app.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard() {
            pasteFromClipboard()
        }

        override fun onBell() {
            this@SshTerminalSession.onBell?.invoke()
        }

        override fun onColorsChanged() {
            onScreenUpdate?.invoke()
        }
    }

    val emulator = TerminalEmulator(output, cols, rows, cellW, cellH, 5000, EmulatorClient)

    // --- ssh -------------------------------------------------------------------------
    private var conn: SshConnection? = null
    private var shellSession: Session? = null
    private var shellOut: OutputStream? = null
    private var writeQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var connectJob: Job? = null
    /** Bumped on every (re)connect so a stale reader thread cannot tear down a newer transport. */
    private var generation = 0

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { doConnect() }
    }

    private suspend fun doConnect() {
        closeTransport()
        profile = app.hosts.get(profile.id) ?: profile
        val c = SshConnection(app, profile)
        conn = c
        try {
            c.connect { msg -> _state.value = ConnState.Connecting(msg) }
            val (session, shell) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                c.openShell(cols, rows, cols * cellW, rows * cellH)
            }
            shellSession = session
            shellOut = shell.outputStream
            startWriter(shell.outputStream)
            startReader(shell.inputStream)
            _state.value = ConnState.Connected
            if (profile.startupCommand.isNotBlank()) send((profile.startupCommand + "\r").toByteArray())
            herdr?.attach(c, profile.herdrSession)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            if (conn === c) conn = null
            thread(name = "ssh-close", isDaemon = true) { c.close() }
            val msg = if (e is SshCancelledException) e.message else (e.message ?: e.javaClass.simpleName)
            _state.value = ConnState.Disconnected(msg)
        }
    }

    private fun startReader(input: InputStream) {
        val gen = ++generation
        thread(name = "ssh-read-$id", isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    main.post {
                        emulator.append(chunk, chunk.size)
                        onScreenUpdate?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "reader ended: ${e.message}")
            }
            main.post { if (gen == generation) onTransportClosed() }
        }
    }

    private fun startWriter(out: OutputStream) {
        writeQueue.close()
        val q = Channel<ByteArray>(Channel.UNLIMITED)
        writeQueue = q
        scope.launch(Dispatchers.IO) {
            try {
                for (bytes in q) {
                    out.write(bytes)
                    // Coalesce whatever else is already queued before flushing.
                    while (true) {
                        val more = q.tryReceive().getOrNull() ?: break
                        out.write(more)
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                Log.i(TAG, "writer ended: ${e.message}")
            }
        }
    }

    private fun onTransportClosed() {
        if (_state.value is ConnState.Connected) {
            _state.value = ConnState.Disconnected("接続が切れました")
        }
        herdr?.detach()
        closeTransport()
    }

    /** Main thread. Socket writes (channel close / disconnect) happen on a background thread. */
    private fun closeTransport() {
        generation++
        writeQueue.close()
        shellOut = null
        val s = shellSession
        val c = conn
        shellSession = null
        conn = null
        if (s != null || c != null) {
            thread(name = "ssh-close", isDaemon = true) {
                runCatching { s?.close() }
                c?.close()
            }
        }
    }

    /** Sends raw bytes to the remote PTY (no-op while disconnected). */
    fun send(bytes: ByteArray) {
        if (_state.value !is ConnState.Connected && shellOut == null) return
        writeQueue.trySend(bytes)
    }

    fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    fun pasteFromClipboard() {
        val cm = app.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString() ?: return
        // TerminalEmulator.paste handles bracketed-paste mode and newline normalisation.
        emulator.paste(text)
    }

    /** Called by the view when its size in cells changes. Main thread. */
    fun resize(newCols: Int, newRows: Int, cellWidth: Int, cellHeight: Int) {
        if (newCols < 4 || newRows < 2) return
        if (newCols == cols && newRows == rows && cellWidth == cellW && cellHeight == cellH) return
        cols = newCols; rows = newRows; cellW = cellWidth; cellH = cellHeight
        emulator.resize(cols, rows, cellW, cellH)
        val s = shellSession ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                // Session.Shell is the same channel object as the Session.
                (s as Session.Shell).changeWindowDimensions(cols, rows, cols * cellW, rows * cellH)
            }.onFailure { Log.w(TAG, "window change failed", it) }
        }
        onScreenUpdate?.invoke()
    }

    fun disconnect() {
        connectJob?.cancel()
        generation++
        herdr?.detach()
        closeTransport()
        _state.value = ConnState.Disconnected(null)
    }

    fun dispose() {
        disconnect()
        herdr?.dispose()
        scope.cancel()
    }

    private object EmulatorClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag ?: TAG, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "error", e) }
    }

    companion object {
        private const val TAG = "SshTerminalSession"
    }
}
