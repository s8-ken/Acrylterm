package com.shibaken.androidterminal

import com.shibaken.androidterminal.herdr.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HerdrWorktreesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun agentNamesSkipUsedAndSanitize() {
        assertEquals("claude", agentNameFor("claude", emptySet()))
        assertEquals("claude-2", agentNameFor("claude", setOf("claude")))
        assertEquals("claude-3", agentNameFor("Claude", setOf("claude", "claude-2")))
        assertEquals("my-agent", agentNameFor("My Agent", emptySet()))
        assertEquals("agent", agentNameFor("123", emptySet()))
        assertTrue(Regex("[a-z][a-z0-9_-]{0,31}").matches(agentNameFor("x".repeat(60), setOf("x".repeat(28)))))
    }

    @Test fun parsesWorktreesUnderAnyWrapperKey() {
        val r = obj("""{"type":"worktree_list","worktrees":[
            {"branch":"main","is_bare":false,"is_detached":false,"is_linked_worktree":false,"is_prunable":false,"label":"repo","open_workspace_id":"w1","path":"/r/repo"},
            {"branch":null,"is_bare":false,"is_detached":true,"is_linked_worktree":true,"is_prunable":false,"label":"","path":"/r/repo-x/"}]}""")
        val l = parseWorktrees(r)
        assertEquals(2, l.size)
        assertEquals("w1", l[0].openWorkspaceId)
        assertEquals("main", l[0].title)
        assertNull(l[1].openWorkspaceId)
        assertTrue(l[1].isLinked)
        assertEquals("repo-x", l[1].dirName)
        assertTrue(parseWorktrees(obj("""{"a":1}""")).isEmpty())
    }

    @Test fun trustIsOnlySentWhenRequested() {
        assertFalse("trust_repository" in worktreeCreateParams("w", "b", null))
        assertFalse("trust_repository" in worktreeOpenParams("w", "/p"))
        assertFalse("trust_repository" in worktreeListParams("w"))
        assertEquals(JsonPrimitive(true), worktreeCreateParams("w", "b", "main", trust = true)["trust_repository"])
        assertEquals("main", worktreeCreateParams("w", " b ", " main ")["base"]?.jsonPrimitive?.content?.let { it })
        assertFalse("base" in worktreeCreateParams("w", "b", "  "))
        assertFalse("force" in worktreeRemoveParams("w", false))
        assertTrue("force" in worktreeRemoveParams("w", true))
        assertFalse("close_group" in workspaceCloseParams("w", false))
        assertTrue("close_group" in workspaceCloseParams("w", true))
    }

    @Test fun trustErrorAsksInsteadOfRetrying() = runBlocking {
        val calls = mutableListOf<JsonObject>()
        val rpc = HerdrRpc { _, p -> calls += p; throw HerdrException("repository_not_trusted", "untrusted") }
        assertEquals(WorktreeOutcome.NEEDS_TRUST, requestWorktreeCreate("w", "b", null, false, rpc))
        assertEquals(1, calls.size)
        assertFalse("trust_repository" in calls[0])
        try { requestWorktreeOpen("w", "/p", true, rpc); fail() } catch (_: HerdrException) {}
    }

    @Test fun groupCloseRequiredIsSurfacedOnlyForPlainClose() = runBlocking {
        val rpc = HerdrRpc { _, p -> if ("close_group" in p) JsonObject(emptyMap()) else throw HerdrException("workspace_group_close_required", "x") }
        assertTrue(requestWorkspaceClose("w", false, rpc))
        assertFalse(requestWorkspaceClose("w", true, rpc))
    }

    @Test fun agentStartRetriesNameConflictAndToleratesNotReady() = runBlocking {
        val names = mutableListOf<String>()
        val name = startAgent("claude", "p", { emptySet() }, HerdrRpc { _, p ->
            names += p["name"]!!.jsonPrimitive.content
            if (names.size <= 2) throw HerdrException("agent_name_taken", "taken")
            JsonObject(emptyMap())
        })
        assertEquals(listOf("claude", "claude-2", "claude-3"), names)
        assertEquals("claude-3", name)
        startAgent("codex", "p", { emptySet() }, HerdrRpc { _, _ -> throw HerdrException("agent_not_ready", "blocked") })
        try { startAgent("codex", "p", { emptySet() }, HerdrRpc { _, _ -> throw HerdrException("unsupported_agent_kind", "x") }); fail() }
        catch (_: HerdrException) {}
    }

    @Test fun agentStartWaitsForTheShellPrompt() = runBlocking {
        var calls = 0
        startAgent("claude", "p", { emptySet() }, HerdrRpc { _, _ ->
            if (++calls < 3) throw HerdrException("agent_pane_busy", "busy")
            JsonObject(emptyMap())
        }, retryDelayMs = 0)
        assertEquals(3, calls)
        calls = 0
        try { startAgent("claude", "p", { emptySet() }, HerdrRpc { _, _ -> calls++; throw HerdrException("agent_pane_unavailable", "x") }, retryDelayMs = 0); fail() }
        catch (_: HerdrException) {}
        assertEquals(11, calls)
    }

    @Test fun rootPaneIdReadsWorkspaceCreatedResults() {
        assertEquals("w2:p1", rootPaneId(obj("""{"type":"workspace_created","workspace":{},"tab":{},"root_pane":{"pane_id":"w2:p1"}}""")))
        assertNull(rootPaneId(obj("""{"type":"ok"}""")))
    }

    @Test fun newTabWithPaneReturnsRootPane() = runBlocking {
        val r = requestNewTabWithPane("w", false, HerdrRpc { _, _ -> obj("""{"tab":{"tab_id":"t"},"root_pane":{"pane_id":"p9"}}""") })
        assertEquals("p9", r.rootPaneId)
        assertEquals(TabCreationOutcome.CREATED, r.outcome)
    }

    @Test fun workspaceWorktreeFieldDeserializes() {
        val ws = Json { ignoreUnknownKeys = true }.decodeFromString<WorkspaceInfo>(
            """{"workspace_id":"w","worktree":{"repo_name":"r","is_linked_worktree":true}}""")
        assertTrue(ws.worktree!!.isLinkedWorktree)
    }
}
