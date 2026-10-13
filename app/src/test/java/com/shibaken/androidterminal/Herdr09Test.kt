package com.shibaken.androidterminal

import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.herdr.HerdrClient
import com.shibaken.androidterminal.herdr.SessionSnapshot
import com.shibaken.androidterminal.ssh.RemoteCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Herdr09Test {
    private val ws = """{"workspace_id":"w1","number":1,"focused":false},{"workspace_id":"w2","number":2,"focused":true}"""
    private val tabs = """{"tab_id":"t1","workspace_id":"w1","focused":true},{"tab_id":"t2","workspace_id":"w2","focused":true}"""
    private val panes = """{"pane_id":"p1","tab_id":"t1","workspace_id":"w1","focused":true},{"pane_id":"p2","tab_id":"t2","workspace_id":"w2","focused":true,"scroll":{"offset_from_bottom":3}}"""

    @Test fun topLevelFocusedIdsWin() {
        val s = AppJson.decodeFromString(
            SessionSnapshot.serializer(),
            """{"version":"0.9.3","focused_workspace_id":"w1","focused_tab_id":"t1","focused_pane_id":"p1",
               "workspaces":[$ws],"tabs":[$tabs],"panes":[$panes]}""",
        )
        assertEquals("w1", s.resolvedFocusedWorkspaceId)
        assertEquals("t1", s.resolvedFocusedTabId)
        assertEquals("p1", s.resolvedFocusedPaneId)
        assertEquals(3, s.panes[1].scroll?.offsetFromBottom)
    }

    @Test fun focusedFlagsFallbackFor08() {
        val s = AppJson.decodeFromString(
            SessionSnapshot.serializer(),
            """{"version":"0.8.0","workspaces":[$ws],"tabs":[$tabs],"panes":[$panes]}""",
        )
        assertEquals("w2", s.resolvedFocusedWorkspaceId)
        assertEquals("t2", s.resolvedFocusedTabId)
        assertEquals("p2", s.resolvedFocusedPaneId)
    }

    @Test fun eventNamesNormalised() {
        assertTrue(HerdrClient.sameEvent("pane.agent_status_changed", "pane_agent_status_changed"))
        assertTrue(HerdrClient.sameEvent("workspace_focused", "workspace.focused"))
        assertTrue(HerdrClient.sameEvent("events_lost", "events.lost"))
        assertFalse(HerdrClient.sameEvent("pane.focused", "pane.closed"))
    }

    @Test fun serverStatusParsing() {
        val v08 = "status: running\nversion: 0.8.0\nprotocol: 21\n"
        val v09 = "status: running\nversion: 0.9.3\nendpoint_compatible: yes\nprivate_protocol: 22\n"
        assertTrue(RemoteCommand.isServerRunning(v08))
        assertTrue(RemoteCommand.isServerRunning(v09))
        assertFalse(RemoteCommand.isServerRunning("status: not running\n"))
        assertFalse(RemoteCommand.isServerRunning(""))
    }

    @Test fun versionGates() {
        assertTrue(RemoteCommand.supportsTerminalAttach("herdr 0.9.3"))
        assertTrue(RemoteCommand.supportsTerminalAttach("herdr 0.7.2"))
        assertFalse(RemoteCommand.supportsTerminalAttach("herdr 0.7.1"))
        assertTrue(RemoteCommand.versionAtLeast("0.9.3", 0, 9))
        assertFalse(RemoteCommand.versionAtLeast("0.8.0", 0, 9))
        assertFalse(RemoteCommand.versionAtLeast("", 0, 9))
    }
}
