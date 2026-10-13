package com.shibaken.androidterminal.ssh

import java.io.InputStream
import java.io.OutputStream

/** Byte transport behind one pane terminal: an SSH PTY channel ([PtyChannel]) or a Mosh session ([MoshChannel]). */
interface PaneStream {
    val input: InputStream
    val output: OutputStream
    /** Size the remote side was told about when the stream was opened. */
    val cols: Int
    val rows: Int

    /** True when the stream keeps working across SSH reconnects (Mosh). */
    val survivesTransport: Boolean get() = false

    /** Blocking (network write). */
    fun changeWindowSize(cols: Int, rows: Int, widthPx: Int, heightPx: Int)

    /** Blocking. Idempotent. */
    fun close()
}
