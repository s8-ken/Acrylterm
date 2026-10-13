package com.shibaken.androidterminal.terminal

import kotlin.math.abs

/** Pure gesture classification used by [TerminalCanvasView] (unit-testable). */
object PaneGestureMath {
    /**
     * Two-finger gesture: -1 = previous tab (swipe right), +1 = next tab (swipe left), 0 = not a tab swipe.
     * [dx]/[dy] are the centroid movement since the second finger went down; [startDist]/[curDist] the finger spacing.
     */
    fun twoFingerSwipe(dx: Float, dy: Float, startDist: Float, curDist: Float, thresholdPx: Float): Int {
        if (abs(dx) < thresholdPx) return 0
        if (abs(dx) < 2f * abs(dy)) return 0 // must be mostly horizontal
        if (abs(curDist - startDist) > 0.3f * startDist) return 0 // spacing changing = pinch
        return if (dx < 0) 1 else -1
    }

    /**
     * One-finger gesture in single-pane mode: -1 = previous pane (swipe right), +1 = next pane (swipe left),
     * 0 = not a pane swipe. [dx]/[dy] are the finger movement since touch-down.
     */
    fun oneFingerPaneSwipe(dx: Float, dy: Float, thresholdPx: Float): Int {
        if (abs(dx) < thresholdPx) return 0
        if (abs(dx) < 2f * abs(dy)) return 0 // must be mostly horizontal (vertical = scroll)
        return if (dx < 0) 1 else -1
    }
}
