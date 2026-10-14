package com.shibaken.androidterminal

import com.shibaken.androidterminal.ui.FlickDirection
import com.shibaken.androidterminal.ui.FlickGesture
import org.junit.Assert.*
import org.junit.Test

class FlickGestureTest {
    @Test fun smallFingerMovementRemainsATap() {
        val gesture = FlickGesture(12f)
        assertNull(gesture.direction(4f, -8f))
        assertFalse(gesture.hasFlicked)
    }

    @Test fun allDirectionsUseTheDominantAxis() {
        val gesture = FlickGesture(12f)
        assertEquals(FlickDirection.LEFT, gesture.direction(-20f, 8f))
        assertEquals(FlickDirection.RIGHT, gesture.direction(20f, -8f))
        assertEquals(FlickDirection.UP, gesture.direction(8f, -20f))
        assertEquals(FlickDirection.DOWN, gesture.direction(-8f, 20f))
        assertTrue(gesture.hasFlicked)
    }

    @Test fun returningToCenterStopsRepeatsWithoutSendingEnter() {
        val gesture = FlickGesture(12f)
        assertEquals(FlickDirection.RIGHT, gesture.direction(12f, 0f))
        assertNull(gesture.direction(0f, 0f))
        assertTrue(gesture.hasFlicked)
    }

    @Test fun newGestureCanTapAfterAPreviousFlick() {
        FlickGesture(12f).direction(30f, 0f)
        val next = FlickGesture(12f)
        assertNull(next.direction(0f, 0f))
        assertFalse(next.hasFlicked)
    }
}
