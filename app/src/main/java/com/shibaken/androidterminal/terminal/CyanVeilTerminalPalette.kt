package com.shibaken.androidterminal.terminal

import com.shibaken.androidterminal.data.ColorPalette
import com.shibaken.androidterminal.data.ColorProfiles
import com.termux.terminal.TerminalColors
import java.util.Properties

/** Installed before creating sessions and on apply. Remote OSC palette overrides continue to work. */
object CyanVeilTerminalPalette {
    fun install(palette: ColorPalette = ColorProfiles.CyanVeil) {
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
            setProperty("foreground", ColorProfiles.toHex(palette.terminalForeground))
            setProperty("background", ColorProfiles.toHex(palette.terminalBackground))
            setProperty("cursor", ColorProfiles.toHex(palette.terminalCursor))
            palette.ansi.forEachIndexed { i, color -> setProperty("color$i", ColorProfiles.toHex(color)) }
        })
    }
}
