package com.shibaken.androidterminal

import com.shibaken.androidterminal.herdr.HerdrRpc
import com.shibaken.androidterminal.herdr.PaneInputQueue
import com.shibaken.androidterminal.herdr.sendPaneInput
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PaneInputTest {
    @Test fun pasteNormalizesAllNewlinesButEnterIsASeparateKeyInTheSameRequest() = runBlocking {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val calls = mutableListOf<Pair<String, JsonObject>>()
            sendPaneInput(HerdrRpc { method, params -> calls += method to params; JsonObject(emptyMap()) },
                "p1", "echo 日本語${newline}echo done", listOf("enter"))
            val (method, params) = calls.single()
            assertEquals("pane.send_input", method)
            assertEquals("p1", params["pane_id"]!!.jsonPrimitive.content)
            assertEquals("echo 日本語\recho done", params["text"]!!.jsonPrimitive.content)
            assertEquals(listOf("enter"), params["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun pasteDoesNotAddEnterAndRawPreservesControlCharactersAndCrLf() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val rpc = HerdrRpc { method, params -> calls += method to params; JsonObject(emptyMap()) }
        sendPaneInput(rpc, "p1", "draft\r\n", emptyList())
        sendPaneInput(rpc, "p1", "\u001b[201~\u0085", emptyList())
        sendPaneInput(rpc, "p1", "\u0003\r\n\u001b[A", emptyList(), raw = true)
        sendPaneInput(rpc, "p1", "", listOf("enter"))
        assertEquals("draft\r", calls[0].second["text"]!!.jsonPrimitive.content)
        assertFalse(calls[0].second.containsKey("keys"))
        assertEquals("[201~", calls[1].second["text"]!!.jsonPrimitive.content)
        assertEquals("pane.send_text", calls[2].first)
        assertEquals("\u0003\r\n\u001b[A", calls[2].second["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("enter"), calls[3].second["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun inputWaitsForEarlierAckWhileOtherPanesCanProceed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PaneInputQueue(scope)
            val gate = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val first = queue.enqueue("p1") { writes += "text"; gate.await(); writes += "ack" }
            val next = queue.enqueue("p1") { writes += "enter" }
            queue.enqueue("p2") { writes += "other" }.await()
            assertEquals(listOf("text", "other"), writes)
            assertFalse(next.isCompleted)
            gate.complete(Unit)
            withTimeout(1_000) { first.await(); next.await() }
            assertEquals(listOf("text", "other", "ack", "enter"), writes)
        } finally { scope.cancel() }
    }

    @Test fun failedInputReportsErrorAndDoesNotPoisonNextInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = PaneInputQueue(scope)
            val gate = CompletableDeferred<Unit>()
            val expected = IOException("rejected")
            val first = queue.enqueue("p1") { gate.await(); throw expected }
            var nextSent = false
            val next = queue.enqueue("p1") { nextSent = true }
            gate.complete(Unit)
            try { first.await(); fail("must report rejection") } catch (actual: IOException) { assertEquals(expected.message, actual.message) }
            next.await()
            assertTrue(nextSent)
        } finally { scope.cancel() }
    }

    @Test fun disposalCancelsQueuedInputBeforeItCanBeSent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = PaneInputQueue(scope)
        val first = queue.enqueue("p1") { awaitCancellation() }
        var sent = false
        val next = queue.enqueue("p1") { sent = true }
        scope.cancel()
        first.join(); next.join()
        assertTrue(next.isCancelled)
        assertFalse(sent)
    }
}
