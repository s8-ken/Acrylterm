package com.shibaken.androidterminal

import com.shibaken.androidterminal.terminal.Band
import com.shibaken.androidterminal.terminal.BandTracker
import com.shibaken.androidterminal.terminal.FixedBands
import com.shibaken.androidterminal.terminal.RowSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedBandsTest {
    private fun sigs(vararg t: String) = LongArray(t.size) { RowSig.of(t[it]) }
    private fun screen(rows: Int, header: List<String>, body: List<String>, footer: List<String>): LongArray {
        val bodyRows = rows - header.size - footer.size
        val b = body.take(bodyRows) + List(maxOf(0, bodyRows - body.size)) { "" }
        return sigs(*(header + b + footer).toTypedArray())
    }
    private val lines = List(100) { "line number $it with text" }

    @Test fun blankRowsHaveNoSignature() {
        assertEquals(0L, RowSig.of("   "))
        assertEquals(0L, RowSig.of(""))
        assertTrue(RowSig.of("a") != RowSig.of("b"))
        assertTrue(RowSig.of("a", 5) != RowSig.of("a", 6))
    }

    @Test fun vimLikeHeaderAndFooter() {
        val h = listOf("[No Name] tab1 | file.kt"); val f = listOf("-- INSERT --", "file.kt 10,3 Top")
        val old = screen(24, h, lines.subList(10, 31), f)
        val new = screen(24, h, lines.subList(13, 34), f)
        assertEquals(Band(1, 2), FixedBands.detect(old, new))
        val old2 = screen(24, h, lines.subList(13, 34), f)
        val up = screen(24, h, lines.subList(10, 31), f) // scrolling the other way
        assertEquals(Band(1, 2), FixedBands.detect(old2, up))
    }

    @Test fun vimMarginsMap() {
        assertEquals(Band(1, 2), FixedBands.fromMargins(1, 22, 24))
        assertNull(FixedBands.fromMargins(0, 24, 24))
        assertNull(FixedBands.fromMargins(5, 7, 24)) // band too small
        assertEquals(Band(0, 3), FixedBands.fromMargins(0, 21, 24))
    }

    @Test fun claudeCodeLikeFixedBottomBox() {
        val box = listOf("", "╭──────────────╮", "│ > ask       │", "╰──────────────╯", "  ? for shortcuts")
        val old = screen(30, emptyList(), lines.subList(0, 25), box)
        val new = screen(30, emptyList(), lines.subList(2, 27), box)
        assertEquals(Band(0, 5), FixedBands.detect(old, new))
    }

    @Test fun codexLikeShadedComposerFooter() {
        val f = listOf("› Write tests", "  gpt-5 · 80% context left")
        val old = screen(20, emptyList(), lines.subList(5, 23), f)
        val new = screen(20, emptyList(), lines.subList(8, 26), f)
        assertEquals(Band(0, 2), FixedBands.detect(old, new))
    }

    @Test fun lessLikeFooterOnly() {
        val old = screen(24, emptyList(), lines.subList(0, 23), listOf(":"))
        val new = screen(24, emptyList(), lines.subList(1, 24), listOf(":"))
        assertEquals(Band(0, 1), FixedBands.detect(old, new))
    }

    @Test fun htopLikeHeaderOnly() {
        val h = listOf("CPU[|||  ] 12%", "Mem[||||  ] 3G", "Tasks: 100", "  PID USER CMD")
        val old = screen(24, h, lines.subList(0, 20), emptyList())
        val new = screen(24, h, lines.subList(4, 24), emptyList())
        assertEquals(Band(4, 0), FixedBands.detect(old, new))
    }

    @Test fun fullScreenScrollYieldsNoFixedRows() {
        val old = screen(20, emptyList(), lines.subList(0, 20), emptyList())
        val new = screen(20, emptyList(), lines.subList(3, 23), emptyList())
        assertEquals(Band.NONE, FixedBands.detect(old, new))
    }

    @Test fun repaintWithoutScrollGivesNoBand() {
        val a = screen(24, listOf("head"), lines.subList(0, 21), listOf("foot", "foot2"))
        assertNull(FixedBands.detect(a, a))
        val b = screen(24, listOf("head"), lines.subList(0, 20) + "spinner |", listOf("foot", "foot2"))
        assertNull(FixedBands.detect(a, b))
    }

    @Test fun blankAndAmbiguousScreens() {
        val blank = LongArray(24)
        assertNull(FixedBands.detect(blank, blank))
        val dup = LongArray(24) { RowSig.of("same") }
        assertNull(FixedBands.detect(dup, dup))
        val periodic = LongArray(24) { RowSig.of("row ${it % 3}") }
        val shifted = LongArray(24) { RowSig.of("row ${(it + 1) % 3}") }
        assertNull(FixedBands.detect(periodic, shifted))
    }

    @Test fun changedFixedRowsRejectTheBand() {
        val old = screen(24, listOf("head"), lines.subList(0, 21), listOf("f1", "f2"))
        val new = screen(24, listOf("other"), lines.subList(3, 24), listOf("g1", "g2"))
        assertNull(FixedBands.detect(old, new))
    }

    @Test fun priorityLearnedThenMarginsThenHeuristic() {
        val learned = Band(1, 2); val margins = Band(0, 3)
        assertEquals(learned, FixedBands.choose(learned, margins, 4))
        assertEquals(margins, FixedBands.choose(null, margins, 4))
        assertEquals(Band(0, 4), FixedBands.choose(null, null, 4))
        assertEquals(Band.NONE, FixedBands.choose(null, null, 0))
        assertEquals(Band.NONE, FixedBands.choose(Band.NONE, margins, 4)) // learned "nothing fixed" wins
    }

    private val h = listOf("head")
    private val f = listOf("foot", "foot2")

    @Test fun trackerLearnsFromProbeAndRepaint() {
        val t = BandTracker()
        t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, 0)
        // partial first repaint: nothing recognisable yet
        assertTrue(!t.onRepaint(LongArray(24), 80, false, 10))
        assertTrue(t.hasProbe)
        assertTrue(t.onRepaint(screen(24, h, lines.subList(2, 23), f), 80, false, 20))
        assertEquals(Band(1, 2), t.learned)
        assertTrue(!t.hasProbe)
    }

    @Test fun trackerInvalidatesOnContextChange() {
        val t = BandTracker()
        t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, 0)
        t.onRepaint(screen(24, h, lines.subList(2, 23), f), 80, false, 10)
        t.validateContext(24, 80, false); assertEquals(Band(1, 2), t.learned)
        t.validateContext(30, 80, false); assertNull(t.learned) // resize
        t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, 100)
        t.onRepaint(screen(24, h, lines.subList(2, 23), f), 80, false, 110)
        t.validateContext(24, 80, true); assertNull(t.learned) // alt-screen switch
    }

    @Test fun trackerInvalidatesWhenFixedRowsKeepDisagreeing() {
        val t = BandTracker()
        t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, 0)
        t.onRepaint(screen(24, h, lines.subList(2, 23), f), 80, false, 10)
        assertEquals(Band(1, 2), t.learned)
        var now = 100L
        repeat(BandTracker.STRIKES) { i ->
            t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, now)
            // layout changed: header/footer rows differ, nothing shifts
            t.onRepaint(screen(24, listOf("new$i"), lines.subList(50, 71), listOf("a$i", "b$i")), 80, false, now + 5)
            now += 2000
        }
        assertNull(t.learned)
    }

    @Test fun trackerRefreshesBandOnNewDetection() {
        val t = BandTracker()
        t.beginProbe(screen(24, h, lines.subList(0, 21), f), 80, false, 0)
        t.onRepaint(screen(24, h, lines.subList(2, 23), f), 80, false, 10)
        val f2 = listOf("f1", "f2", "f3", "f4")
        t.beginProbe(screen(24, h, lines.subList(0, 19), f2), 80, false, 3000)
        assertTrue(t.onRepaint(screen(24, h, lines.subList(2, 21), f2), 80, false, 3010))
        assertEquals(Band(1, 4), t.learned)
    }
}
