package com.shibaken.androidterminal

import com.shibaken.androidterminal.herdr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class DeferredTabClosuresTest {
    private val tabs = listOf("a", "b", "c").mapIndexed { i, id -> TabInfo(id, "w", i, id) }
    private val raw = HerdrState(tabs = tabs, panes = tabs.map { PaneInfo("p${it.tabId}", "w", it.tabId) },
        focusedWorkspaceId = "w", focusedTabId = "a", focusedPaneId = "pa")
    private fun close(id: String, replacement: String? = null) = PendingTabClose(id, id, id == "a", replacement)

    @Test fun undoKeepsRemoteSessionAlive() = runBlocking {
        val queue = DeferredTabClosures(this, 80)
        var commits = 0
        assertTrue(queue.request(close("a", "b")) { commits++; true })
        assertEquals(listOf("b", "c"), raw.withPendingTabsHidden(queue.pending.value).tabs.map { it.tabId })
        assertEquals(3, raw.panes.size)
        assertNotNull(queue.undo("a"))
        delay(120)
        assertEquals(0, commits)
        assertEquals(raw, raw.withPendingTabsHidden(queue.pending.value))
    }

    @Test fun deadlineCommitsExactlyOnceAndWaitsForSnapshot() = runBlocking {
        val queue = DeferredTabClosures(this, 20)
        val committed = Channel<Unit>(1)
        assertTrue(queue.request(close("a")) { committed.send(Unit); true })
        assertFalse(queue.request(close("a")) { error("duplicate") })
        withTimeout(1_000) { committed.receive() }
        assertFalse(queue.pending.value.single().undoable)
        assertNull(queue.undo("a"))
        queue.reconcile(setOf("b", "c"))
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test fun failedCloseRestoresTheTab() = runBlocking {
        val queue = DeferredTabClosures(this, 20)
        queue.request(close("a")) { false }
        withTimeout(1_000) { while (queue.pending.value.isNotEmpty()) delay(5) }
        assertEquals(raw, raw.withPendingTabsHidden(queue.pending.value))
    }

    @Test fun disconnectOrReconnectCancelsAllPendingRemoteCloses() = runBlocking {
        val queue = DeferredTabClosures(this, 40)
        var commits = 0
        queue.request(close("a")) { commits++; true }
        queue.request(close("b")) { commits++; true }
        queue.cancelAll()
        delay(80)
        assertEquals(0, commits)
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test fun consecutiveClosesAndUndoKeepStableOrderAndValidFocus() {
        val a = close("a", "b"); val b = close("b", "c")
        val hidden = raw.withPendingTabsHidden(listOf(a, b))
        assertEquals(listOf("c"), hidden.tabs.map { it.tabId })
        assertEquals("c", hidden.focusedTabId)
        assertEquals("pc", hidden.focusedPaneId)
        val undone = raw.withPendingTabsHidden(listOf(a))
        assertEquals(listOf("b", "c"), undone.tabs.map { it.tabId })
        assertEquals("b", undone.focusedTabId)
    }

    @Test fun closingAllTabsClearsPaneFocusAndUndoRestoresOriginal() {
        val hidden = raw.withPendingTabsHidden(tabs.map { close(it.tabId) })
        assertTrue(hidden.tabs.isEmpty())
        assertTrue(hidden.panes.isEmpty())
        assertNull(hidden.focusedTabId)
        assertNull(hidden.focusedPaneId)
        assertEquals("w", hidden.focusedWorkspaceId)
        assertEquals(raw, raw.withPendingTabsHidden(emptyList()))
    }

    @Test fun closingLastWorkspaceTabFallsBackToAnotherWorkspace() {
        val s = raw.copy(tabs = listOf(tabs.first(), TabInfo("other", "w2")), panes = listOf(raw.panes.first(), PaneInfo("otherPane", "w2", "other")))
        val projected = s.withPendingTabsHidden(listOf(close("a", "other")))
        assertEquals("other", projected.focusedTabId)
        assertEquals("w2", projected.focusedWorkspaceId)
        assertEquals("otherPane", projected.focusedPaneId)
    }
}
