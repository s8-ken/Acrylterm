package com.shibaken.androidterminal.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.Log

/** Bundled UDEV Gothic (half:full = 1:2, so CJK fills exactly two cells); falls back to the system monospace. */
object TerminalFont {
    @Volatile private var cached: Typeface? = null

    fun get(context: Context): Typeface = cached ?: synchronized(this) {
        cached ?: runCatching { Typeface.createFromAsset(context.applicationContext.assets, "fonts/UDEVGothic-Regular.ttf") }
            .onFailure { Log.w("TerminalFont", "falling back to monospace", it) }
            .getOrDefault(Typeface.MONOSPACE)
            .also { cached = it }
    }
}
