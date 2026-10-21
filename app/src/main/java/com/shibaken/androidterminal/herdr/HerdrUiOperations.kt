package com.shibaken.androidterminal.herdr

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun interface HerdrRpc {
    suspend fun call(method: String, params: JsonObject): JsonObject
}

internal fun isNoActiveWorkspaceError(error: Exception): Boolean {
    if (error !is HerdrException) return false
    val code = error.code.lowercase()
    val message = error.message.orEmpty().lowercase().replace('_', ' ').replace('-', ' ')
    return code == "no_active_workspace" || code == "workspace_not_found" ||
        "no active workspace" in message || "no workspace is active" in message
}

internal enum class TabCreationOutcome { CREATED, NEEDS_WORKSPACE }

internal suspend fun requestNewTab(workspaceId: String?, noKnownWorkspaces: Boolean, rpc: HerdrRpc): TabCreationOutcome {
    if (workspaceId == null && noKnownWorkspaces) return TabCreationOutcome.NEEDS_WORKSPACE
    return requestNewTabWithPane(workspaceId, noKnownWorkspaces, rpc).outcome
}

/** workspace.create already creates and focuses its initial tab and root pane on supported servers. */
internal suspend fun createWorkspaceWithInitialTab(label: String?, rpc: HerdrRpc) = rpc.call("workspace.create", buildJsonObject {
    put("focus", true)
    if (!label.isNullOrBlank()) put("label", label.trim())
})

enum class BulkCloseKind { TABS, WORKSPACES }

data class BulkClosePlan(val kind: BulkCloseKind, val ids: List<String>, val workspaceCount: Int, val tabCount: Int, val paneCount: Int)
/** [message] is literal text; [messageRes] (when set) is resolved lazily so this stays pure in JVM tests. */
data class BulkCloseFailure(val id: String, val message: String = "", @StringRes val messageRes: Int = 0) {
    val reason: String get() = if (messageRes != 0) App.str(messageRes) else message
}
data class BulkCloseResult(val plan: BulkClosePlan, val completed: Int, val failures: List<BulkCloseFailure>)

/** A confirmation captures IDs across every workspace, independent of the overview's display filter. */
fun HerdrState.bulkClosePlan(kind: BulkCloseKind) = BulkClosePlan(kind,
    (if (kind == BulkCloseKind.TABS) tabs.map { it.tabId } else workspaces.map { it.workspaceId }).distinct(),
    workspaces.size, tabs.size, panes.size)

/** [closeGroup] re-closes a workspace together with its linked worktree workspaces (bulk close targets all of them anyway). */
internal suspend fun runBulkClose(plan: BulkClosePlan, alreadyClosingTabs: Set<String>, existingIds: () -> Set<String>,
    closeGroup: (suspend (String) -> Unit)? = null, close: suspend (String) -> Unit): BulkCloseResult {
    var completed = 0
    val failures = mutableListOf<BulkCloseFailure>()
    for (id in plan.ids.distinct()) {
        currentCoroutineContext().ensureActive()
        if (id !in existingIds() || (plan.kind == BulkCloseKind.TABS && id in alreadyClosingTabs)) {
            completed++
            continue
        }
        try {
            try {
                close(id)
            } catch (error: Exception) {
                if (plan.kind == BulkCloseKind.WORKSPACES && closeGroup != null && isGroupCloseRequired(error)) closeGroup(id) else throw error
            }
            completed++
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failures += BulkCloseFailure(id, messageRes = R.string.core_bulk_timeout)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val gone = error is HerdrException && (error.code == "workspace_not_found" ||
                (plan.kind == BulkCloseKind.TABS && error.code == "tab_not_found"))
            if (gone) completed++ else failures += error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120).let { line ->
                if (error is HerdrException && error.code == "workspace_group_close_required")
                    BulkCloseFailure(id, messageRes = R.string.core_bulk_group_confirm)
                else if (line.isBlank()) BulkCloseFailure(id, messageRes = R.string.core_bulk_close_failed)
                else BulkCloseFailure(id, line)
            }
        }
    }
    return BulkCloseResult(plan, completed, failures)
}
