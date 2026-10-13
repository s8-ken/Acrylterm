package com.shibaken.androidterminal.terminal

import com.termux.terminal.TerminalColors
import java.util.Properties

/** Installed once before creating sessions. Remote OSC palette overrides continue to work. */
object CyanVeilTerminalPalette {
    fun install() {
        val colors = listOf(
            "#071216", "#E57084", "#86DDB4", "#E9BB78", "#7EBBDA", "#BDA6E8", "#50CFC4", "#C7DDDD",
            "#57777F", "#FF8497", "#9CF6C9", "#FFD18A", "#A5D8F3", "#D7BEFF", "#64F4E5", "#E4F7F6",
        )
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
            setProperty("foreground", "#E4F7F6")
            setProperty("background", "#071216")
            setProperty("cursor", "#64F4E5")
            colors.forEachIndexed { i, color -> setProperty("color$i", color) }
        })
    }
}
