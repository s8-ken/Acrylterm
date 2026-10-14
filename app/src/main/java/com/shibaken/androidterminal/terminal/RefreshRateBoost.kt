package com.shibaken.androidterminal.terminal

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View

/** A display mode reduced to what [RefreshRateBoost.pickBestModeId] needs. */
data class ModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Asks for the display's highest refresh rate while the terminal is being interacted with (scroll, fling,
 * offset animations) and drops the request again after [IDLE_MS] without interaction, to save battery.
 * Call [touch] on every interaction frame (cheap); it only changes window state on the idle/active transitions.
 */
class RefreshRateBoost(private val view: View) {
    private var active = false
    private var lastMs = 0L
    private val idleCheck = object : Runnable {
        override fun run() {
            val remaining = IDLE_MS - (SystemClock.uptimeMillis() - lastMs)
            if (remaining > 0) view.postDelayed(this, remaining) else { active = false; apply(false) }
        }
    }

    fun touch() {
        lastMs = SystemClock.uptimeMillis()
        if (active) return
        active = true
        apply(true)
        view.postDelayed(idleCheck, IDLE_MS)
    }

    /** Drops the request immediately (view detached). */
    fun release() {
        view.removeCallbacks(idleCheck)
        if (active) { active = false; apply(false) }
    }

    private fun apply(on: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val display = view.display ?: return
        val modes = display.supportedModes.map { ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val cur = display.mode
        val best = pickBestModeId(modes, cur.physicalWidth, cur.physicalHeight)
        val window = (generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .firstOrNull { it is Activity } as? Activity)?.window
        if (window != null) {
            val attrs = window.attributes
            val target = if (on) best ?: 0 else 0
            if (attrs.preferredDisplayModeId != target) {
                attrs.preferredDisplayModeId = target
                window.attributes = attrs
            }
        }
        if (Build.VERSION.SDK_INT >= 35) {
            val hz = modes.filter { it.width == cur.physicalWidth && it.height == cur.physicalHeight }.maxOfOrNull { it.refreshRate } ?: 0f
            if (on && hz > 0f) view.setRequestedFrameRate(hz)
            else view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
        }
    }

    companion object {
        const val IDLE_MS = 1000L

        /** Id of the mode with the same resolution as ([width] x [height]) and the highest refresh rate; null if none. */
        fun pickBestModeId(modes: List<ModeInfo>, width: Int, height: Int): Int? =
            modes.filter { it.width == width && it.height == height }.maxByOrNull { it.refreshRate }?.id
    }
}
