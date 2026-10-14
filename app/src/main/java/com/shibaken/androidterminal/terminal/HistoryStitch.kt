package com.shibaken.androidterminal.terminal

import com.termux.terminal.AnsiRows
import com.termux.terminal.TerminalEmulator
import kotlin.math.max
import kotlin.math.min

/** A fetched history snapshot: ANSI lines plus their plain text (trailing blanks trimmed) used for alignment. */
class HistorySnapshot(val raw: List<String>, val plain: List<String>, val cols: Int) {
    val size: Int get() = raw.size
}

/**
 * Content-based stitching: the snapshot may be older than the live screen (the pane kept printing / redrawing since),
 * so the history view is [snapshot lines older than the live screen] + [the CURRENT live screen]. The alignment is found by
 * matching the live rows against the snapshot's tail.
 */
object HistoryStitch {
    private val ANSI = Regex("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|\u001b[()][A-Za-z0-9]|\u001b[=>78]")

    fun splitLines(snapshot: String): List<String> =
        snapshot.replace("\r\n", "\n").trimEnd('\n', '\r').split('\n')

    fun stripAnsi(s: String): String = if (s.indexOf('\u001b') < 0) s else ANSI.replace(s, "")

    fun snapshotOf(text: String, cols: Int): HistorySnapshot {
        val raw = splitLines(text)
        return HistorySnapshot(raw, raw.map { stripAnsi(it).trimEnd() }, cols)
    }

    /** Plain text of the live screen rows (trailing blanks trimmed). */
    fun livePlain(emu: TerminalEmulator): List<String> =
        List(emu.mRows) { r -> emu.screen.getSelectedText(0, r, emu.mColumns - 1, r, false).trimEnd() }

    /** The live screen as ANSI rows (colours and attributes kept). */
    fun liveAnsi(emu: TerminalEmulator): List<String> = List(emu.mRows) { r -> AnsiRows.row(emu, r) }

    /** Minimum run of consecutive matching non-blank rows required for a confident match. */
    fun minRun(rows: Int): Int = min(6, max(2, rows / 3))

    /**
     * Number of snapshot lines that lie above the live screen (live row 0 corresponds to snapshot line `result`), or null
     * when there is no confident, unambiguous alignment. Searches the last 3 screens of the snapshot.
     */
    fun align(snap: List<String>, live: List<String>): Int? {
        val rows = live.size
        val s = snap.size
        if (rows == 0 || s <= 1) return null
        val lo = max(1, s - 3 * rows)
        var best = -1; var bestScore = 0; var bestRun = 0
        var second = 0
        for (delta in lo until s) {
            var total = 0; var run = 0; var longest = 0
            for (j in 0 until rows) {
                val idx = delta + j
                if (idx >= s) break
                val a = live[j]; val b = snap[idx]
                if (a.isEmpty() && b.isEmpty()) continue // blank = blank keeps a run alive but is no evidence
                if (a == b) { total++; run++; if (run > longest) longest = run } else run = 0
            }
            if (total > bestScore) {
                second = bestScore
                best = delta; bestScore = total; bestRun = longest
            } else if (total > second) second = total
        }
        if (best < 0 || bestRun < minRun(rows)) return null
        if (bestScore - second < max(2, bestScore / 4)) return null // ambiguous (repeated lines)
        return best
    }

    /** Live rows at the bottom after the last row that matches the snapshot at [delta] (a hint for volatile footer rows). */
    fun volatileTail(snap: List<String>, live: List<String>, delta: Int): Int {
        var lastMatch = -1
        for (j in live.indices) {
            val idx = delta + j
            if (idx >= snap.size) break
            if (live[j].isNotEmpty() && live[j] == snap[idx]) lastMatch = j
        }
        return live.size - 1 - lastMatch
    }

    /** Text for a fresh emulator: [prefix] (ANSI) followed by the live rows, so the last [liveAnsi].size lines are the live screen. */
    fun feed(prefixRaw: List<String>, liveAnsi: List<String>): String {
        val sb = StringBuilder()
        sb.append("\u001b[?25l")
        for (l in prefixRaw) sb.append(l).append("\u001b[0m\r\n")
        for ((i, l) in liveAnsi.withIndex()) {
            sb.append(l).append("\u001b[0m")
            if (i < liveAnsi.size - 1) sb.append("\r\n")
        }
        return sb.toString()
    }

    /** Stitched emulator, or null if the snapshot cannot be aligned with the live screen. Safe on a background thread. */
    fun build(snap: HistorySnapshot, livePlain: List<String>, liveAnsi: List<String>, cols: Int, rows: Int, cellW: Int, cellH: Int): TerminalEmulator? {
        if (snap.cols != cols || livePlain.size != rows) return null
        val prefix = align(snap.plain, livePlain) ?: return null
        val emu = TerminalEmulator(HistoryMath.NoOutput, cols, rows, cellW, cellH, prefix + rows + 200, HistoryMath.NoClient)
        val bytes = feed(snap.raw.subList(0, prefix), liveAnsi).toByteArray(Charsets.UTF_8)
        emu.append(bytes, bytes.size)
        emu.clearScrollCounter()
        return emu
    }
}
