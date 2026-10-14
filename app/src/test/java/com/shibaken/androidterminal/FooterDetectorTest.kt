package com.shibaken.androidterminal

import com.shibaken.androidterminal.terminal.FooterDetector
import com.shibaken.androidterminal.terminal.FooterRow
import com.shibaken.androidterminal.terminal.HistoryStitch
import org.junit.Assert.assertEquals
import org.junit.Test

class FooterDetectorTest {
    private val cols = 80
    private val rule = "─".repeat(cols)
    private fun body(n: Int) = List(n) { "conversation line $it" }

    @Test fun claudeCodeBoxWithStatusLinesBelow() {
        // 30 rows: content, blank, box (3 rows), 2 status rows
        val rows = body(24) + listOf("") + listOf("╭" + "─".repeat(78) + "╮", "│ > hello" + " ".repeat(60) + "│", "╰" + "─".repeat(78) + "╯") + listOf("  ? for shortcuts", "  tokens 12k")
        // 24 + 1 + 3 + 2 = 30; cursor in the input line (row 26)
        assertEquals(30, rows.size)
        assertEquals(6, FooterDetector.detect(rows, 26, true, cols)) // blank + box + status = rows 24..29
    }

    @Test fun ruleStyleInputWithHiddenCursor() {
        val rows = body(40) + listOf("", rule, "❯ type here", rule, "  esc to interrupt", "  status")
        assertEquals(46, rows.size)
        assertEquals(6, FooterDetector.detect(rows, 0, false, cols)) // blank + rule + input + rule + 2 status
    }

    @Test fun ruleStyleInputWithVisibleCursor() {
        val rows = body(40) + listOf(rule, "❯ type here", rule, "  status")
        assertEquals(4, FooterDetector.detect(rows, 41, true, cols))
    }

    @Test fun plainShellPromptOnLastRowIsOneRow() {
        val rows = body(29) + listOf("user@host ~ % ")
        assertEquals(1, FooterDetector.detect(rows, 29, true, cols))
    }

    @Test fun plainShellPromptMidScreenIsNoFooter() {
        val rows = body(10) + listOf("user@host ~ % ") + List(19) { "" }
        assertEquals(0, FooterDetector.detect(rows, 10, true, cols))
    }

    @Test fun cursorHiddenWithoutChromeIsNoFooter() {
        assertEquals(0, FooterDetector.detect(body(30), 29, false, cols))
    }

    @Test fun singleHorizontalRuleWithHiddenCursorIsNotABox() {
        val rows = body(28) + listOf(rule, "after rule")
        assertEquals(0, FooterDetector.detect(rows, 0, false, cols))
    }

    @Test fun boxTallerThanCapIsNoFooter() {
        // 20 rows: cap = 8; a 12-row box exceeds it
        val rows = body(8) + listOf("╭" + "─".repeat(78) + "╮") + List(10) { "│ line $it" } + listOf("╰" + "─".repeat(78) + "╯")
        assertEquals(20, rows.size)
        assertEquals(0, FooterDetector.detect(rows, 12, true, cols))
    }

    @Test fun cursorInTopHalfIgnoredFallsBackToChrome() {
        val rows = body(24) + listOf("", "╭" + "─".repeat(78) + "╮", "│ > ", "╰" + "─".repeat(78) + "╯", "  status", "  status2")
        assertEquals(6, FooterDetector.detect(rows, 3, true, cols))
    }

    @Test fun tinyScreenHasNoFooter() {
        assertEquals(0, FooterDetector.detect(listOf("a", "b", "c"), 2, true, cols))
    }
}

class FooterDetectorCodexTest {
    private val cols = 40
    private fun emu(rows: Int) = com.shibaken.androidterminal.terminal.HistoryMath.buildEmulator("", cols, rows, 8, 16, -1)
    private fun feed(e: com.termux.terminal.TerminalEmulator, s: String) { val b = s.toByteArray(); e.append(b, b.size) }
    private fun shaded(text: String) = "\u001b[48;2;50;50;60m" + text.padEnd(cols) + "\u001b[0m"

    @Test fun codexLikeShadedComposerWithGlyphAndHintsBelow() {
        val e = emu(20)
        val rows = (0 until 14).map { "assistant output $it" } + listOf("", shaded(""), shaded("› fix the bug"), shaded(""), "  ⏎ send  ctrl+j newline", "  gpt-5  72% context left")
        feed(e, "\u001b[2J\u001b[H" + rows.joinToString("\r\n"))
        feed(e, "\u001b[16;6H") // cursor inside the composer (row index 15)
        val n = FooterDetector.detectRows(FooterDetector.rowsOf(e), e.cursorRow, e.shouldCursorBeVisible(), cols)
        assertEquals(6, n) // blank + 3 shaded rows + 2 hint rows = rows 14..19
    }

    @Test fun codexLikeSingleShadedRowWithHints() {
        val e = emu(20)
        val rows = (0 until 16).map { "out $it" } + listOf(shaded("› ask"), "  ⏎ send", "  gpt-5")
        feed(e, "\u001b[2J\u001b[H" + rows.joinToString("\r\n"))
        feed(e, "\u001b[17;4H")
        assertEquals(4, FooterDetector.detectRows(FooterDetectorHelper.rows(e), e.cursorRow, true, cols)) // shaded row + 2 hints + blank last row
    }

    @Test fun codexLikeWithCursorHiddenUsesShading() {
        val e = emu(20)
        val rows = (0 until 14).map { "out $it" } + listOf("", shaded(""), shaded("› typed"), shaded(""), "  hint", "  status")
        feed(e, "\u001b[2J\u001b[H" + rows.joinToString("\r\n") + "\u001b[?25l")
        assertEquals(6, FooterDetector.detectRows(FooterDetector.rowsOf(e), e.cursorRow, e.shouldCursorBeVisible(), cols))
    }

    @Test fun claudeCodeFixtureThroughEmulatorHasNoFalseShading() {
        val e = emu(20)
        val box = "─".repeat(38)
        val rows = (0 until 13).map { "out $it" } + listOf("", "╭$box╮", "│ > hi".padEnd(39) + "│", "╰$box╯", "  ? shortcuts", "  tokens", "")
        feed(e, "\u001b[2J\u001b[H" + rows.joinToString("\r\n"))
        feed(e, "\u001b[16;4H")
        assertEquals(7, FooterDetector.detectRows(FooterDetector.rowsOf(e), e.cursorRow, true, cols))
    }

    @Test fun plainShellHasNoFalseFooter() {
        val e = emu(20)
        feed(e, "\u001b[2J\u001b[H" + (0 until 8).joinToString("\r\n") { "output $it" } + "\r\n\$ ")
        assertEquals(0, FooterDetector.detectRows(FooterDetector.rowsOf(e), e.cursorRow, true, cols))
    }

    @Test fun glyphQuoteLinesInConversationAreNotGrabbedPastOne() {
        val rows = (0 until 10).map { FooterRow("out $it") } + List(4) { FooterRow("> quoted $it") } + List(3) { FooterRow("") } +
            listOf(FooterRow("❯ typing"), FooterRow("  status"))
        // cursor on the prompt row (index 17): the quote block above is separated by blanks, so only prompt + status are the footer
        assertEquals(2, FooterDetector.detectRows(rows, 17, true, 40))
    }

    @Test fun volatileTailHint() {
        val snap = (1..50).map { "l$it" }
        val live = snap.subList(40, 47) + listOf("new1", "new2", "new3")
        assertEquals(3, HistoryStitch.volatileTail(snap, live, 40))
    }
}

private object FooterDetectorHelper { fun rows(e: com.termux.terminal.TerminalEmulator) = FooterDetector.rowsOf(e) }
