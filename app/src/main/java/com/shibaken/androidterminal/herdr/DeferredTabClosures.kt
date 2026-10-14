package com.shibaken.androidterminal.herdr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Only presentation is hidden during Undo. The remote PTY and raw snapshot stay alive. */
data class PendingTabClose(val tabId: String, val title: String, val restoreFocus: Boolean, val replacementId: String?, val undoable: Boolean = true)

internal class DeferredTabClosures(private val scope: CoroutineScope, private val timeoutMs: Long = 5_000) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val _pending = MutableStateFlow<List<PendingTabClose>>(emptyList())
    val pending = _pending.asStateFlow()

    fun request(close: PendingTabClose, commit: suspend () -> Boolean): Boolean = synchronized(lock) {
        if (_pending.value.any { it.tabId == close.tabId }) return false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMs)
            val claimed = synchronized(lock) {
                if (jobs[close.tabId] !== coroutineContext[Job]) false else {
                    _pending.value = _pending.value.map { if (it.tabId == close.tabId) it.copy(undoable = false) else it }
                    true
                }
            }
            if (!claimed) return@launch
            // Keep successful closes hidden until the next server snapshot removes the actual tab.
            val committed = try { commit() } catch (error: Throwable) {
                synchronized(lock) { if (jobs[close.tabId] === coroutineContext[Job]) remove(close.tabId) }
                throw error
            }
            if (!committed) synchronized(lock) {
                if (jobs[close.tabId] === coroutineContext[Job]) remove(close.tabId)
            }
        }
        jobs[close.tabId] = job
        _pending.value += close
        job.start()
        true
    }

    fun undo(tabId: String): PendingTabClose? = synchronized(lock) {
        val close = _pending.value.firstOrNull { it.tabId == tabId && it.undoable } ?: return null
        jobs.remove(tabId)?.cancel()
        _pending.value = _pending.value.filterNot { it.tabId == tabId }
        close
    }

    fun reconcile(existing: Set<String>) = synchronized(lock) {
        _pending.value.filter { it.tabId !in existing }.forEach { remove(it.tabId) }
    }

    /** Transfer Undo-pending closes to a confirmed batch; wait for claimed RPCs before deduplicating. */
    suspend fun prepareForBulk(tabIds: Set<String>): Set<String> {
        val inFlight = synchronized(lock) {
            val matching = _pending.value.filter { it.tabId in tabIds }
            matching.filter { it.undoable }.forEach { remove(it.tabId) }
            matching.filterNot { it.undoable }.mapNotNull { jobs[it.tabId] }
        }
        inFlight.forEach { it.join() }
        return synchronized(lock) { _pending.value.filter { it.tabId in tabIds && !it.undoable }.mapTo(HashSet()) { it.tabId } }
    }

    fun cancelAll() = synchronized(lock) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _pending.value = emptyList()
    }

    private fun remove(tabId: String) {
        jobs.remove(tabId)?.cancel()
        _pending.value = _pending.value.filterNot { it.tabId == tabId }
    }
}

/** UI projection only; SshTerminalSession must continue observing the unfiltered controller state. */
fun HerdrState.withPendingTabsHidden(pending: List<PendingTabClose>): HerdrState {
    val hidden = pending.mapTo(HashSet()) { it.tabId }
    if (hidden.isEmpty()) return this
    val visibleTabs = tabs.filterNot { it.tabId in hidden }
    val visiblePanes = panes.filterNot { it.tabId in hidden }
    val focused = visibleTabs.firstOrNull { it.tabId == focusedTabId }
        ?: pending.lastOrNull { it.tabId == focusedTabId }?.replacementId?.let { id -> visibleTabs.firstOrNull { it.tabId == id } }
        ?: visibleTabs.firstOrNull { it.workspaceId == focusedWorkspaceId } ?: visibleTabs.firstOrNull()
    val pane = visiblePanes.firstOrNull { it.paneId == focusedPaneId && it.tabId == focused?.tabId }
        ?: visiblePanes.firstOrNull { it.paneId == layouts[focused?.tabId]?.focusedPaneId }
        ?: visiblePanes.firstOrNull { it.tabId == focused?.tabId }
    return copy(tabs = visibleTabs, panes = visiblePanes, layouts = layouts.filterKeys { it !in hidden },
        focusedTabId = focused?.tabId, focusedWorkspaceId = focused?.workspaceId ?: focusedWorkspaceId, focusedPaneId = pane?.paneId)
}
