package com.shibaken.androidterminal.herdr

import com.termux.terminal.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Preserve invocation order across suspending RPCs, independently for each pane. */
internal class PaneInputQueue(private val scope: CoroutineScope) {
    private val tails = HashMap<String, Deferred<Unit>>()

    fun enqueue(paneId: String, send: suspend () -> Unit): Deferred<Unit> = synchronized(tails) {
        val previous = tails[paneId]
        val next = scope.async(start = CoroutineStart.LAZY) {
            previous?.join() // A failed send must not poison later input.
            scope.coroutineContext.ensureActive()
            send()
        }
        tails[paneId] = next
        next.invokeOnCompletion { synchronized(tails) { if (tails[paneId] === next) tails.remove(paneId) } }
        next.start()
        next
    }
}

/** A submission is one RPC: the server appends keys outside its paste wrapper. */
internal suspend fun sendPaneInput(rpc: HerdrRpc, paneId: String, text: String?, keys: List<String>, raw: Boolean = false) {
    rpc.call(if (raw) "pane.send_text" else "pane.send_input", buildJsonObject {
        put("pane_id", paneId)
        if (raw || !text.isNullOrEmpty()) put("text", if (raw) text.orEmpty() else TerminalEmulator.normalizePasteText(text.orEmpty()))
        if (!raw && keys.isNotEmpty()) put("keys", JsonArray(keys.map { JsonPrimitive(it) }))
    })
}
