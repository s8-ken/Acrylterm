package com.shibaken.androidterminal

import com.shibaken.androidterminal.terminal.HistoryMath
import com.shibaken.androidterminal.terminal.HistoryStitch
import com.shibaken.androidterminal.terminal.ModeInfo
import com.shibaken.androidterminal.terminal.RefreshRateBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryScrollTest {
    private fun lines(n: Int) = (1..n).joinToString("\n") { "line$it" }

    private fun rowText(e: com.termux.terminal.TerminalEmulator, row: Int) =
        e.screen.getSelectedText(0, row, e.mColumns - 1, row, false).trimEnd()

    @Test fun entersOnlyWhenScrollingTowardOlderOnMousePane() {
        assertTrue(HistoryMath.shouldBegin(-5f, true, true, 1000, 0))
        assertFalse(HistoryMath.shouldBegin(5f, true, true, 1000, 0))
        assertFalse(HistoryMath.shouldBegin(-5f, false, true, 1000, 0))
        assertFalse(HistoryMath.shouldBegin(-5f, true, false, 1000, 0))
        assertFalse("backoff after a failed fetch", HistoryMath.shouldBegin(-5f, true, true, 1000, 2000))
    }

    @Test fun pendingDistanceAccumulatesAndNeverGoesNegative() {
        var p = 0f
        p = HistoryMath.addPending(p, -30f); assertEquals(30f, p, 0f)
        p = HistoryMath.addPending(p, -20f); assertEquals(50f, p, 0f)
        p = HistoryMath.addPending(p, 80f); assertEquals(0f, p, 0f)
    }

    @Test fun initialScrollContinuesGestureWithinBounds() {
        assertEquals(120f, HistoryMath.initialScroll(120f, 1000f), 0f)
        assertEquals(1000f, HistoryMath.initialScroll(5000f, 1000f), 0f)
        assertEquals(0f, HistoryMath.initialScroll(-3f, 1000f), 0f)
        assertTrue(HistoryMath.shouldEnter(30f, 400f, 40f))
        assertFalse("gesture reversed", HistoryMath.shouldEnter(0f, 400f, 40f))
        assertFalse("no scrollback", HistoryMath.shouldEnter(30f, 0f, 40f))
    }

    @Test fun pendingShiftIsBounded() {
        assertEquals(-10f, HistoryMath.pendingShift(10f, 40f), 0f)
        assertEquals(-60f, HistoryMath.pendingShift(500f, 40f), 0f)
    }

    @Test fun exitsOnlyAtBottomWhenGestureIsOver() {
        assertTrue(HistoryMath.shouldExit(0f, flinging = false, touching = false))
        assertFalse(HistoryMath.shouldExit(0f, flinging = false, touching = true))
        assertFalse(HistoryMath.shouldExit(0f, flinging = true, touching = false))
        assertFalse(HistoryMath.shouldExit(12f, flinging = false, touching = false))
    }

    @Test fun lastNonBlankRow() {
        val blank = setOf(5, 6, 7, 8, 9)
        assertEquals(4, HistoryMath.lastNonBlankRow({ it in blank }, 10))
        assertEquals(-1, HistoryMath.lastNonBlankRow({ true }, 10))
    }

    @Test fun feedHidesCursorNormalizesNewlinesAndPads() {
        val f = HistoryMath.buildFeed("a\r\nb\nc\n\n", liveRows = 10, lastLiveRow = 6)
        assertEquals("\u001b[?25la\r\nb\r\nc" + "\r\n".repeat(3), f)
        assertEquals("\u001b[?25lx", HistoryMath.buildFeed("x", 10, 9))
    }

    @Test fun bottomOfHistoryMatchesFullLiveScreen() {
        val e = HistoryMath.buildEmulator(lines(100), 20, 10, 8, 16, lastLiveRow = 9)
        assertEquals(90, e.screen.activeTranscriptRows)
        assertEquals("line100", rowText(e, 9))
        assertEquals("line91", rowText(e, 0))
        assertEquals("line90", rowText(e, -1))
        assertFalse(e.isMouseTrackingActive)
        assertFalse(e.shouldCursorBeVisible())
    }

    @Test fun bottomOfHistoryMatchesPartiallyFilledLiveScreen() {
        // live screen: content on rows 0..4 only (herdr trims trailing blanks in the snapshot)
        val e = HistoryMath.buildEmulator(lines(100), 20, 10, 8, 16, lastLiveRow = 4)
        assertEquals("line100", rowText(e, 4))
        assertEquals("", rowText(e, 5))
        assertEquals("line96", rowText(e, 0))
        assertEquals(95, e.screen.activeTranscriptRows)
    }

    @Test fun ansiColoursSurvive() {
        val e = HistoryMath.buildEmulator("\u001b[31mred\u001b[0m\n" + lines(30), 20, 10, 8, 16, lastLiveRow = 9)
        val first = -e.screen.activeTranscriptRows
        assertEquals("red", rowText(e, first))
        assertTrue("fg colour kept", com.termux.terminal.TextStyle.decodeForeColor(e.screen.getStyleAt(first, 0)) != com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND)
    }

    @Test fun shortHistoryHasNoTranscript() {
        val e = HistoryMath.buildEmulator(lines(3), 20, 10, 8, 16, lastLiveRow = 9)
        assertEquals(0, e.screen.activeTranscriptRows)
    }

    @Test fun picksHighestRefreshModeAtSameResolution() {
        val modes = listOf(
            ModeInfo(1, 1344, 2992, 60f), ModeInfo(2, 1344, 2992, 120f),
            ModeInfo(3, 1008, 2244, 120f), ModeInfo(4, 1008, 2244, 60f),
        )
        assertEquals(3, RefreshRateBoost.pickBestModeId(modes, 1008, 2244))
        assertEquals(2, RefreshRateBoost.pickBestModeId(modes, 1344, 2992))
        assertNull(RefreshRateBoost.pickBestModeId(modes, 100, 100))
    }
}

class HistoryCachePolicyTest {
    private fun policy() = com.shibaken.androidterminal.terminal.HistoryCachePolicy(1500, 5000, 15000)

    @Test fun noPrefetchBeforeAnyOutputOrWhenHidden() {
        val p = policy()
        assertFalse(p.shouldPrefetch(10_000, true, false))
        p.onOutput(0)
        assertTrue(p.shouldPrefetch(10_000, true, false))
        assertFalse(p.shouldPrefetch(10_000, false, false))
        assertFalse(p.shouldPrefetch(10_000, true, true))
    }

    @Test fun waitsForQuietPeriod() {
        val p = policy()
        p.onOutput(10_000)
        assertFalse(p.shouldPrefetch(11_000, true, false))
        assertTrue(p.shouldPrefetch(11_500, true, false))
        p.onOutput(11_400)
        assertFalse(p.shouldPrefetch(11_500, true, false))
    }

    @Test fun freshOnlyIfNoOutputSinceFetchStart() {
        val p = policy()
        p.onOutput(0)
        val t = p.begin(10_000)
        assertTrue(p.complete(t, true))
        assertTrue(p.isFresh)
        assertFalse("fresh cache needs no refetch", p.shouldPrefetch(99_000, true, false))
        p.onOutput(100_000)
        assertFalse(p.isFresh)
    }

    @Test fun outputDuringFetchMakesResultStale() {
        val p = policy()
        p.onOutput(0)
        val t = p.begin(10_000)
        p.onOutput(10_100)
        assertFalse(p.complete(t, true))
        assertFalse(p.isFresh)
    }

    @Test fun rateLimitedToOncePerInterval() {
        val p = policy()
        p.onOutput(0)
        val t = p.begin(10_000)
        p.complete(t, true)
        p.onOutput(11_000)
        assertFalse("quiet but within 5s of the last fetch", p.shouldPrefetch(13_000, true, false))
        assertTrue(p.shouldPrefetch(15_000, true, false))
    }

    @Test fun failureBacksOffAndStreamingNeverLoops() {
        val p = policy()
        p.onOutput(0)
        val t = p.begin(10_000)
        p.complete(t, false)
        assertFalse(p.shouldPrefetch(12_000, true, false))
        assertTrue(p.shouldPrefetch(15_000, true, false))
        p.complete(p.begin(15_000), false)
        // continuous output every 1s: never quiet, refreshes only every >= 8 s (stitched), never a loop
        var now = 20_000L
        var fetches = 0
        repeat(60) {
            p.onOutput(now); now += 1000
            if (p.shouldPrefetch(now, true, false)) { fetches++; p.complete(p.begin(now), true) }
        }
        assertTrue("fetches=$fetches", fetches in 1..8)
    }

    @Test fun inFlightBlocksUntilStuckTimeout() {
        val p = policy()
        p.onOutput(0)
        p.begin(10_000)
        assertFalse(p.shouldPrefetch(20_000, true, false))
        assertTrue(p.shouldPrefetch(26_000, true, false))
    }

    @Test fun invalidateDropsCacheAndAdoptNeedsSameCounter() {
        val p = policy()
        p.onOutput(0)
        assertTrue(p.adopt(p.outputSeq))
        assertTrue(p.isFresh)
        p.invalidate()
        assertFalse(p.isFresh)
        assertFalse(p.adopt(p.outputSeq - 1))
    }
}

class HistoryStitchTest {
    private fun snap(n: Int) = (1..n).map { "message number $it" }
    private fun live(rows: List<String>) = rows

    @Test fun exactMatchWhenNothingChanged() {
        val s = snap(200)
        val l = s.takeLast(20)
        assertEquals(180, HistoryStitch.align(s, l))
    }

    @Test fun liveScrolledForwardSinceSnapshot() {
        val s = snap(200)
        // 5 new lines printed after the snapshot: live = snapshot rows 185..199 + 5 new
        val l = s.subList(185, 200) + (1..5).map { "new $it" }
        assertEquals(185, HistoryStitch.align(s, l))
    }

    @Test fun volatileBottomRowsDiffer() {
        val s = snap(200)
        val l = s.takeLast(20).toMutableList()
        for (i in 14 until 20) l[i] = "input box $i changed"
        assertEquals(180, HistoryStitch.align(s, l))
    }

    @Test fun spinnerLineInTheMiddleDiffers() {
        val s = snap(200)
        val l = s.takeLast(20).toMutableList()
        l[9] = "| working 12s"
        assertEquals(180, HistoryStitch.align(s, l))
    }

    @Test fun noMatchWhenScreenFullyChanged() {
        val s = snap(200)
        val l = (1..20).map { "something else $it" }
        assertNull(HistoryStitch.align(s, l))
        assertNull(HistoryStitch.align(s, List(20) { "" }))
    }

    @Test fun tooShortRunIsNotConfident() {
        val s = snap(200)
        val l = (1..20).map { "other $it" }.toMutableList()
        l[3] = s[190]; l[4] = s[191] // only 2 consecutive rows match
        assertNull(HistoryStitch.align(s, l))
    }

    @Test fun repeatedIdenticalLinesAreAmbiguous() {
        val s = List(100) { "-----" }
        assertNull(HistoryStitch.align(s, List(20) { "-----" }))
    }

    @Test fun repeatedBlockWithDistinctAnchorStillAligns() {
        val s = snap(150) + List(10) { "-----" } + snap(30).map { "tail $it" }
        val l = s.takeLast(20)
        assertEquals(s.size - 20, HistoryStitch.align(s, l))
    }

    @Test fun wideCharactersAndTrimming() {
        val s = (1..100).map { "日本語のテキスト $it ｱｲｳ" }
        val l = s.takeLast(12).map { it + "   " }.map { it.trimEnd() }
        assertEquals(88, HistoryStitch.align(s, l))
    }

    @Test fun stripAnsiAndPlainLines() {
        val snap = HistoryStitch.snapshotOf("\u001b[0m\u001b[31mred\u001b[0m   \n\u001b[1;38;2;1;2;3mx\u001b[0m\n", 20)
        assertEquals(listOf("red", "x"), snap.plain)
    }

    private fun feed(emu: com.termux.terminal.TerminalEmulator, s: String) { val b = s.toByteArray(); emu.append(b, b.size) }

    @Test fun liveAnsiRoundTripKeepsTextAndColours() {
        val src = HistoryMath.buildEmulator("", 20, 4, 8, 16, -1)
        feed(src, "\u001b[2J\u001b[H\u001b[31mred\u001b[0m plain\r\n\u001b[1;48;2;10;20;30m日本\u001b[0m x\r\n\u001b[44m  bar\u001b[0m")
        val plain = HistoryStitch.livePlain(src)
        assertEquals(listOf("red plain", "日本 x", "  bar", ""), plain)
        val dst = HistoryMath.buildEmulator("", 20, 4, 8, 16, -1)
        feed(dst, "\u001b[2J\u001b[H" + HistoryStitch.liveAnsi(src).joinToString("\r\n"))
        assertEquals(plain, HistoryStitch.livePlain(dst))
        for (r in 0 until 3) for (c in 0 until 8)
            assertEquals("style r=$r c=$c", src.screen.getStyleAt(r, c), dst.screen.getStyleAt(r, c))
    }

    @Test fun stitchedEmulatorHasOldLinesAboveAndExactLiveScreenBelow() {
        val s = (1..200).map { "line $it" }
        val raw = s.joinToString("\n")
        val snap = HistoryStitch.snapshotOf(raw, 30)
        // live screen: 10 rows; content scrolled 3 rows, bottom two rows volatile
        val liveRows = s.subList(193, 200).map { it } + listOf("new a", "new b", "new c")
        val src = HistoryMath.buildEmulator("", 30, 10, 8, 16, -1)
        feed(src, "\u001b[2J\u001b[H" + liveRows.joinToString("\r\n"))
        val e = HistoryStitch.build(snap, HistoryStitch.livePlain(src), HistoryStitch.liveAnsi(src), 30, 10, 8, 16)!!
        assertEquals(193, e.screen.activeTranscriptRows)
        assertEquals("line 1", e.screen.getSelectedText(0, -193, 29, -193, false).trim())
        assertEquals("line 193", e.screen.getSelectedText(0, -1, 29, -1, false).trim())
        assertEquals(liveRows, HistoryStitch.livePlain(e))
        assertNull(HistoryStitch.build(snap, HistoryStitch.livePlain(src), HistoryStitch.liveAnsi(src), 31, 10, 8, 16))
    }
}

class HistoryBoundsTest {
    @Test fun shallowSnapshotIsNotDeepEnough() {
        assertFalse(HistoryMath.deepEnough(0, 53))   // alt-screen TUI: herdr returns only the visible screen
        assertFalse(HistoryMath.deepEnough(3, 53))
        assertTrue(HistoryMath.deepEnough(53, 53))
        assertTrue(HistoryMath.deepEnough(2000, 53))
    }

    @Test fun screenOnlySnapshotBuildsNoUsefulTranscript() {
        val screenOnly = (1..52).joinToString("\n") { "row $it" }
        val e = HistoryMath.buildEmulator(screenOnly, 40, 53, 8, 16, 51)
        assertFalse(HistoryMath.deepEnough(e.screen.activeTranscriptRows, e.mRows))
    }

    @Test fun deepSnapshotKeepsFullBounds() {
        val deep = (1..3000).joinToString("\n") { "row $it" }
        val e = HistoryMath.buildEmulator(deep, 40, 53, 8, 16, 52)
        assertTrue(HistoryMath.deepEnough(e.screen.activeTranscriptRows, e.mRows))
        assertEquals(3000 - 53, e.screen.activeTranscriptRows)
        val max = e.screen.activeTranscriptRows * 16f
        assertEquals(max, HistoryMath.initialScroll(1e9f, max), 0f)
    }

    @Test fun replayRowsConvertsSwallowedDistance() {
        assertEquals(0, HistoryMath.replayRows(0f, 40f, 8))
        assertEquals(2, HistoryMath.replayRows(95f, 40f, 8))
        assertEquals(8, HistoryMath.replayRows(4000f, 40f, 8))
        assertEquals(0, HistoryMath.replayRows(10f, 0f, 8))
    }
}
