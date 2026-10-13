package com.shibaken.androidterminal.ssh

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One pane over Mosh. Presents the datagram session as [input]/[output] streams so [com.shibaken.androidterminal.session.PaneTerminal]
 * can treat it like a PTY channel: EOF on [input] = the attach ended; an IOException = transport failure.
 * Independent of the SSH connection: [survivesTransport].
 */
class MoshChannel private constructor(
    private val handle: Int,
    override val cols: Int,
    override val rows: Int,
    private var firstChunk: ByteArray?,
    private val serverPid: Int?,
    /** Best-effort `kill <pid>` over whatever SSH connection is up. Blocking. */
    private val killServer: (Int) -> Unit,
) : PaneStream {
    private val closed = AtomicBoolean(false)

    override val survivesTransport: Boolean get() = true

    override val input = object : InputStream() {
        private var rest = ByteArray(0)
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val n = read(one, 0, 1)
                if (n < 0) return -1
                if (n > 0) return one[0].toInt() and 0xff
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (pos >= rest.size) {
                    if (closed.get()) return -1
                    val first = firstChunk
                    firstChunk = null
                    val d = first ?: try {
                        MoshBridge.recv(handle, RECV_TIMEOUT_MS)
                    } catch (_: MoshClosedException) {
                        return -1
                    } catch (e: IOException) {
                        if (closed.get()) return -1
                        throw e
                    }
                    if (d.isEmpty()) continue
                    rest = d; pos = 0
                }
                val n = minOf(len, rest.size - pos)
                System.arraycopy(rest, pos, b, off, n)
                pos += n
                return n
            }
        }
    }

    override val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            if (closed.get()) throw IOException("mosh: closed")
            MoshBridge.send(handle, b.copyOfRange(off, off + len))
        }
    }

    override fun changeWindowSize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (!closed.get()) MoshBridge.resize(handle, cols, rows)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        MoshBridge.close(handle)
        serverPid?.let { pid -> runCatching { killServer(pid) }.onFailure { Log.i(TAG, "kill $pid failed: ${it.message}") } }
    }

    companion object {
        private const val TAG = "MoshChannel"
        private const val RECV_TIMEOUT_MS = 500
        const val FIRST_DATA_TIMEOUT_MS = 5_000

        /**
         * Opens the datagram session, applies the initial size and waits for the first screen update
         * (so a silently broken UDP path is noticed). Blocking; throws on any failure (handle closed again).
         */
        fun open(
            info: MoshBootstrap.Info,
            host: String,
            viaTailscale: Boolean,
            cols: Int,
            rows: Int,
            killServer: (Int) -> Unit,
        ): MoshChannel {
            val h = MoshBridge.open(host, info.port, info.key, viaTailscale)
            try {
                MoshBridge.resize(h, cols, rows)
                val first = MoshBridge.recv(h, FIRST_DATA_TIMEOUT_MS)
                if (first.isEmpty()) throw IOException("Mosh: サーバーから応答がありません (UDP がブロックされている可能性)")
                return MoshChannel(h, cols, rows, first, info.pid, killServer)
            } catch (e: Exception) {
                MoshBridge.close(h)
                throw e
            }
        }
    }
}
