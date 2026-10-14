package com.shibaken.androidterminal

import com.shibaken.androidterminal.herdr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HerdrUiOperationsTest {
    private val emptyResult = JsonObject(emptyMap())
    private val state = HerdrState(workspaces = listOf(WorkspaceInfo("w1"), WorkspaceInfo("w2")),
        tabs = listOf(TabInfo("a", "w1"), TabInfo("b", "w2")),
        panes = listOf(PaneInfo("pa", "w1", "a"), PaneInfo("pb", "w2", "b")), focusedWorkspaceId = "w1")

    @Test fun emptyWorkspaceStatePromptsBeforeSendingAnyRpc() = runBlocking {
        assertEquals(TabCreationOutcome.NEEDS_WORKSPACE, requestNewTab(null, true, HerdrRpc { _, _ -> error("No RPC before confirmation") }))
    }

    @Test fun staleSnapshotMissingWorkspacePromptsInsteadOfGenericError() = runBlocking {
        for (error in listOf(HerdrException("invalid_params", "no active workspace"),
            HerdrException("no_active_workspace", "missing"), HerdrException("workspace_not_found", "stale id"))) {
            assertEquals(TabCreationOutcome.NEEDS_WORKSPACE, requestNewTab("stale", false, HerdrRpc { _, _ -> throw error }))
        }
    }

    @Test fun unrelatedTabCreationErrorIsNotConvertedIntoWorkspaceCreation() = runBlocking {
        val error = HerdrException("permission_denied", "not allowed")
        try { requestNewTab("w1", false, HerdrRpc { _, _ -> throw error }); fail("should throw") }
        catch (actual: HerdrException) { assertSame(error, actual) }
    }

    @Test fun existingWorkspaceCreatesFocusedTabWithExplicitId() = runBlocking {
        val calls = mutableListOf<String>()
        assertEquals(TabCreationOutcome.CREATED, requestNewTab("w2", false, HerdrRpc { method, params ->
            calls += method
            assertEquals("w2", params["workspace_id"]?.jsonPrimitive?.content)
            assertEquals("true", params["focus"]?.jsonPrimitive?.content)
            emptyResult
        }))
        assertEquals(listOf("tab.create"), calls)
    }

    @Test fun confirmedNewWorkspaceCreatesItsInitialTabWithoutDuplicateTabRpc() = runBlocking {
        val calls = mutableListOf<String>()
        createWorkspaceWithInitialTab("  Project  ", HerdrRpc { method, params ->
            calls += method
            assertEquals("Project", params["label"]?.jsonPrimitive?.content)
            assertEquals("true", params["focus"]?.jsonPrimitive?.content)
            emptyResult
        })
        assertEquals(listOf("workspace.create"), calls)
    }

    @Test fun blankWorkspaceNameIsOmitted() = runBlocking {
        createWorkspaceWithInitialTab("  ", HerdrRpc { _, params -> assertNull(params["label"]); emptyResult })
        Unit
    }

    @Test fun confirmationPlanIncludesEveryWorkspaceAndFreezesTargetIds() {
        val plan = state.bulkClosePlan(BulkCloseKind.TABS)
        assertEquals(listOf("a", "b"), plan.ids)
        assertEquals(2, plan.workspaceCount)
        assertEquals(2, plan.tabCount)
        assertEquals(2, plan.paneCount)
        assertEquals(listOf("w1", "w2"), state.bulkClosePlan(BulkCloseKind.WORKSPACES).ids)
        assertEquals(listOf("a", "b"), plan.ids) // Later state changes cannot expand a reviewed batch.
    }

    @Test fun bulkTabsSkipAlreadyCommittedCloseAndDeduplicateIds() = runBlocking {
        val called = mutableListOf<String>()
        val plan = state.bulkClosePlan(BulkCloseKind.TABS).copy(ids = listOf("a", "a", "b", "gone"))
        val result = runBulkClose(plan, setOf("a"), { setOf("a", "b") }) { called += it }
        assertEquals(listOf("b"), called)
        assertEquals(3, result.completed)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun partialFailureContinuesAndReportsFailedTarget() = runBlocking {
        val called = mutableListOf<String>()
        val result = runBulkClose(state.bulkClosePlan(BulkCloseKind.TABS), emptySet(), { setOf("a", "b") }) {
            called += it
            if (it == "a") throw HerdrException("server_unavailable", "try again")
        }
        assertEquals(listOf("a", "b"), called)
        assertEquals(1, result.completed)
        assertEquals("a", result.failures.single().id)
    }

    @Test fun workspaceGroupRequirementRetriesThatIdWithGroupClose() = runBlocking {
        val called = mutableListOf<String>()
        val grouped = mutableListOf<String>()
        val result = runBulkClose(state.bulkClosePlan(BulkCloseKind.WORKSPACES), emptySet(), { setOf("w1", "w2") },
            closeGroup = { grouped += it }) {
            called += it
            if (it == "w1") throw HerdrException("workspace_group_close_required", "group required")
        }
        assertEquals(listOf("w1", "w2"), called)
        assertEquals(listOf("w1"), grouped)
        assertEquals(2, result.completed)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun groupRetryIsNotUsedForTabs() = runBlocking {
        val result = runBulkClose(state.bulkClosePlan(BulkCloseKind.TABS), emptySet(), { setOf("a", "b") },
            closeGroup = { error("no group for tabs") }) {
            throw HerdrException("workspace_group_close_required", "x")
        }
        assertEquals(2, result.failures.size)
    }

    @Test fun disappearedTargetIsConsideredComplete() = runBlocking {
        val result = runBulkClose(state.bulkClosePlan(BulkCloseKind.TABS), emptySet(), { setOf("a", "b") }) {
            throw HerdrException(if (it == "a") "tab_not_found" else "workspace_not_found", "already gone")
        }
        assertEquals(2, result.completed)
        assertTrue(result.failures.isEmpty())
    }

    @Test fun rpcTimeoutIsReportedAndOtherTargetsStillClose() = runBlocking {
        val result = runBulkClose(state.bulkClosePlan(BulkCloseKind.TABS), emptySet(), { setOf("a", "b") }) {
            if (it == "a") withTimeout(1) { delay(100) }
        }
        assertEquals(1, result.completed)
        assertEquals("a", result.failures.single().id)
        assertTrue(result.failures.single().reason.contains("タイムアウト"))
    }

    @Test fun cancellationStopsBeforeSendingFurtherCloses() = runBlocking {
        val called = mutableListOf<String>()
        try {
            runBulkClose(state.bulkClosePlan(BulkCloseKind.TABS), emptySet(), { setOf("a", "b") }) {
                called += it
                throw CancellationException("transport changed")
            }
            fail("must cancel")
        } catch (_: CancellationException) { assertEquals(listOf("a"), called) }
    }

    @Test fun confirmedBatchTakesOverUndoPendingCloseWithoutDuplicateRpc() = runBlocking {
        val queue = DeferredTabClosures(this, 40)
        var individual = 0
        queue.request(PendingTabClose("a", "a", false, null)) { individual++; true }
        assertTrue(queue.prepareForBulk(setOf("a")).isEmpty())
        delay(80)
        assertEquals(0, individual)
        assertTrue(queue.pending.value.isEmpty())
    }

    @Test fun confirmedBatchWaitsForInFlightIndividualClose() = runBlocking {
        val queue = DeferredTabClosures(this, 1)
        val started = Channel<Unit>(1)
        val finish = CompletableDeferred<Boolean>()
        queue.request(PendingTabClose("a", "a", false, null)) { started.send(Unit); finish.await() }
        withTimeout(1_000) { started.receive() }
        val preparing = async(start = CoroutineStart.UNDISPATCHED) { queue.prepareForBulk(setOf("a")) }
        assertFalse(preparing.isCompleted)
        finish.complete(true)
        assertEquals(setOf("a"), withTimeout(1_000) { preparing.await() })
        queue.cancelAll()
    }

    @Test fun failedInFlightCloseCanBeRetriedByBatch() = runBlocking {
        val queue = DeferredTabClosures(this, 1)
        val started = Channel<Unit>(1)
        val finish = CompletableDeferred<Boolean>()
        queue.request(PendingTabClose("a", "a", false, null)) { started.send(Unit); finish.await() }
        withTimeout(1_000) { started.receive() }
        val preparing = async(start = CoroutineStart.UNDISPATCHED) { queue.prepareForBulk(setOf("a")) }
        finish.complete(false)
        assertTrue(withTimeout(1_000) { preparing.await() }.isEmpty())
    }
}
