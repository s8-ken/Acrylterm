package com.shibaken.androidterminal

import com.shibaken.androidterminal.terminal.FlingDecay
import com.shibaken.androidterminal.terminal.ScrollAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollMathTest {
    @Test fun accumulatesSubRowDistance() {
        val a = ScrollAccumulator(40f)
        assertEquals(0, a.add(15f))
        assertEquals(0, a.add(20f))
        assertEquals(1, a.add(10f))
        assertEquals(5f, a.remainder, 0.001f)
    }

    @Test fun negativeDirectionAndMultiRow() {
        val a = ScrollAccumulator(40f)
        assertEquals(-3, a.add(-130f))
        assertEquals(-10f, a.remainder, 0.001f)
        assertEquals(-1, a.add(-30f))
    }

    @Test fun totalRowsMatchTotalDistanceRegardlessOfChunking() {
        val a = ScrollAccumulator(37f)
        var rows = 0
        repeat(100) { rows += a.add(7.3f) }
        assertEquals((730f / 37f).toInt(), rows)
    }

    @Test fun maxRowsCapsAndDropsExcess() {
        val a = ScrollAccumulator(10f)
        assertEquals(4, a.add(200f, maxRows = 4))
        assertEquals(0f, a.remainder, 0.001f)
        assertEquals(-4, a.add(-95f, maxRows = 4))
    }

    @Test fun flingDistanceIsFrameRateIndependent() {
        fun total(stepMs: Long): Float {
            val f = FlingDecay(3000f)
            var d = 0f
            var t = 0L
            while (!f.finished && t < 10_000) { d += f.advance(stepMs); t += stepMs }
            return d
        }
        assertEquals(total(8), total(33), 15f)
    }

    @Test fun flingDecaysMonotonicallyAndFinishes() {
        val f = FlingDecay(-2000f)
        var last = Float.MAX_VALUE
        var frames = 0
        while (!f.finished) {
            val d = f.advance(16)
            assertTrue(d < 0f)
            assertTrue(Math.abs(d) <= last)
            last = Math.abs(d); frames++
            assertTrue(frames < 1000)
        }
        assertEquals(0f, f.advance(16), 0f)
    }
}
