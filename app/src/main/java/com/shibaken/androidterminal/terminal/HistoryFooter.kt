package com.shibaken.androidterminal.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import kotlin.math.max

/** One live screen row as seen by [FooterDetector]: trimmed plain text, and whether most of its width has a non-default background. */
class FooterRow(val text: String, val shaded: Boolean = false)

/**
 * Finds the TUI "footer" of the live screen (input composer + status/hint lines: Claude Code's bordered box, Codex's shaded
 * composer, ...) so it can stay pinned at the bottom while the content above scrolls. Generic: combines
 *  - the cursor row as anchor (if visible and in the bottom half),
 *  - input chrome extended upward: box-drawing borders, horizontal rules, rows with a shaded (non-default) background
 *    spanning most of the width, and one attached prompt-glyph row (›, ▌, >, ❯),
 *  - everything below down to the last row (status / hint lines),
 *  - capped at [MAX_FRACTION] of the screen; without any chrome only a short prompt-like tail counts.
 * Codex's exact rendering is not confirmed; the shaded-background and glyph signals are deliberately tolerant.
 */
object FooterDetector {
    private const val BOX_STARTS = "╭│╰┌└├┤┬┴┼╔║╚╒╘╞"
    private const val RULE_CHARS = "─━═╌╍┄┅—-_"
    private const val PROMPT_GLYPHS = "›▌>❯»▶"
    const val MAX_FRACTION = 0.4f
    const val SHADED_FRACTION = 0.6f

    fun isBoxLine(row: String): Boolean {
        val t = row.trim()
        return t.isNotEmpty() && BOX_STARTS.indexOf(t[0]) >= 0
    }

    fun isRule(row: String, cols: Int): Boolean {
        val t = row.trim()
        if (t.length < max(10, cols / 2)) return false
        val n = t.count { RULE_CHARS.indexOf(it) >= 0 }
        return n >= t.length * 0.8
    }

    fun isPromptGlyphRow(row: String): Boolean {
        val t = row.trimStart()
        return t.isNotEmpty() && PROMPT_GLYPHS.indexOf(t[0]) >= 0 && (t.length == 1 || t[1] == ' ')
    }

    private fun isChrome(r: FooterRow, cols: Int) = isBoxLine(r.text) || isRule(r.text, cols) || r.shaded

    /** Rows of the live screen for [detect]. */
    fun rowsOf(emu: TerminalEmulator): List<FooterRow> {
        val plain = HistoryStitch.livePlain(emu)
        val cols = emu.mColumns
        val screen = emu.screen
        return List(emu.mRows) { r ->
            var shadedCells = 0
            for (c in 0 until cols) if (TextStyle.decodeBackColor(screen.getStyleAt(r, c)) != TextStyle.COLOR_INDEX_BACKGROUND) shadedCells++
            FooterRow(plain[r], shadedCells >= cols * SHADED_FRACTION)
        }
    }

    fun detect(rows: List<String>, cursorRow: Int, cursorVisible: Boolean, cols: Int): Int =
        detectRows(rows.map { FooterRow(it) }, cursorRow, cursorVisible, cols)

    /** Number of bottom rows forming the footer (0 = none). */
    fun detectRows(rows: List<FooterRow>, cursorRow: Int, cursorVisible: Boolean, cols: Int): Int {
        val r = rows.size
        if (r < 4) return 0
        val cap = max(1, (r * MAX_FRACTION).toInt())
        val anchor = if (cursorVisible && cursorRow >= r / 2 && cursorRow < r) cursorRow
        else (r - 1 downTo r - cap).firstOrNull { isChrome(rows[it], cols) } ?: return 0
        var top = anchor
        var glyphUsed = isPromptGlyphRow(rows[anchor].text) // the anchor itself may be the prompt row
        while (true) {
            if (top - 1 >= 0 && isChrome(rows[top - 1], cols)) { top--; continue }
            if (top - 1 >= 0 && !glyphUsed && isPromptGlyphRow(rows[top - 1].text)) { glyphUsed = true; top--; continue }
            if (top == anchor && isRule(rows[anchor].text, cols) && !rows[anchor].shaded) {
                // rule / input text / rule style: pair the rule with the one above, across the input rows
                var j = top - 1
                var found = -1
                while (j >= 0 && top - j <= 8 && rows[j].text.isNotBlank()) { if (isChrome(rows[j], cols)) { found = j; break }; j-- }
                if (found >= 0) { top = found; continue }
            }
            break
        }
        if (top == anchor) {
            // No chrome above: only a short prompt-like tail counts (cursor row .. bottom); a shaded composer row may have more hints below.
            if (!(cursorVisible && cursorRow == anchor)) return 0
            val n = r - anchor
            val limit = if (rows[anchor].shaded) cap else 3
            return if (n <= limit) n else 0
        }
        if (top - 1 >= 0 && rows[top - 1].text.isBlank()) top-- // blank separator directly above the composer moves with it
        val n = r - top
        return if (n in 1..cap) n else 0
    }
}
