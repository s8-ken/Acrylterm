package com.shibaken.androidterminal.herdr

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One entry of `worktree.list`. */
data class WorktreeEntry(
    val branch: String?,
    val isBare: Boolean,
    val isDetached: Boolean,
    val isLinked: Boolean,
    val isPrunable: Boolean,
    val label: String,
    val openWorkspaceId: String?,
    val path: String,
) {
    val title: String get() = branch?.takeIf { it.isNotBlank() } ?: label.ifBlank { dirName }
    val dirName: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** Finds the first array of objects having `path` (depth-first), so the wrapper key does not matter. */
internal fun parseWorktrees(result: JsonObject): List<WorktreeEntry> {
    fun find(e: JsonElement): JsonArray? = when (e) {
        is JsonArray -> if (e.isNotEmpty() && e.all { it is JsonObject && "path" in it }) e
            else e.firstNotNullOfOrNull { find(it) }
        is JsonObject -> e.values.firstNotNullOfOrNull { find(it) }
        else -> null
    }
    val array = find(result) ?: return emptyList()
    return array.map { it.jsonObject }.mapNotNull { o ->
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
        val path = str("path") ?: return@mapNotNull null
        WorktreeEntry(str("branch"), bool("is_bare"), bool("is_detached"), bool("is_linked_worktree"), bool("is_prunable"),
            str("label").orEmpty(), str("open_workspace_id"), path)
    }
}

// trust_repository is only ever sent when the user has explicitly confirmed it.
internal fun worktreeListParams(workspaceId: String, trust: Boolean = false) = buildJsonObject {
    put("workspace_id", workspaceId)
    if (trust) put("trust_repository", true)
}

internal fun worktreeCreateParams(sourceWorkspaceId: String, branch: String, base: String?, trust: Boolean = false) = buildJsonObject {
    put("workspace_id", sourceWorkspaceId)
    put("branch", branch.trim())
    base?.trim()?.takeIf { it.isNotEmpty() }?.let { put("base", it) }
    put("focus", true)
    if (trust) put("trust_repository", true)
}

internal fun worktreeOpenParams(sourceWorkspaceId: String, path: String, trust: Boolean = false) = buildJsonObject {
    put("workspace_id", sourceWorkspaceId)
    put("path", path)
    put("focus", true)
    if (trust) put("trust_repository", true)
}

internal fun worktreeRemoveParams(workspaceId: String, force: Boolean) = buildJsonObject {
    put("workspace_id", workspaceId)
    if (force) put("force", true)
}

internal fun workspaceCloseParams(workspaceId: String, group: Boolean) = buildJsonObject {
    put("workspace_id", workspaceId)
    if (group) put("close_group", true)
}

internal fun isTrustError(error: Exception) =
    error is HerdrException && ("trust" in error.code.lowercase() || "trust" in error.message.orEmpty().lowercase())

internal fun isGroupCloseRequired(error: Exception) = error is HerdrException && error.code == "workspace_group_close_required"

/** What to retry once the user confirms trusting the repository. */
sealed interface PendingTrust {
    data class Create(val sourceWorkspaceId: String, val branch: String, val base: String?) : PendingTrust
    data class Open(val sourceWorkspaceId: String, val path: String) : PendingTrust
}

internal enum class WorktreeOutcome { DONE, NEEDS_TRUST }

internal suspend fun requestWorktreeCreate(sourceWorkspaceId: String, branch: String, base: String?, trust: Boolean, rpc: HerdrRpc): WorktreeOutcome =
    try { rpc.call("worktree.create", worktreeCreateParams(sourceWorkspaceId, branch, base, trust)); WorktreeOutcome.DONE }
    catch (e: Exception) { if (!trust && isTrustError(e)) WorktreeOutcome.NEEDS_TRUST else throw e }

internal suspend fun requestWorktreeOpen(sourceWorkspaceId: String, path: String, trust: Boolean, rpc: HerdrRpc): WorktreeOutcome =
    try { rpc.call("worktree.open", worktreeOpenParams(sourceWorkspaceId, path, trust)); WorktreeOutcome.DONE }
    catch (e: Exception) { if (!trust && isTrustError(e)) WorktreeOutcome.NEEDS_TRUST else throw e }

/** Returns true when herdr wants the linked worktree workspaces closed together (caller must ask the user). */
internal suspend fun requestWorkspaceClose(workspaceId: String, group: Boolean, rpc: HerdrRpc): Boolean =
    try { rpc.call("workspace.close", workspaceCloseParams(workspaceId, group)); false }
    catch (e: Exception) { if (!group && isGroupCloseRequired(e)) true else throw e }

// --- agents --------------------------------------------------------------------------

val AGENT_KINDS = listOf("claude" to "Claude", "codex" to "Codex", "gemini" to "Gemini")

private val AGENT_NAME = Regex("[a-z][a-z0-9_-]{0,31}")

/** `kind` sanitized to herdr's agent-name rules, then `-2`, `-3`… until it is unused. */
fun agentNameFor(kind: String, existing: Set<String>): String {
    var base = kind.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trimStart { it !in 'a'..'z' }.take(28)
    if (base.isEmpty()) base = "agent"
    val taken = existing.mapTo(HashSet()) { it.lowercase() }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n".also { check(AGENT_NAME.matches(it)) }
}

internal fun agentStartParams(name: String, kind: String, paneId: String) = buildJsonObject {
    put("name", name)
    put("kind", kind)
    put("pane_id", paneId)
    put("timeout_ms", 30_000)
}

private fun isAgentNameConflict(e: Exception) = e is HerdrException && e.code == "agent_name_taken"

/** A freshly created tab's shell may not have reached its prompt yet. */
private fun isPaneNotReady(e: Exception) = e is HerdrException && (e.code == "agent_pane_busy" || e.code == "agent_pane_unavailable")

/** Starts [kind] in [paneId]. `agent_not_ready` is non-fatal (agent is up, waiting for input). Returns the name used. */
internal suspend fun startAgent(kind: String, paneId: String, existing: () -> Set<String>, rpc: HerdrRpc,
    retryDelayMs: Long = 500): String {
    val tried = HashSet<String>()
    var name = agentNameFor(kind, existing())
    var paneRetries = 0
    while (true) {
        try {
            rpc.call("agent.start", agentStartParams(name, kind, paneId))
            return name
        } catch (e: Exception) {
            when {
                e is HerdrException && e.code == "agent_not_ready" -> return name
                isAgentNameConflict(e) && tried.size < 8 -> { tried += name; name = agentNameFor(kind, existing() + tried) }
                isPaneNotReady(e) && paneRetries < 10 -> { paneRetries++; delay(retryDelayMs) }
                else -> throw e
            }
        }
    }
}

/** `root_pane.pane_id` of a tab.create / workspace.create result. */
internal fun rootPaneId(result: JsonObject): String? =
    (result["root_pane"] as? JsonObject)?.get("pane_id")?.jsonPrimitive?.contentOrNull

internal class NewTabResult(val outcome: TabCreationOutcome, val rootPaneId: String?)

/** Like [requestNewTab] but also returns `root_pane.pane_id` of the created tab. */
internal suspend fun requestNewTabWithPane(workspaceId: String?, noKnownWorkspaces: Boolean, rpc: HerdrRpc): NewTabResult {
    if (workspaceId == null && noKnownWorkspaces) return NewTabResult(TabCreationOutcome.NEEDS_WORKSPACE, null)
    return try {
        val r = rpc.call("tab.create", buildJsonObject {
            workspaceId?.let { put("workspace_id", it) }
            put("focus", true)
        })
        NewTabResult(TabCreationOutcome.CREATED, rootPaneId(r))
    } catch (error: Exception) {
        if (isNoActiveWorkspaceError(error)) NewTabResult(TabCreationOutcome.NEEDS_WORKSPACE, null) else throw error
    }
}
