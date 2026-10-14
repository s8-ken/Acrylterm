package com.shibaken.androidterminal

import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.herdr.CellRect
import com.shibaken.androidterminal.herdr.LayoutPane
import com.shibaken.androidterminal.herdr.SessionSnapshot
import com.shibaken.androidterminal.herdr.TabLayout
import com.shibaken.androidterminal.session.PaneTerminal
import com.shibaken.androidterminal.ssh.RemoteCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanesLogicTest {
    @Test fun ctrlBIsDoubled() {
        assertArrayEquals(byteArrayOf(0x02, 0x02), PaneTerminal.doubleCtrlB(byteArrayOf(0x02)))
        assertArrayEquals(
            byteArrayOf(0x61, 0x02, 0x02, 0x62, 0x02, 0x02),
            PaneTerminal.doubleCtrlB(byteArrayOf(0x61, 0x02, 0x62, 0x02)),
        )
        val plain = byteArrayOf(0x61, 0x62)
        assertEquals(plain.toList(), PaneTerminal.doubleCtrlB(plain).toList())
    }

    @Test fun quoting() {
        assertEquals("'abc'", RemoteCommand.quote("abc"))
        assertEquals("'it'\\''s'", RemoteCommand.quote("it's"))
        assertEquals("exec env PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" \"\$SHELL\" -lc 'herdr --version'", RemoteCommand.viaLoginShell("herdr --version"))
        assertEquals("exec env PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:\$PATH\" \"\$SHELL\" -lc 'echo '\\''x'\\'''", RemoteCommand.viaLoginShell("echo 'x'"))
        assertEquals("a-b_1.2", RemoteCommand.sanitizeSessionName("a-b_1.2/; $"))
    }

    @Test fun attachCommandPutsOptionsAfterTerminalId() {
        assertEquals(
            "env -u HERDR_SOCKET_PATH HERDR_SESSION='android' herdr terminal attach 'term_65da' --takeover",
            RemoteCommand.herdrAttach("android", "term_65da"),
        )
    }

    @Test fun versions() {
        assertTrue(RemoteCommand.supportsTerminalAttach("herdr 0.8.0"))
        assertTrue(RemoteCommand.supportsTerminalAttach("0.7.2"))
        assertTrue(RemoteCommand.supportsTerminalAttach("herdr 1.0.0"))
        assertFalse(RemoteCommand.supportsTerminalAttach("herdr 0.7.1"))
        assertFalse(RemoteCommand.supportsTerminalAttach("command not found"))
    }

    @Test fun layoutNormalisation() {
        val l = TabLayout(
            tabId = "t", area = CellRect(26, 2, 100, 40),
            panes = listOf(
                LayoutPane("a", true, CellRect(26, 2, 50, 40)),
                LayoutPane("b", false, CellRect(76, 2, 50, 20)),
            ),
        )
        val n = l.normalizedPaneRects()
        assertEquals(0f, n.getValue("a").x, 1e-6f)
        assertEquals(0.5f, n.getValue("a").width, 1e-6f)
        assertEquals(1f, n.getValue("a").height, 1e-6f)
        assertEquals(0.5f, n.getValue("b").x, 1e-6f)
        assertEquals(0.5f, n.getValue("b").height, 1e-6f)
        assertEquals(1f, TabLayout().normalizedPaneRects().values.firstOrNull()?.width ?: 1f, 0f)
    }

    @Test fun snapshotParsesTerminalIdAndLayoutsIgnoringUnknownKeys() {
        val json = """{"version":"0.8.0","extra":1,"panes":[{"pane_id":"p1","terminal_id":"term_x",
          "scroll":{"offset_from_bottom":0,"max_offset_from_bottom":3,"viewport_rows":24,"zzz":1}}],
          "layouts":[{"workspace_id":"w","tab_id":"t","zoomed":false,"focused_pane_id":"p1",
          "area":{"x":0,"y":0,"width":80,"height":24},
          "panes":[{"pane_id":"p1","focused":true,"rect":{"x":0,"y":0,"width":80,"height":24}}],
          "splits":[{"id":3,"direction":"right","ratio":0.5,"rect":{"x":0,"y":0,"width":80,"height":24}}]}]}"""
        val s = AppJson.decodeFromString(SessionSnapshot.serializer(), json)
        assertEquals("term_x", s.panes[0].terminalId)
        assertEquals(3, s.panes[0].scroll?.maxOffsetFromBottom)
        assertEquals("t", s.layouts[0].tabId)
        assertEquals(1f, s.layouts[0].normalizedPaneRects().getValue("p1").width, 1e-6f)
    }
}
