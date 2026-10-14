package com.shibaken.androidterminal

import com.shibaken.androidterminal.data.HostProfile
import com.shibaken.androidterminal.herdr.*
import com.shibaken.androidterminal.ssh.RemoteCommand
import com.shibaken.androidterminal.ssh.MoshBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HerdrSessionsTest {
    @Test fun discoveryPreservesExactNamesAndAuthoritativeDefaultSocket() {
        val list = parseHerdrSessions("""{"sessions":[{"name":"default","default":true,"running":true,"socket_path":"/cfg/herdr/herdr.sock","session_dir":"/cfg/herdr"},{"name":"Work-v2","default":false,"running":false,"socket_path":"/custom/Work-v2.sock"}]}""")
        assertEquals(listOf("default", "Work-v2"), list.map { it.name })
        assertEquals("/cfg/herdr/herdr.sock", list.first().socketPath)
        assertFalse(list.last().running)
        assertTrue(parseHerdrSessions("""{"sessions":[]}""").isEmpty())
    }

    @Test fun invalidDiscoveryFailsInsteadOfSelectingAndroid() {
        for (json in listOf("", "not json", "{}", "[]", """{"sessions":[{"name":"a/b","running":true,"socket_path":"/x"}]}""",
            """{"sessions":[{"name":"..","running":true,"socket_path":"/x"}]}""",
            """{"sessions":[{"name":"work","running":true,"socket_path":"relative"}]}""")) {
            assertTrue(json, runCatching { parseHerdrSessions(json) }.isFailure)
        }
    }

    @Test fun switchCommitsOnlyAfterFreshListAndSuccessfulPing() = runBlocking {
        val entry = HerdrSessionInfo("Work", "/custom/herdr.sock", true)
        val events = mutableListOf<String>()
        val selected = resolveRunningHerdrSession("Work", { events += "list"; listOf(entry) }) {
            events += "ping:${it.socketPath}"
        }
        events += "commit:${selected.name}"
        assertEquals(listOf("list", "ping:/custom/herdr.sock", "commit:Work"), events)
    }

    @Test fun failedDiscoveryStoppedMissingOrFailedPingNeverCommits() = runBlocking {
        val running = HerdrSessionInfo("work", "/x", true)
        var committed = "previous-success"
        for (scenario in 0..3) {
            val outcome = runCatching {
                val selected = resolveRunningHerdrSession("work", {
                    when (scenario) {
                        0 -> error("discovery failed")
                        1 -> listOf(running.copy(running = false))
                        2 -> emptyList()
                        else -> listOf(running)
                    }
                }) { error("ping failed") }
                committed = selected.name
            }
            assertTrue(outcome.isFailure)
            assertEquals("previous-success", committed)
        }
    }

    @Test fun rememberedSelectionIsScopedToHostAndExplicitConfiguration() {
        val host = HostProfile(id = "one", host = "host", username = "u")
        val key = herdrSessionPreferenceKey(host)
        assertEquals(key, herdrSessionPreferenceKey(host.copy(name = "Renamed", fontSizeSp = 18f)))
        for (changed in listOf(host.copy(id = "two"), host.copy(host = "other"), host.copy(port = 2222),
            host.copy(username = "other"), host.copy(herdrSession = "explicit"))) {
            assertNotEquals(key, herdrSessionPreferenceKey(changed))
        }
    }

    @Test fun pingTimeoutIsRetryableFailureAndDoesNotCommitSelection() = runBlocking {
        var committed = "previous"
        val outcome = runCatching {
            val entry = resolveRunningHerdrSession("work", { listOf(HerdrSessionInfo("work", "/x", true)) }) {
                withTimeout(10) { awaitCancellation() }
            }
            committed = entry.name
        }
        assertTrue(outcome.exceptionOrNull() is java.io.IOException)
        assertFalse(outcome.exceptionOrNull() is CancellationException)
        assertEquals("previous", committed)
    }

    @Test fun sshAndMoshAttachShareExactNameAndSocketPath() {
        val socket = "/custom/it's here/herdr.sock"
        val attach = RemoteCommand.herdrAttach("default", "term_1", socket)
        assertTrue(attach.contains("HERDR_SOCKET_PATH=${RemoteCommand.quote(socket)}"))
        assertTrue(attach.contains("HERDR_SESSION='default'"))
        assertTrue(MoshBootstrap.serverCommand("default", "term_1", socketPath = socket).contains(attach))
        assertTrue(RemoteCommand.herdrAttach("work", "term_1").contains("-u HERDR_SOCKET_PATH"))
    }

    @Test fun ordinaryDetachRetainsMoshPaneIdentityWhileSessionSwitchResetsIt() {
        val controller = HerdrController(AgentAlertListener { _, _, _ -> })
        try {
            val field = controller.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val state = field.get(controller) as kotlinx.coroutines.flow.MutableStateFlow<HerdrState>
            val populated = HerdrState(phase = HerdrState.Phase.READY,
                workspaces = listOf(WorkspaceInfo("w1", label = "work")),
                tabs = listOf(TabInfo("t1", "w1", label = "tab")),
                panes = listOf(PaneInfo("p1", "w1", "t1", terminalId = "term1")),
                focusedWorkspaceId = "w1", focusedTabId = "t1", focusedPaneId = "p1")
            state.value = populated
            controller.detach()
            assertEquals(HerdrState.Phase.IDLE, controller.state.value.phase)
            assertEquals(populated.panes, controller.state.value.panes)
            assertEquals("p1", controller.state.value.focusedPaneId)
            controller.detach(resetState = true)
            assertTrue(controller.state.value.panes.isEmpty())
            assertTrue(controller.state.value.tabs.isEmpty())
            assertTrue(controller.state.value.workspaces.isEmpty())
            assertNull(controller.state.value.focusedPaneId)
            assertTrue(controller.pendingTabCloses.value.isEmpty())
        } finally { controller.dispose() }
    }
}
