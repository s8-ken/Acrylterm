package com.shibaken.androidterminal

import com.shibaken.androidterminal.herdr.FractionRect
import com.shibaken.androidterminal.terminal.PaneGestureMath
import com.shibaken.androidterminal.ui.PaneLogic
import com.shibaken.androidterminal.ui.QuickChoice
import com.shibaken.androidterminal.ui.QuickReplies
import com.shibaken.androidterminal.ui.SplitGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneUiLogicTest {
    // --- quick replies ---
    @Test fun numberedOptions() {
        val text = """
            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for this command
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()
        val c = QuickReplies.parse(text)
        assertEquals(listOf("", "1", "2", "3", ""), c.map { it.label })
        assertEquals(QuickChoice.Kind.ENTER, c.first().kind)
        assertEquals(QuickChoice.Kind.ESC, c.last().kind)
        assertEquals("1", c[1].text)
        assertTrue(c[1].keys.isEmpty())
    }

    @Test fun yesNoPrompt() {
        val c = QuickReplies.parse("Overwrite file? [Y/n]")
        assertEquals(listOf("", "y", "n", ""), c.map { it.label })
        assertEquals(listOf("enter"), c[1].keys)
        assertEquals(2, QuickReplies.parse("Continue? (y/n)").count { it.kind == QuickChoice.Kind.OPTION })
    }

    @Test fun fallbackWhenNothingRecognised() {
        val c = QuickReplies.parse("some output\nwaiting...\n")
        assertEquals(listOf(QuickChoice.Kind.ENTER, QuickChoice.Kind.ESC), c.map { it.kind })
    }

    @Test fun ignoresNumberedListNotStartingAtOne() {
        val c = QuickReplies.parse("3. foo\n4. bar\nRun it?")
        assertEquals(listOf(QuickChoice.Kind.ENTER, QuickChoice.Kind.ESC), c.map { it.kind })
    }

    @Test fun usesLastNumberedBlockOnly() {
        val c = QuickReplies.parse("1. old\n2. old\nsome text\n1. Yes\n2. No")
        assertEquals(listOf("", "1", "2", ""), c.map { it.label })
    }

    @Test fun borderedPrompt() {
        val text = """
            │ Do you want to proceed?                       │
            │ ❯ 1. Yes                                      │
            │   2. Yes, and don't ask again                 │
            │   3. No                                       │
            ╰───────────────────────────────────────────────╯
        """.trimIndent()
        val c = QuickReplies.parse(text)
        assertEquals(listOf("", "1", "2", "3", ""), c.map { it.label })
        assertTrue(c.none { it.description.contains("│") })
        assertEquals("1. Yes", c[1].description)
    }

    @Test fun strayEarlierOneIsIgnored() {
        val c = QuickReplies.parse("1. earlier step\nSome explanation\n❯ 1. Yes\n  2. No")
        assertEquals(listOf("", "1", "2", ""), c.map { it.label })
        val adjacent = QuickReplies.parse("1. stray\n❯ 1. Yes\n  2. No")
        assertEquals(listOf("", "1", "2", ""), adjacent.map { it.label })
        assertEquals("1. Yes", adjacent[1].description)
    }

    // --- narrow fallback ---
    @Test fun narrowFallback() {
        val half = listOf(FractionRect(0f, 0f, 0.5f, 1f), FractionRect(0.5f, 0f, 0.5f, 1f))
        // 1080px wide, 20px cells => 27 columns each: too narrow
        assertTrue(PaneLogic.needsSingleMode(half, 1080f, 20f))
        // 2400px => 60 columns each: fine
        assertFalse(PaneLogic.needsSingleMode(half, 2400f, 20f))
        assertFalse(PaneLogic.needsSingleMode(listOf(FractionRect(0f, 0f, 1f, 1f)), 300f, 20f))
        assertFalse(PaneLogic.needsSingleMode(half, 1080f, 0f))
        // stacked panes keep full width
        val stacked = listOf(FractionRect(0f, 0f, 1f, 0.5f), FractionRect(0f, 0.5f, 1f, 0.5f))
        assertFalse(PaneLogic.needsSingleMode(stacked, 1080f, 10f))
    }

    // --- two-finger swipe ---
    @Test fun twoFingerSwipeClassification() {
        assertEquals(1, PaneGestureMath.twoFingerSwipe(-200f, 10f, 300f, 310f, 100f))
        assertEquals(-1, PaneGestureMath.twoFingerSwipe(200f, -20f, 300f, 290f, 100f))
        assertEquals(0, PaneGestureMath.twoFingerSwipe(50f, 0f, 300f, 300f, 100f)) // too short
        assertEquals(0, PaneGestureMath.twoFingerSwipe(-200f, 150f, 300f, 300f, 100f)) // diagonal
        assertEquals(0, PaneGestureMath.twoFingerSwipe(-200f, 0f, 300f, 600f, 100f)) // pinch out
    }

    // --- one-finger pane swipe ---
    @Test fun oneFingerPaneSwipeClassification() {
        assertEquals(1, PaneGestureMath.oneFingerPaneSwipe(-200f, 10f, 100f))
        assertEquals(-1, PaneGestureMath.oneFingerPaneSwipe(200f, -20f, 100f))
        assertEquals(0, PaneGestureMath.oneFingerPaneSwipe(50f, 0f, 100f)) // too short
        assertEquals(0, PaneGestureMath.oneFingerPaneSwipe(-200f, 150f, 100f)) // mostly vertical
    }

    // --- pane order ---
    @Test fun paneOrderIsReadingOrder() {
        val rects = mapOf(
            "br" to FractionRect(0.5f, 0.5f, 0.5f, 0.5f),
            "tl" to FractionRect(0f, 0f, 0.5f, 0.5f),
            "bl" to FractionRect(0f, 0.5f, 0.5f, 0.5f),
            "tr" to FractionRect(0.5f, 0f, 0.5f, 0.5f),
        )
        assertEquals(listOf("tl", "tr", "bl", "br"), PaneLogic.paneOrder(rects))
    }

    // --- divider math ---
    @Test fun remapMovesPanesInsideSplit() {
        val split = FractionRect(0f, 0f, 1f, 1f)
        val left = FractionRect(0f, 0f, 0.5f, 1f)
        val right = FractionRect(0.5f, 0f, 0.5f, 1f)
        val l = PaneLogic.remap(left, split, true, 0.5f, 0.3f)
        val r = PaneLogic.remap(right, split, true, 0.5f, 0.3f)
        assertEquals(0.3f, l.width, 1e-4f)
        assertEquals(0.3f, r.x, 1e-4f)
        assertEquals(0.7f, r.width, 1e-4f)
        // rects outside the split are untouched
        val outside = FractionRect(0f, 0.5f, 1f, 0.5f)
        val sub = FractionRect(0f, 0f, 1f, 0.5f)
        assertEquals(outside, PaneLogic.remap(outside, sub, false, 0.5f, 0.2f))
    }

    @Test fun remapVerticalSplit() {
        val split = FractionRect(0f, 0f, 1f, 1f)
        val top = FractionRect(0f, 0f, 1f, 0.5f)
        val bottom = FractionRect(0f, 0.5f, 1f, 0.5f)
        assertEquals(0.8f, PaneLogic.remap(top, split, false, 0.5f, 0.8f).height, 1e-4f)
        assertEquals(0.8f, PaneLogic.remap(bottom, split, false, 0.5f, 0.8f).y, 1e-4f)
    }

    @Test fun splitPathFromGeometry() {
        // root: side by side; right half is split top/bottom; bottom-right is split side by side.
        val root = SplitGeom(FractionRect(0f, 0f, 1f, 1f), true, 0.5f)
        val rightHalf = SplitGeom(FractionRect(0.5f, 0f, 0.5f, 1f), false, 0.5f)
        val bottomRight = SplitGeom(FractionRect(0.5f, 0.5f, 0.5f, 0.5f), true, 0.5f)
        val splits = listOf(bottomRight, root, rightHalf)
        assertEquals(emptyList<Boolean>(), PaneLogic.splitPath(splits, 1))
        assertEquals(listOf(true), PaneLogic.splitPath(splits, 2))
        assertEquals(listOf(true, true), PaneLogic.splitPath(splits, 0))
        val leftSplit = SplitGeom(FractionRect(0f, 0f, 0.5f, 1f), false, 0.5f)
        assertEquals(listOf(false), PaneLogic.splitPath(listOf(root, leftSplit), 1))
    }

    @Test fun ratioClamp() {
        assertEquals(0.1f, PaneLogic.clampRatio(0f), 0f)
        assertEquals(0.9f, PaneLogic.clampRatio(2f), 0f)
    }
}
