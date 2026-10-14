package com.shibaken.androidterminal.terminal

import com.termux.terminal.TerminalEmulator
import kotlin.math.max
import kotlin.math.min

/** Rows of a TUI that stay put while the content between them scrolls: [header] at the top, [footer] at the bottom. */
data class Band(val header: Int, val footer: Int) {
    val isNone: Boolean get() = header == 0 && footer == 0
    companion object { val NONE = Band(0, 0) }
}

/** Per-row signatures (hash of text + attributes; 0 = blank row, which never counts as evidence). */
object RowSig {
    fun of(text: String, styleHash: Long = 0L): Long {
        if (text.isBlank()) return 0L
        var h = -0x340d631b7bdddcdbL xor styleHash // FNV-1a 64
        for (ch in text.trimEnd()) { h = (h xor ch.code.toLong()) * 0x100000001b3L }
        return if (h == 0L) 1L else h
    }

    fun ofScreen(emu: TerminalEmulator): LongArray {
        val cols = emu.mColumns
        val screen = emu.screen
        return LongArray(emu.mRows) { r ->
            val text = screen.getSelectedText(0, r, cols - 1, r, false)
            if (text.isBlank()) 0L else {
                var sh = 0L
                for (c in 0 until cols) sh = (sh * 31) xor screen.getStyleAt(r, c)
                of(text, sh)
            }
        }
    }
}

/** DECSTBM margins and the generic frame-diff band detector; both pure. */
object FixedBands {
    const val MIN_BAND = 3
    private const val DENSITY = 0.7
    private const val AMBIGUITY = 0.6
    private const val FIXED_TOLERANCE = 0.25
    private const val INPLACE_LIMIT = 0.3

    /** Fixed rows implied by scroll margins (top = first scrolling row, bottom = exclusive end); null when not restricted. */
    fun fromMargins(top: Int, bottom: Int, rows: Int): Band? {
        if (rows < 2 + MIN_BAND) return null
        if (top <= 0 && bottom >= rows) return null
        if (top < 0 || bottom > rows || bottom - top < MIN_BAND) return null
        return Band(top, rows - bottom)
    }

    /** Learned band > margins > heuristic footer rows > nothing. */
    fun choose(learned: Band?, margins: Band?, heuristicFooter: Int): Band = when {
        learned != null -> learned
        margins != null -> margins
        heuristicFooter > 0 -> Band(0, heuristicFooter)
        else -> Band.NONE
    }

    /**
     * Compares the screen before a scroll ([old]) and after ([new]). Returns the band (possibly [Band.NONE] when everything
     * scrolled) when a confident, unique shift is found, else null (no update).
     */
    fun detect(old: LongArray, new: LongArray): Band? {
        val rows = old.size
        if (rows < 2 + MIN_BAND || new.size != rows) return null
        val maxK = rows / 2
        var bestK = 0; var bestC = 0; var secondC = 0
        for (k in -maxK..maxK) {
            if (k == 0) continue
            var c = 0
            for (i in 0 until rows) { val j = i + k; if (j in 0 until rows && old[i] != 0L && old[i] == new[j]) c++ }
            if (c > bestC) { secondC = bestC; bestC = c; bestK = k } else if (c > secondC) secondC = c
        }
        if (bestC < MIN_BAND || secondC > bestC * AMBIGUITY) return null
        val k = bestK
        var lo = -1; var hi = -1
        for (i in 0 until rows) { val j = i + k; if (j in 0 until rows && old[i] != 0L && old[i] == new[j]) { if (lo < 0) lo = i; hi = i } }
        var evidence = 0; var inPlace = 0
        for (i in lo..hi) {
            if (old[i] == 0L) continue
            evidence++
            if (old[i] == new[i]) inPlace++
        }
        if (bestC < evidence * DENSITY || inPlace > bestC * INPLACE_LIMIT) return null
        val top = max(0, min(lo, lo + k))
        val bottom = min(rows, max(hi, hi + k) + 1)
        if (bottom - top < MIN_BAND) return null
        var counted = 0; var changed = 0
        for (i in 0 until rows) {
            if (i in top until bottom) continue
            if (old[i] == 0L && new[i] == 0L) continue
            counted++
            if (old[i] != new[i]) changed++
        }
        if (counted > 0 && changed > counted * FIXED_TOLERANCE) return null
        return Band(top, rows - bottom)
    }

    /** Fraction of the band's fixed rows (non-blank in [old]) that are unchanged in [new]; 1.0 when there are none. */
    fun fixedAgreement(band: Band, old: LongArray, new: LongArray): Double {
        val rows = old.size
        var n = 0; var same = 0
        for (i in 0 until rows) {
            if (i >= band.header && i < rows - band.footer) continue
            if (old[i] == 0L) continue
            n++
            if (old[i] == new[i]) same++
        }
        return if (n == 0) 1.0 else same.toDouble() / n
    }
}

/**
 * Per-pane memory of the learned band. A probe (screen signatures taken right before wheel events are forwarded) is compared
 * with the next few repaints; a confident shift updates [learned]. The learned band is dropped on resize / alt-screen switch,
 * or when its fixed rows keep disagreeing across repaints (the app changed layout).
 */
class BandTracker(private val probeTtlMs: Long = 1500L) {
    var learned: Band? = null
        private set
    private var rows = 0
    private var cols = 0
    private var alt = false
    private var probe: LongArray? = null
    private var probeAtMs = 0L
    private var attempts = 0
    private var strikes = 0

    val hasProbe: Boolean get() = probe != null

    fun reset() { learned = null; probe = null; attempts = 0; strikes = 0 }

    /** Call when grid/alt state may have changed; drops the learned band if its context no longer matches. */
    fun validateContext(rows: Int, cols: Int, alt: Boolean) {
        if (learned != null && (rows != this.rows || cols != this.cols || alt != this.alt)) reset()
        else if (probe != null && probe!!.size != rows) { probe = null }
    }

    fun beginProbe(sigs: LongArray, cols: Int, alt: Boolean, nowMs: Long) {
        if (probe != null && nowMs - probeAtMs < probeTtlMs) return
        probe = sigs; probeAtMs = nowMs; attempts = 0
        if (learned == null) { rows = sigs.size; this.cols = cols; this.alt = alt }
    }

    fun probeExpired(nowMs: Long): Boolean = probe != null && nowMs - probeAtMs >= probeTtlMs

    /** A repaint arrived; [new] are its signatures. Returns true when [learned] changed. */
    fun onRepaint(new: LongArray, cols: Int, alt: Boolean, nowMs: Long): Boolean {
        val old = probe ?: return false
        if (nowMs - probeAtMs >= probeTtlMs || old.size != new.size) { probe = null; return false }
        attempts++
        val found = FixedBands.detect(old, new)
        if (found != null) {
            val changed = found != learned
            learned = found; rows = new.size; this.cols = cols; this.alt = alt; strikes = 0
            probe = null
            return changed
        }
        val l = learned
        if (l != null && !l.isNone && old.any { it != 0L } && FixedBands.fixedAgreement(l, old, new) < 0.5) {
            if (++strikes >= STRIKES) { reset(); return true }
        }
        if (attempts >= MAX_ATTEMPTS) probe = null
        return false
    }

    companion object { const val MAX_ATTEMPTS = 3; const val STRIKES = 4 }
}
