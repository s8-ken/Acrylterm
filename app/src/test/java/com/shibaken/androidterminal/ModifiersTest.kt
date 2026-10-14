package com.shibaken.androidterminal

import com.shibaken.androidterminal.terminal.Modifiers
import org.junit.Assert.*
import org.junit.Test

class ModifiersTest {
    private val timeout = 300L

    private fun forEachKey(check: (Modifiers, (Long) -> Unit, () -> Boolean, () -> Boolean) -> Unit) {
        for (ctrl in listOf(true, false)) {
            val modifiers = Modifiers {}
            check(modifiers,
                { time -> if (ctrl) modifiers.tapCtrl(time, timeout) else modifiers.tapAlt(time, timeout) },
                { if (ctrl) modifiers.ctrl else modifiers.alt },
                { if (ctrl) modifiers.ctrlLocked else modifiers.altLocked })
        }
    }

    @Test fun firstTapAppliesImmediatelyAndIsConsumedOnce() = forEachKey { modifiers, tap, active, held ->
        tap(1_000)
        assertTrue(active())
        assertFalse(held())
        modifiers.consume()
        assertFalse(active())
    }

    @Test fun doubleTapHoldsAcrossInputUntilNextTap() = forEachKey { modifiers, tap, active, held ->
        tap(1_000)
        tap(1_150)
        assertTrue(active())
        assertTrue(held())
        repeat(3) { modifiers.consume() }
        assertTrue(active())
        assertTrue(held())
        tap(1_250)
        assertFalse(active())
        assertFalse(held())
        tap(1_300)
        assertTrue(active())
        assertFalse(held())
    }

    @Test fun slowSecondTapCancelsInsteadOfHolding() = forEachKey { _, tap, active, held ->
        tap(1_000)
        tap(1_301)
        assertFalse(active())
        assertFalse(held())
    }

    @Test fun doubleTapUsesThePlatformTimeoutBoundary() = forEachKey { _, tap, active, held ->
        tap(1_000)
        tap(1_300)
        assertTrue(active())
        assertTrue(held())
    }

    @Test fun inputBetweenTapsStartsANewOneShot() = forEachKey { modifiers, tap, active, held ->
        tap(1_000)
        modifiers.consume()
        tap(1_100)
        assertTrue(active())
        assertFalse(held())
    }

    @Test fun tappingAnotherModifierInterruptsTheDoubleTap() {
        val modifiers = Modifiers {}
        modifiers.tapCtrl(1_000, timeout)
        modifiers.tapAlt(1_050, timeout)
        modifiers.tapCtrl(1_100, timeout)
        assertFalse(modifiers.ctrl)
        assertFalse(modifiers.ctrlLocked)
        assertTrue(modifiers.alt)
        assertFalse(modifiers.altLocked)
    }

    @Test fun heldAndOneShotModifiersCanBeCombined() {
        val modifiers = Modifiers {}
        modifiers.tapCtrl(1_000, timeout)
        modifiers.tapCtrl(1_100, timeout)
        modifiers.tapAlt(1_150, timeout)
        modifiers.consume()
        assertTrue(modifiers.ctrl)
        assertTrue(modifiers.ctrlLocked)
        assertFalse(modifiers.alt)
        modifiers.tapAlt(1_200, timeout)
        modifiers.tapAlt(1_300, timeout)
        modifiers.consume()
        assertTrue(modifiers.ctrlLocked && modifiers.altLocked)
        modifiers.tapCtrl(1_400, timeout)
        assertFalse(modifiers.ctrl)
        assertTrue(modifiers.altLocked)
    }

    @Test fun observersSeeBothActivationAndHoldChanges() {
        val states = mutableListOf<Pair<Boolean, Boolean>>()
        lateinit var modifiers: Modifiers
        modifiers = Modifiers { states += modifiers.ctrl to modifiers.ctrlLocked }
        modifiers.tapCtrl(1_000, timeout)
        modifiers.tapCtrl(1_100, timeout)
        modifiers.consume()
        modifiers.tapCtrl(1_200, timeout)
        assertEquals(listOf(true to false, true to true, false to false), states)
    }
}
