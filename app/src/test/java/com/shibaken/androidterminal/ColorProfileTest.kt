package com.shibaken.androidterminal

import com.shibaken.androidterminal.data.ColorProfiles
import com.shibaken.androidterminal.data.ColorSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorProfileTest {
    private fun argb(v: Long) = (0xFF000000L or v).toInt()

    @Test fun cyanVeilMatchesOriginalValues() {
        val p = ColorProfiles.preset("cyan_veil").palette
        assertEquals(argb(0x071216), p.background)
        assertEquals(argb(0x64F4E5), p.accent)
        assertEquals(argb(0x9CF6C9), p.secondary)
        assertEquals(argb(0xFFD18A), p.warning)
        assertEquals(argb(0xFF8497), p.error)
        assertEquals(argb(0xE4F7F6), p.text)
        assertEquals(argb(0x204049), p.surfaceHighest)
        assertEquals(argb(0x432B34), p.errorContainer)
        assertEquals(argb(0x0C272F), p.aurora1)
        assertEquals(argb(0x27636B), p.aurora2)
        assertEquals(argb(0x122932), p.aurora3)
        assertEquals(argb(0xE4F7F6), p.terminalForeground)
        assertEquals(argb(0x64F4E5), p.terminalCursor)
        assertEquals(16, p.ansi.size)
        assertEquals(argb(0xE57084), p.ansi[1])
        assertTrue(ColorProfiles.presets.all { it.palette.ansi.size == 16 })
    }

    @Test fun customDerivesTerminalColors() {
        val sel = ColorSelection("custom", "frost", argb(0x101010), argb(0xFF0000), argb(0xEEEEEE), null)
        val p = sel.resolve()
        val base = ColorProfiles.preset("frost").palette
        assertEquals(argb(0xEEEEEE), p.terminalForeground)
        assertEquals(argb(0x101010), p.terminalBackground)
        assertEquals(argb(0xFF0000), p.terminalCursor)
        assertEquals(argb(0x101010), p.ansi[0])
        assertEquals(base.ansi[1], p.ansi[1])
        assertEquals(base.secondary, p.secondary)
    }

    @Test fun toCustomStartsFromPreset() {
        val c = ColorSelection("ember", "ember").toCustom()
        assertTrue(c.isCustom)
        assertEquals("ember", c.customBase)
        assertEquals(ColorProfiles.preset("ember").palette.accent, c.resolve().accent)
    }

    @Test fun serializationRoundTrip() {
        val sel = ColorSelection("custom", "violet", argb(0x123456), argb(0xABCDEF), null, argb(0x00FF00))
        assertEquals(sel, ColorSelection.decode(sel.encode()))
        val preset = ColorSelection("solar", "solar")
        assertEquals(preset, ColorSelection.decode(preset.encode()))
    }

    @Test fun invalidDataFallsBack() {
        assertEquals(ColorSelection(), ColorSelection.decode(null))
        assertEquals(ColorSelection(), ColorSelection.decode("garbage"))
        assertEquals(ColorSelection(), ColorSelection.decode("nope;cyan_veil;;;;"))
        val d = ColorSelection.decode("custom;bogus;zzz;#12;;")
        assertEquals("cyan_veil", d.customBase)
        assertNull(d.background)
        assertNull(ColorProfiles.parseHex("#12345"))
        assertEquals(argb(0xAABBCC), ColorProfiles.parseHex("abc"))
        assertEquals("#64F4E5", ColorProfiles.toHex(argb(0x64F4E5)))
    }
}
