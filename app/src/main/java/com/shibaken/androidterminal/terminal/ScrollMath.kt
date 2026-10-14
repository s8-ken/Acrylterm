package com.shibaken.androidterminal.terminal

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns a stream of pixel distances into whole-row steps, carrying the sub-row remainder.
 * Positive distance = content moving up (finger moving up, wheel-down / scroll toward newer lines).
 */
class ScrollAccumulator(var rowHeight: Float) {
    /** Sub-row distance not yet turned into a row step; |remainder| < rowHeight. */
    var remainder = 0f
        private set

    /** Adds [dy] px and returns the signed whole rows to emit, at most [maxRows] in magnitude (excess is dropped). */
    fun add(dy: Float, maxRows: Int = Int.MAX_VALUE): Int {
        if (rowHeight <= 0f) return 0
        remainder += dy
        var rows = (remainder / rowHeight).toInt()
        remainder -= rows * rowHeight
        if (abs(rows) > maxRows) rows = if (rows > 0) maxRows else -maxRows
        return rows
    }

    fun reset() { remainder = 0f }
}

/** Exponential-decay fling: v(t) = v0 * e^(-k t). Distances are exact integrals, so frame timing does not change the total. */
class FlingDecay(velocityPxPerSec: Float, private val friction: Float = DEFAULT_FRICTION, private val minVelocity: Float = MIN_VELOCITY) {
    var velocity = velocityPxPerSec
        private set
    val finished: Boolean get() = abs(velocity) < minVelocity

    /** Advances by [dtMs] and returns the distance travelled in px (signed). */
    fun advance(dtMs: Long): Float {
        if (finished) return 0f
        val t = dtMs.coerceAtLeast(0L) / 1000f
        val decay = exp(-friction * t)
        val dist = velocity * (1f - decay) / friction
        velocity *= decay
        return dist
    }

    /** Total distance the fling would still cover. */
    val remainingDistance: Float get() = velocity / friction

    companion object {
        const val DEFAULT_FRICTION = 3.2f
        const val MIN_VELOCITY = 40f
    }
}
