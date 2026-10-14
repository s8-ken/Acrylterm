package com.shibaken.androidterminal

import android.app.Instrumentation
import com.shibaken.androidterminal.session.PaneTerminal
import com.shibaken.androidterminal.ssh.PaneStream
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Tests the actual emulator paste encoding and ordered shell writer without touching a user's session. */
internal class ExtensionInputChecks(private val instrumentation: Instrumentation) {
    fun run() = runBlocking {
        withTimeout(10_000) {
            withContext(Dispatchers.Main) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                val terminal = PaneTerminal(instrumentation.targetContext, scope)
                val stream = RecordingStream()
                try {
                    try { terminal.sendChecked("echo"); error("disconnected input must fail") }
                    catch (_: IOException) { }
                    terminal.bind(stream)
                    val enablePaste = "\u001b[?2004h".toByteArray()
                    terminal.emulator.append(enablePaste, enablePaste.size)
                    for (newline in listOf("\n", "\r\n", "\r")) {
                        val paste = terminal.emulator.encodePaste("echo 日本語${newline}echo done")
                        check(paste == "\u001b[200~echo 日本語\recho done\u001b[201~")
                        terminal.sendChecked(paste)
                        terminal.sendChecked(paste + "\r")
                    }
                    val emptySubmit = "\r"
                    terminal.sendChecked(emptySubmit)
                    val raw = "\u0003\r\n\u001b[A"
                    terminal.sendChecked(raw)
                    val frame = "\u001b[200~echo 日本語\recho done\u001b[201~"
                    check(stream.text() == (frame + frame + "\r").repeat(3) + emptySubmit + raw)

                    val disablePaste = "\u001b[?2004l".toByteArray()
                    terminal.emulator.append(disablePaste, disablePaste.size)
                    check(terminal.emulator.encodePaste("a\r\nb\nc\r") == "a\rb\rc\r")
                    // A failed flush/write must reach Lua's blocking host call, never hang or silently succeed.
                    stream.failWrites = true
                    try { terminal.sendChecked("echo"); error("failed input must throw") }
                    catch (error: IOException) { check(error.message == "test write failed") }
                } finally {
                    terminal.close()
                    stream.close()
                    scope.cancel()
                }
            }
        }
    }

    private class RecordingStream : PaneStream {
        override val input = PipedInputStream()
        private val keepOpen = PipedOutputStream(input)
        private val captured = ByteArrayOutputStream()
        @Volatile var failWrites = false
        override val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (failWrites) throw IOException("test write failed")
                synchronized(captured) { captured.write(bytes, offset, length) }
            }
        }
        override val cols = 80
        override val rows = 24
        override fun changeWindowSize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {}
        fun text(): String = synchronized(captured) { captured.toString("UTF-8") }
        override fun close() { runCatching { keepOpen.close() }; runCatching { input.close() } }
    }
}
