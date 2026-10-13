package com.shibaken.androidterminal.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream

/** An SSH session channel with an allocated PTY (shell or exec). */
class PtyChannel(
    private val session: Session,
    override val input: InputStream,
    override val output: OutputStream,
    /** Size the PTY was allocated with. */
    override val cols: Int,
    override val rows: Int,
    private val windowChange: (cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit,
) : PaneStream {
    /** Blocking (network write). */
    override fun changeWindowSize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) = windowChange(cols, rows, widthPx, heightPx)

    /** Blocking (network write). Idempotent. */
    override fun close() {
        runCatching { session.close() }
    }
}
