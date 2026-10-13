package com.shibaken.androidterminal.herdr

import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.ssh.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * herdr socket API over SSH (newline-delimited JSON on herdr.sock).
 *
 * herdr answers exactly one request per connection (except events.subscribe, which streams),
 * so every call opens a fresh direct-streamlocal channel; channels are multiplexed over the
 * one SSH connection and are cheap.
 */
class HerdrClient(private val conn: SshConnection, val socketPath: String) {
    private val seq = AtomicLong()

    private fun request(method: String, params: JsonObject): Pair<String, String> {
        val id = "a${seq.incrementAndGet()}"
        val line = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        return id to line
    }

    /** Calls [method] and returns its `result` object. Throws [HerdrException] on API errors. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 10_000): JsonObject =
        withTimeout(timeoutMs) {
            // runInterruptible: sshj's channel streams block in Object.wait(), so the timeout's
            // interrupt actually unblocks them.
            runInterruptible(Dispatchers.IO) {
                val (_, line) = request(method, params)
                val ch = try {
                    conn.openStreamLocal(socketPath)
                } catch (e: Exception) {
                    throw HerdrException("unreachable", "ソケットに接続できません(サーバーが起動していない可能性があります): ${e.message}")
                }
                try {
                    val out = ch.outputStream
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(ch.inputStream, Charsets.UTF_8))
                    val reply = reader.readLine() ?: throw IOException("応答がありませんでした")
                    parseResult(AppJson.parseToJsonElement(reply).jsonObject)
                } finally {
                    runCatching { ch.close() }
                }
            }
        }

    private fun parseResult(obj: JsonObject): JsonObject {
        obj["error"]?.let { err ->
            val e = err.jsonObject
            throw HerdrException(
                e["code"]?.jsonPrimitive?.contentOrNull ?: "error",
                e["message"]?.jsonPrimitive?.contentOrNull ?: "エラー",
            )
        }
        return obj["result"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /**
     * events.subscribe: emits each event line (`{"event": ..., "data": {...}}`) after the
     * `subscription_started` acknowledgement. The flow fails with [HerdrException] on an
     * error line (e.g. `events_lost`) and completes when herdr closes the stream.
     */
    fun subscribe(subscriptions: List<JsonObject>): Flow<JsonObject> = callbackFlow {
        val (_, line) = request("events.subscribe", buildJsonObject {
            put("subscriptions", kotlinx.serialization.json.JsonArray(subscriptions))
        })
        val ch = withContext(Dispatchers.IO) { conn.openStreamLocal(socketPath) }
        val reader = thread(name = "herdr-events", isDaemon = true) {
            try {
                ch.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
                ch.outputStream.flush()
                val r = BufferedReader(InputStreamReader(ch.inputStream, Charsets.UTF_8))
                val first = r.readLine() ?: throw IOException("subscription closed")
                parseResult(AppJson.parseToJsonElement(first).jsonObject) // throws on error
                while (true) {
                    val l = r.readLine() ?: break
                    if (l.isBlank()) continue
                    val obj = AppJson.parseToJsonElement(l).jsonObject
                    if (obj.containsKey("error")) parseResult(obj)
                    trySend(obj)
                }
                close()
            } catch (e: Throwable) {
                close(e)
            }
        }
        awaitClose {
            reader.interrupt()
            // sshj's close() waits for CHANNEL_CLOSE; never block the caller's dispatcher on it.
            thread(name = "herdr-close", isDaemon = true) { runCatching { ch.close() } }
        }
    }

    companion object {
        fun eventName(ev: JsonObject): String = ev["event"]?.jsonPrimitive?.contentOrNull ?: ""

        /** Canonical form of an event name: lower case, '.' -> '_' (0.9 mixes `pane_focused` and `pane.agent_status_changed`). */
        fun normalizeEvent(name: String): String = name.trim().lowercase().replace('.', '_')

        /** True when [a] and [b] name the same event regardless of '.'/'_' spelling. */
        fun sameEvent(a: String, b: String): Boolean = normalizeEvent(a) == normalizeEvent(b)

        fun eventData(ev: JsonObject): JsonElement? = ev["data"]
    }
}
