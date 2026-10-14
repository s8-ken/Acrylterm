package com.shibaken.androidterminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MouseTrackingModeTest {
    private val sent = StringBuilder()

    private fun emulator(): TerminalEmulator {
        val out = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) { sent.append(String(data, offset, count)) }
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        val client = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(TerminalSessionClient::class.java),
        ) { _, _, _ -> null } as TerminalSessionClient
        return TerminalEmulator(out, 80, 6, 10, 20, 100, client)
    }

    private fun TerminalEmulator.feed(s: String) { val b = s.toByteArray(); append(b, b.size) }

    @Test fun anyEventOnlyAsMoshSendsIt() {
        // mosh-server reduces herdr's 1000/1002/1003 to ?1003h ?1006h.
        val e = emulator()
        e.feed("\u001b[?1049h\u001b[?1003l\u001b[?1002l\u001b[?1000l\u001b[?1003h\u001b[?1006h")
        assertTrue(e.isMouseTrackingActive)
        e.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 3, 2, true)
        assertEquals("\u001b[<64;3;2M", sent.toString())
        e.feed("\u001b[?1003l")
        assertFalse(e.isMouseTrackingActive)
    }

    @Test fun herdrSshSequenceKeepsTracking() {
        val e = emulator()
        e.feed("\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1015h\u001b[?1006h")
        assertTrue(e.isMouseTrackingActive)
    }
}
