package com.shibaken.androidterminal.ssh

import com.shibaken.androidterminal.BuildConfig
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/** The Mosh server ended (its program exited) or the handle was closed. */
class MoshClosedException : IOException("mosh: closed")

/**
 * Reflection wrapper over `tsbridge.Tsbridge.mosh*` (see TailscaleManager for why reflection).
 * [available] is false without tsbridge.aar or with an old one lacking the mosh methods.
 */
object MoshBridge {
    private val bridge: Class<*>? by lazy {
        if (!BuildConfig.HAS_TSBRIDGE) return@lazy null
        try {
            Class.forName("tsbridge.Tsbridge").takeIf { c -> c.methods.any { it.name == "moshOpen" } && c.methods.any { it.name == "moshRecv" } }
        } catch (_: Throwable) {
            null
        }
    }

    val available: Boolean get() = bridge != null

    private fun call(name: String, vararg args: Any?): Any? {
        val cls = bridge ?: throw IOException("Mosh is not available in this build")
        val m = cls.methods.first { it.name == name && it.parameterTypes.size == args.size }
        try {
            return m.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            val t = e.targetException
            if (t?.message?.contains("mosh: closed") == true) throw MoshClosedException()
            throw IOException(t?.message ?: "tsbridge.$name failed", t)
        }
    }

    fun open(host: String, port: Int, key: String, viaTailscale: Boolean): Int =
        (call("moshOpen", host, port, key, viaTailscale) as Number).toInt()

    fun send(h: Int, data: ByteArray) { call("moshSend", h, data) }

    fun resize(h: Int, cols: Int, rows: Int) { call("moshResize", h, cols, rows) }

    /** Empty array on timeout. Throws [MoshClosedException] when the server side ended. */
    fun recv(h: Int, timeoutMs: Int): ByteArray = (call("moshRecv", h, timeoutMs) as? ByteArray) ?: ByteArray(0)

    fun close(h: Int) { runCatching { call("moshClose", h) } }
}
