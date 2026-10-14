package com.shibaken.androidterminal.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shibaken.androidterminal.ssh.PaneStream
import com.shibaken.androidterminal.terminal.TerminalBackend
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * A Termux [TerminalEmulator] fed by an SSH PTY channel or a Mosh session ([PaneStream]).
 *
 * The emulator outlives channels: [bind] attaches a channel, [detach]/[release] drop it again
 * while the last screen stays visible, so a pane can be re-attached later without the UI
 * swapping its backend. [close] ends the terminal for good.
 *
 * Threading: everything public is main-thread. SSH reads run on a daemon thread and are posted
 * to the main thread (emulator is main-thread only); writes/window-changes go through an
 * ordered queue consumed by an IO coroutine. [scope] must be a main-thread scope.
 */
class PaneTerminal(
    private val context: Context,
    private val scope: CoroutineScope,
    /** When true every 0x02 byte typed by the user is doubled (herdr attach treats a lone ^B as its prefix). */
    private val escapeCtrlB: Boolean = false,
    cols: Int = 80,
    rows: Int = 24,
    cellWidth: Int = 10,
    cellHeight: Int = 20,
    private val tag: String = "pane",
) : TerminalBackend {

    var cols = cols; private set
    var rows = rows; private set
    var cellW = cellWidth; private set
    var cellH = cellHeight; private set

    private val main = Handler(Looper.getMainLooper())

    override var onScreenUpdate: (() -> Unit)? = null
    var onBell: (() -> Unit)? = null

    /** Main thread. Fired when the user typed/pasted something (not for terminal-generated ESC [ reports). */
    var onUserInput: (() -> Unit)? = null

    /** Main thread. Fired when the current channel ended on its own (not via [detach]/[release]/[close]). */
    var onChannelClosed: ((PaneTerminal) -> Unit)? = null

    /** Main thread. Fired after a size change was applied (for remembering a default size). */
    var onResized: ((cols: Int, rows: Int, cellW: Int, cellH: Int) -> Unit)? = null

    private val _title = MutableStateFlow<String?>(null)
    /** Terminal title set by the remote program; null when blank/unset. */
    val title: StateFlow<String?> = _title.asStateFlow()

    private val _screenRevision = MutableStateFlow(0L)
    /** Bumped whenever remote output was appended; lets observers react without touching [onScreenUpdate]. */
    val screenRevision: StateFlow<Long> = _screenRevision.asStateFlow()

    private val _attached = MutableStateFlow(false)
    /** True while a channel is bound. */
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    private val _closed = MutableStateFlow(false)
    /** True after [close]; the terminal is unusable. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            send(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            _title.value = newTitle?.takeIf { it.isNotBlank() }
        }

        override fun onCopyTextToClipboard(text: String?) {
            if (text == null) return
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard() {
            pasteFromClipboard()
        }

        override fun onBell() {
            this@PaneTerminal.onBell?.invoke()
        }

        override fun onColorsChanged() {
            onScreenUpdate?.invoke()
        }
    }

    override val emulator = TerminalEmulator(output, cols, rows, cellWidth, cellHeight, 5000, EmulatorClient)

    private class CheckedWrite(val bytes: ByteArray, val done: CompletableDeferred<Unit>)

    private class Binding(val channel: PaneStream, val gen: Int) {
        val queue = Channel<Any>(Channel.UNLIMITED, onUndeliveredElement = { item ->
            if (item is CheckedWrite) item.done.completeExceptionally(java.io.IOException("terminal disconnected before input was written"))
        })
        /** Graceful detach: the writer closes the channel once the queue is drained. */
        @Volatile var closeWhenDrained = false
    }

    private var binding: Binding? = null

    /** The stream currently bound (main thread), if any. */
    val boundStream: PaneStream? get() = binding?.channel
    private var gen = 0

    /** Attaches [channel]; any previous channel is dropped (closed in the background). */
    fun bind(channel: PaneStream) {
        if (_closed.value) {
            thread(name = "pty-close", isDaemon = true) { channel.close() }
            return
        }
        release()?.let { old -> thread(name = "pty-close", isDaemon = true) { old.close() } }
        // A herdr attach redraws everything from scratch: drop leftovers (alt screen, modes) of the previous attach.
        if (escapeCtrlB) emulator.reset()
        val b = Binding(channel, ++gen)
        binding = b
        _attached.value = true
        startReader(b)
        startWriter(b)
        // The size may have changed while the channel was being opened.
        if (channel.cols != cols || channel.rows != rows) b.queue.trySend(windowChange())
    }

    /**
     * Drops the channel without closing it and returns it; the caller closes it (off the main
     * thread). Pending output of the old channel is ignored.
     */
    fun release(): PaneStream? {
        val b = binding ?: return null
        binding = null
        gen++
        b.queue.close()
        _attached.value = false
        return b.channel
    }

    /**
     * Graceful detach for herdr attach: sends `^B q` (raw, not escaped) and then closes the
     * channel from the writer once it is flushed.
     */
    fun detach() {
        val b = binding ?: return
        binding = null
        gen++
        _attached.value = false
        b.closeWhenDrained = true
        if (escapeCtrlB) b.queue.trySend(DETACH_KEYS)
        b.queue.close()
    }

    /** Permanently ends this terminal (channel closed in the background). */
    fun close() {
        if (_closed.value) return
        _closed.value = true
        onChannelClosed = null
        release()?.let { ch -> thread(name = "pty-close", isDaemon = true) { ch.close() } }
    }

    private fun startReader(b: Binding) {
        thread(name = "pty-read-$tag", isDaemon = true) {
            val buf = ByteArray(16 * 1024)
            try {
                val input = b.channel.input
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    main.post {
                        if (b.gen != gen) return@post
                        emulator.append(chunk, chunk.size)
                        _screenRevision.value += 1
                        onScreenUpdate?.invoke()
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "reader ended ($tag): ${e.message}")
            }
            main.post {
                if (b.gen != gen || binding !== b) return@post
                // Ended on its own: forget the channel, then tell the owner.
                binding = null
                gen++
                b.queue.close()
                _attached.value = false
                thread(name = "pty-close", isDaemon = true) { b.channel.close() }
                onChannelClosed?.invoke(this)
            }
        }
    }

    private fun startWriter(b: Binding) {
        val q = b.queue
        scope.launch(Dispatchers.IO) {
            val out = b.channel.output
            val pending = ByteArrayOutputStream()
            try {
                fun flushBytes() {
                    if (pending.size() > 0) {
                        out.write(pending.toByteArray())
                        pending.reset()
                    }
                }
                fun handle(m: Any) {
                    when (m) {
                        is ByteArray -> pending.write(m)
                        is CheckedWrite -> {
                            try {
                                flushBytes()
                                out.write(m.bytes)
                                out.flush()
                                m.done.complete(Unit)
                            } catch (e: Exception) {
                                m.done.completeExceptionally(e)
                                throw e
                            }
                        }
                        is IntArray -> {
                            flushBytes() // keep ordering relative to input
                            out.flush()
                            b.channel.changeWindowSize(m[0], m[1], m[2], m[3])
                        }
                    }
                }
                for (m in q) {
                    handle(m)
                    // Coalesce whatever else is already queued before flushing.
                    while (true) {
                        val more = q.tryReceive().getOrNull() ?: break
                        handle(more)
                    }
                    flushBytes()
                    out.flush()
                }
            } catch (e: Exception) {
                Log.i(TAG, "writer ended ($tag): ${e.message}")
                // Nobody drains the queue any more: stop accepting bytes, and release the channel
                // (the reader then sees EOF and reports the channel as ended).
                q.cancel()
                b.channel.close()
            }
            if (b.closeWhenDrained) {
                delay(150) // let the remote act on the detach keys before the channel goes away
                b.channel.close()
            }
        }
    }

    private fun windowChange() = intArrayOf(cols, rows, cols * cellW, rows * cellH)

    /** Sends raw bytes to the remote PTY (no-op while no channel is bound). Main thread. */
    fun send(bytes: ByteArray) {
        val b = binding ?: return
        // ESC [ ... = mouse/focus/cursor reports generated by the emulator, not typing.
        if (bytes.isNotEmpty() && !(bytes.size >= 2 && bytes[0] == 0x1b.toByte() && bytes[1] == '['.code.toByte())) onUserInput?.invoke()
        b.queue.trySend(if (escapeCtrlB) doubleCtrlB(bytes) else bytes)
    }

    /** Main thread. Unlike UI typing, extension input reports unavailable/failed writes. */
    suspend fun sendChecked(text: String) {
        val b = binding ?: throw java.io.IOException("terminal is not connected")
        val bytes = text.toByteArray(Charsets.UTF_8)
        val done = CompletableDeferred<Unit>()
        b.queue.trySend(CheckedWrite(if (escapeCtrlB) doubleCtrlB(bytes) else bytes, done)).getOrThrow()
        if (bytes.isNotEmpty()) onUserInput?.invoke()
        done.await()
    }

    /** Main thread. Text of the visible screen rows, top to bottom (trailing blanks trimmed by the buffer). */
    fun visibleLines(): List<String> {
        val e = emulator
        return (0 until e.mRows).map { r -> e.screen.getSelectedText(0, r, e.mColumns - 1, r, true).trimEnd('\n') }
    }

    /** Main thread. The last [n] non-trailing-blank lines of scrollback + screen. */
    fun tailText(n: Int): String {
        val e = emulator
        val first = maxOf(-e.screen.activeTranscriptRows, e.mRows - 1 - (n + 200))
        val lines = (first until e.mRows).map { r -> e.screen.getSelectedText(0, r, e.mColumns - 1, r, true).trimEnd('\n') }
        return lines.dropLastWhile { it.isBlank() }.takeLast(n).joinToString("\n")
    }

    override fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    override fun pasteFromClipboard() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        // TerminalEmulator.paste handles bracketed-paste mode and newline normalisation.
        emulator.paste(text)
    }

    /** Main thread. Re-reads the default colors from the color scheme (after the user changed the profile). */
    fun refreshColors() {
        emulator.mColors.reset()
        onScreenUpdate?.invoke()
    }

    override fun resize(newCols: Int, newRows: Int, cellWidth: Int, cellHeight: Int) {
        if (newCols < 4 || newRows < 2) return
        if (newCols == cols && newRows == rows && cellWidth == cellW && cellHeight == cellH) return
        cols = newCols; rows = newRows; cellW = cellWidth; cellH = cellHeight
        emulator.resize(cols, rows, cellW, cellH)
        binding?.queue?.trySend(windowChange())
        onResized?.invoke(cols, rows, cellW, cellH)
        onScreenUpdate?.invoke()
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
        private const val TAG = "PaneTerminal"
        /** herdr attach: prefix (^B) then q = detach. Sent raw, never doubled. */
        private val DETACH_KEYS = byteArrayOf(0x02, 'q'.code.toByte())

        /** Doubles every 0x02 byte (herdr attach: a literal ^B is `^B ^B`). */
        fun doubleCtrlB(bytes: ByteArray): ByteArray {
            if (bytes.none { it == 0x02.toByte() }) return bytes
            val out = ByteArrayOutputStream(bytes.size + 4)
            for (x in bytes) {
                out.write(x.toInt())
                if (x == 0x02.toByte()) out.write(0x02)
            }
            return out.toByteArray()
        }
    }
}
