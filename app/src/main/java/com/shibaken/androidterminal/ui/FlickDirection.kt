package com.shibaken.androidterminal.ui

import kotlin.math.abs

internal enum class FlickDirection { LEFT, DOWN, UP, RIGHT }

/** Returning to the center stops repeats, but must never turn a flick into Enter. */
internal class FlickGesture(private val threshold: Float) {
    var hasFlicked = false
        private set

    fun direction(dx: Float, dy: Float): FlickDirection? {
        if (maxOf(abs(dx), abs(dy)) < threshold) return null
        hasFlicked = true
        return if (abs(dx) > abs(dy)) {
            if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
        } else {
            if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
        }
    }
}
