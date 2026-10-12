package com.shibaken.androidterminal.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** "idle" | "working" | "blocked" | "done" | "unknown" */
object AgentStatus {
    const val IDLE = "idle"
    const val WORKING = "working"
    const val BLOCKED = "blocked"
    const val DONE = "done"
    const val UNKNOWN = "unknown"

    fun label(s: String?): String = when (s) {
        IDLE -> "待機"
        WORKING -> "作業中"
        BLOCKED -> "入力待ち"
        DONE -> "完了"
        else -> ""
    }
}

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
data class SessionSnapshot(
    val version: String = "",
    val protocol: Int = 0,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<WorkspaceInfo> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<PaneInfo> = emptyList(),
)

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
