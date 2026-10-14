package com.shibaken.androidterminal.terminal

/** Sticky modifiers shared by the toolbar and terminal. A quick second tap holds the key. */
class Modifiers(private val onChange: () -> Unit) {
    private val control = StickyKey()
    private val alternate = StickyKey()
    val ctrl: Boolean get() = control.active
    val alt: Boolean get() = alternate.active
    val ctrlLocked: Boolean get() = control.locked
    val altLocked: Boolean get() = alternate.locked

    fun tapCtrl(nowMillis: Long, doubleTapTimeoutMillis: Long) {
        alternate.forgetTap()
        control.tap(nowMillis, doubleTapTimeoutMillis)
        onChange()
    }

    fun tapAlt(nowMillis: Long, doubleTapTimeoutMillis: Long) {
        control.forgetTap()
        alternate.tap(nowMillis, doubleTapTimeoutMillis)
        onChange()
    }

    fun consume() {
        val ctrlChanged = control.consume()
        val altChanged = alternate.consume()
        if (ctrlChanged || altChanged) onChange()
    }

    private class StickyKey {
        var active = false
            private set
        var locked = false
            private set
        private var lastTapMillis: Long? = null

        fun forgetTap() { lastTapMillis = null }

        fun tap(nowMillis: Long, doubleTapTimeoutMillis: Long) {
            val elapsed = lastTapMillis?.let { nowMillis - it }
            when {
                locked -> { active = false; locked = false; forgetTap() }
                active && elapsed != null && elapsed in 0..doubleTapTimeoutMillis -> {
                    locked = true
                    forgetTap()
                }
                active -> { active = false; forgetTap() }
                else -> { active = true; lastTapMillis = nowMillis }
            }
        }

        fun consume(): Boolean {
            forgetTap()
            if (!active || locked) return false
            active = false
            return true
        }
    }
}
