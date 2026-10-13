package com.shibaken.androidterminal.terminal

import com.termux.terminal.TerminalEmulator

/** What [TerminalCanvasView] needs from whatever feeds it: an emulator plus input/resize plumbing. */
interface TerminalBackend {
    val emulator: TerminalEmulator

    /** Called on the main thread after the emulator content changed. Set by the view. */
    var onScreenUpdate: (() -> Unit)?

    /** Sends UTF-8 text to the remote side (main thread; no-op while not connected). */
    fun sendText(text: String)

    fun pasteFromClipboard()

    /** Called by the view when its size in cells changes. Main thread. */
    fun resize(newCols: Int, newRows: Int, cellWidth: Int, cellHeight: Int)
}
