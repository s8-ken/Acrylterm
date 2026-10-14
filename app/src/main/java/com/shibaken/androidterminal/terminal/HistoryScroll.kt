package com.shibaken.androidterminal.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlin.math.max
import kotlin.math.min

/**
 * Native history view for panes whose remote app has mouse tracking on (herdr): instead of forwarding wheel
 * events (each waits for a remote repaint) the pane's recent output is fetched once and scrolled locally.
 * Scroll convention: positive px = how far into history (0 = the live bottom), as in [TerminalCanvasView].
 */
enum class HistoryPhase { IDLE, FETCHING, ACTIVE }

object HistoryMath {
    const val FETCH_LINES = 4000
    const val FETCH_TIMEOUT_MS = 2000L
    const val FAIL_BACKOFF_MS = 3000L
    /** A snapshot with (almost) no scrollback: the pane keeps its history itself (alt screen / own scrolling). */
    const val SHALLOW_BACKOFF_MS = 30_000L
    /** The live frame may be shifted by at most this many rows while the fetch is in flight. */
    private const val PENDING_SHIFT_ROWS = 1.5f

    /** Scrolling toward older lines (finger moving down, dy < 0) on a mouse-tracking pane starts a fetch. */
    fun shouldBegin(dy: Float, mouseActive: Boolean, hasFetcher: Boolean, nowMs: Long, failUntilMs: Long): Boolean =
        dy < 0f && mouseActive && hasFetcher && nowMs >= failUntilMs

    /** Distance (px, into history) accumulated while the fetch is in flight; never negative. */
    fun addPending(pending: Float, dy: Float): Float = max(0f, pending - dy)

    /** Where the history view starts: the distance already travelled, bounded by the snapshot. */
    fun initialScroll(pending: Float, maxScrollPx: Float): Float = pending.coerceIn(0f, max(0f, maxScrollPx))

    /**
     * The history view is only worth it with at least one screenful above the live screen; otherwise it would dead-end after
     * a few lines (e.g. a TUI with its own scrolling, where herdr holds no scrollback) and wheel forwarding is better.
     */
    fun deepEnough(transcriptRows: Int, liveRows: Int): Boolean = transcriptRows >= max(liveRows, 1)

    /** Whole wheel-up rows to replay to the host for a gesture whose distance [pendingPx] was swallowed while fetching. */
    fun replayRows(pendingPx: Float, rowHeight: Float, maxRows: Int): Int =
        if (rowHeight <= 0f || pendingPx <= 0f) 0 else (pendingPx / rowHeight).toInt().coerceIn(0, maxRows)

    /** Worth entering only if the user is still (at least slightly) into history and there is something to see. */
    fun shouldEnter(pending: Float, maxScrollPx: Float, rowHeight: Float): Boolean = pending >= 1f && maxScrollPx >= rowHeight

    /** Live-frame shift (px, + = content moves up) while fetching, so the gesture still moves something. */
    fun pendingShift(pending: Float, rowHeight: Float): Float = -min(pending, rowHeight * PENDING_SHIFT_ROWS)

    /** Back at the live bottom once the gesture is over (finger up, no fling running). */
    fun shouldExit(scrollPx: Float, flinging: Boolean, touching: Boolean): Boolean =
        scrollPx <= 0.5f && !flinging && !touching

    /** Last row (0 = top) of the live screen that has text, or -1 when blank. */
    fun lastNonBlankRow(rowIsBlank: (Int) -> Boolean, rows: Int): Int {
        for (r in rows - 1 downTo 0) if (!rowIsBlank(r)) return r
        return -1
    }

    /**
     * Text to feed a fresh emulator so that the snapshot's last line lands on live row [lastLiveRow] of a
     * [liveRows]-row screen (herdr trims trailing blank lines), i.e. the bottom of the history matches the live screen.
     */
    fun buildFeed(snapshot: String, liveRows: Int, lastLiveRow: Int): String {
        val body = snapshot.replace("\r\n", "\n").trimEnd('\n', '\r').replace("\n", "\r\n")
        val pad = if (lastLiveRow < 0) 0 else max(0, liveRows - 1 - lastLiveRow)
        val sb = StringBuilder(body.length + pad * 2 + 16)
        sb.append("\u001b[?25l") // hide the cursor: the live one is elsewhere
        sb.append(body)
        repeat(pad) { sb.append("\r\n") }
        return sb.toString()
    }

    /** Offscreen emulator holding the snapshot. Safe to call from a background thread. */
    fun buildEmulator(snapshot: String, cols: Int, rows: Int, cellW: Int, cellH: Int, lastLiveRow: Int): TerminalEmulator {
        val emu = TerminalEmulator(NoOutput, cols, rows, cellW, cellH, FETCH_LINES + rows + 200, NoClient)
        val bytes = buildFeed(snapshot, rows, lastLiveRow).toByteArray(Charsets.UTF_8)
        emu.append(bytes, bytes.size)
        emu.clearScrollCounter()
        return emu
    }

    internal object NoOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    internal object NoClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

/**
 * Freshness and rate-limit policy of the background history prefetch (pure; the view owns the actual cache).
 * A snapshot is fresh only while no output arrived since its fetch started, because only then it aligns exactly with
 * the live screen; a stale snapshot is still usable via content stitching ([HistoryStitch]). Refreshes after a quiet period
 * are rate-limited (5 s), and a continuously streaming pane refreshes at most every 8 s, so there is never a refetch loop.
 */
class HistoryCachePolicy(
    private val quietMs: Long = QUIET_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val stuckMs: Long = STUCK_MS,
    private val streamIntervalMs: Long = STREAM_INTERVAL_MS,
) {
    var outputSeq = 0L
        private set
    private var lastOutputMs = 0L
    private var firstOutputMs = -1L
    private var cacheSeq = -1L
    private var inFlight = false
    private var lastStartMs = Long.MIN_VALUE / 2

    fun onOutput(nowMs: Long) { outputSeq++; lastOutputMs = nowMs; if (firstOutputMs < 0) firstOutputMs = nowMs }

    /** A snapshot exists and nothing was output since its fetch started. */
    val isFresh: Boolean get() = cacheSeq >= 0 && cacheSeq == outputSeq

    fun shouldPrefetch(nowMs: Long, visible: Boolean, inHistoryOrFetching: Boolean): Boolean {
        if (outputSeq == 0L || !visible || inHistoryOrFetching || isFresh) return false
        if (inFlight && nowMs - lastStartMs < stuckMs) return false
        val sinceStart = nowMs - lastStartMs
        if (nowMs - lastOutputMs >= quietMs) return sinceStart >= minIntervalMs
        if (nowMs - firstOutputMs < SETTLE_MS) return false // initial output still settling
        return sinceStart >= streamIntervalMs // still streaming: the (stale) snapshot is stitched, so refresh only slowly
    }

    /** Starts a prefetch; returns the token (output counter at start) to hand back to [complete]. */
    fun begin(nowMs: Long): Long { inFlight = true; lastStartMs = nowMs; return outputSeq }

    /** Returns true when the result is usable (success and still aligned with the live screen). */
    fun complete(token: Long, success: Boolean): Boolean {
        inFlight = false
        if (success && token == outputSeq) { cacheSeq = token; return true }
        return false
    }

    /** A snapshot built on demand at output counter [token] becomes the cache if still aligned. */
    fun adopt(token: Long): Boolean {
        if (token != outputSeq) return false
        cacheSeq = token
        return true
    }

    /** Drops the cache and any in-flight result (grid resize, session change, detach); output counter is kept. */
    fun invalidate() { cacheSeq = -1; inFlight = false }

    companion object {
        const val QUIET_MS = 1500L
        const val MIN_INTERVAL_MS = 5000L
        const val STUCK_MS = 15000L
        const val STREAM_INTERVAL_MS = 8000L
        const val SETTLE_MS = 3000L
    }
}
