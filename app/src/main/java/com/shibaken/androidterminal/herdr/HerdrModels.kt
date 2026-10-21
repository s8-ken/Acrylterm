package com.shibaken.androidterminal.herdr

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** "idle" | "working" | "blocked" | "done" | "unknown" */
object AgentStatus {
    const val IDLE = "idle"
    const val WORKING = "working"
    const val BLOCKED = "blocked"
    const val DONE = "done"
    const val UNKNOWN = "unknown"

    fun label(s: String?): String = when (s) {
        IDLE -> App.str(R.string.core_agent_idle)
        WORKING -> App.str(R.string.core_agent_working)
        BLOCKED -> App.str(R.string.core_agent_blocked)
        DONE -> App.str(R.string.core_agent_done)
        else -> ""
    }
}

@Serializable
data class WorkspaceWorktree(
    @SerialName("repo_key") val repoKey: String = "",
    @SerialName("repo_name") val repoName: String = "",
    @SerialName("repo_root") val repoRoot: String = "",
    @SerialName("checkout_path") val checkoutPath: String = "",
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean = false,
)

@Serializable
data class WorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
    @SerialName("active_tab_id") val activeTabId: String? = null,
    @SerialName("agent_status") val agentStatus: String = AgentStatus.UNKNOWN,
    val worktree: WorkspaceWorktree? = null,
) {
    val title: String get() = label.ifBlank { "Workspace $number" }
}

@Serializable
data class TabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("agent_status") val agentStatus: String = AgentStatus.UNKNOWN,
) {
    val title: String get() = label.ifBlank { "Tab $number" }
}

@Serializable
data class PaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val focused: Boolean = false,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: String = AgentStatus.UNKNOWN,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    /** Id used by `herdr terminal attach`; absent on very old servers. */
    @SerialName("terminal_id") val terminalId: String? = null,
    val scroll: PaneScroll? = null,
) {
    val agentName: String? get() = displayAgent ?: agent

    val title2: String
        get() = label?.takeIf { it.isNotBlank() }
            ?: agentName?.takeIf { it.isNotBlank() }
            ?: terminalTitle?.takeIf { it.isNotBlank() }
            ?: (foregroundCwd ?: cwd)?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: paneId

    val statusLabel: String
        get() = stateLabels[agentStatus] ?: AgentStatus.label(agentStatus)
}

@Serializable
data class PaneScroll(
    @SerialName("offset_from_bottom") val offsetFromBottom: Int = 0,
    @SerialName("max_offset_from_bottom") val maxOffsetFromBottom: Int = 0,
    @SerialName("viewport_rows") val viewportRows: Int = 0,
)

/** Rectangle in terminal cells, as reported by herdr's layout API. */
@Serializable
data class CellRect(val x: Int = 0, val y: Int = 0, val width: Int = 0, val height: Int = 0)

/** Rectangle as fractions (0..1) of a parent rectangle. */
data class FractionRect(val x: Float, val y: Float, val width: Float, val height: Float)

@Serializable
data class LayoutPane(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean = false,
    val rect: CellRect = CellRect(),
)

@Serializable
data class LayoutSplit(
    val id: JsonElement? = null,
    /** "right" (children side by side) or "down" (stacked). */
    val direction: String = "",
    val ratio: Float = 0.5f,
    val rect: CellRect = CellRect(),
)

/** Geometry of one tab (`layouts[]` entry of session.snapshot / `pane.layout`). */
@Serializable
data class TabLayout(
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val zoomed: Boolean = false,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val area: CellRect = CellRect(),
    val panes: List<LayoutPane> = emptyList(),
    val splits: List<LayoutSplit> = emptyList(),
) {
    /**
     * Each pane's rect normalised to 0..1 fractions of [area] (server-side chrome offsets such as
     * a sidebar are removed). Degenerate areas yield a full-size rect per pane.
     */
    fun normalizedPaneRects(): Map<String, FractionRect> = panes.associate { it.paneId to normalize(it.rect) }

    fun normalize(r: CellRect): FractionRect {
        if (area.width <= 0 || area.height <= 0) return FractionRect(0f, 0f, 1f, 1f)
        return FractionRect(
            (r.x - area.x).toFloat() / area.width,
            (r.y - area.y).toFloat() / area.height,
            r.width.toFloat() / area.width,
            r.height.toFloat() / area.height,
        )
    }
}

@Serializable
data class SessionSnapshot(
    val version: String = "",
    val protocol: Int = 0,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
    val layouts: List<TabLayout> = emptyList(),
) {
    /** 0.9 reports focus at the top level; 0.8 only has per-item `focused` flags. */
    val resolvedFocusedWorkspaceId: String?
        get() = focusedWorkspaceId ?: workspaces.firstOrNull { it.focused }?.workspaceId

    val resolvedFocusedTabId: String?
        get() {
            focusedTabId?.let { return it }
            val ws = resolvedFocusedWorkspaceId
            return (tabs.firstOrNull { it.focused && it.workspaceId == ws } ?: tabs.firstOrNull { it.focused })?.tabId
        }

    val resolvedFocusedPaneId: String?
        get() {
            focusedPaneId?.let { return it }
            val tab = resolvedFocusedTabId
            return (panes.firstOrNull { it.focused && it.tabId == tab } ?: panes.firstOrNull { it.focused })?.paneId
        }
}

/** Payload of the dotted "pane.agent_status_changed" subscription event. */
@Serializable
data class AgentStatusEvent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("agent_status") val agentStatus: String = AgentStatus.UNKNOWN,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val title: String? = null,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
)

class HerdrException(val code: String, message: String) : Exception(message)
