package com.shibaken.androidterminal.data

/** Pure color model (ARGB ints, no Android/Compose deps) so it can be unit-tested. */
data class ColorPalette(
    val background: Int, val surface: Int, val raised: Int, val surfaceHighest: Int,
    val accent: Int, val secondary: Int, val warning: Int, val error: Int, val errorContainer: Int,
    val text: Int, val muted: Int, val border: Int, val ink: Int,
    /** Three aurora gradient stops (after [background]). */
    val aurora1: Int, val aurora2: Int, val aurora3: Int,
    val terminalForeground: Int, val terminalBackground: Int, val terminalCursor: Int,
    /** 16 ANSI colors. */
    val ansi: List<Int>,
)

data class ColorPreset(val id: String, val name: String, val palette: ColorPalette)

object ColorProfiles {
    const val DEFAULT_ID = "cyan_veil"
    const val CUSTOM_ID = "custom"

    private fun rgb(v: Int) = v or (0xFF shl 24)

    private fun make(
        bg: Int, surface: Int, raised: Int, highest: Int, accent: Int, secondary: Int, warning: Int, error: Int,
        errorContainer: Int, text: Int, muted: Int, border: Int, ink: Int,
        a1: Int, a2: Int, a3: Int, ansi: List<Int>,
    ) = ColorPalette(
        rgb(bg), rgb(surface), rgb(raised), rgb(highest), rgb(accent), rgb(secondary), rgb(warning), rgb(error),
        rgb(errorContainer), rgb(text), rgb(muted), rgb(border), rgb(ink), rgb(a1), rgb(a2), rgb(a3),
        rgb(text), rgb(bg), rgb(accent), ansi.map(::rgb),
    )

    val CyanVeil = make(
        0x071216, 0x102329, 0x173239, 0x204049, 0x64F4E5, 0x9CF6C9, 0xFFD18A, 0xFF8497,
        0x432B34, 0xE4F7F6, 0x96B2B8, 0x2B4B52, 0x052629,
        0x0C272F, 0x27636B, 0x122932,
        listOf(
            0x071216, 0xE57084, 0x86DDB4, 0xE9BB78, 0x7EBBDA, 0xBDA6E8, 0x50CFC4, 0xC7DDDD,
            0x57777F, 0xFF8497, 0x9CF6C9, 0xFFD18A, 0xA5D8F3, 0xD7BEFF, 0x64F4E5, 0xE4F7F6,
        ),
    )

    private val Violet = make(
        0x1A1625, 0x241E33, 0x302845, 0x3B3256, 0xBD93F9, 0x50FA7B, 0xF1FA8C, 0xFF6E6E,
        0x442433, 0xF2EEFF, 0xA59BC4, 0x3F3659, 0x1D1033,
        0x221A38, 0x4A3A7A, 0x261D3F,
        listOf(
            0x1A1625, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
            0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
        ),
    )

    private val Frost = make(
        0x20262F, 0x2A323E, 0x363F4E, 0x414B5C, 0x88C0D0, 0xA3BE8C, 0xEBCB8B, 0xE08A94,
        0x442E36, 0xE5E9F0, 0x9AA5B8, 0x434C5E, 0x1B2A30,
        0x262E3A, 0x3E5A6E, 0x2A3441,
        listOf(
            0x20262F, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x4C566A, 0xD6747E, 0xB5D19F, 0xF0D79E, 0x8FB5D6, 0xC79FC0, 0x8FBCBB, 0xECEFF4,
        ),
    )

    private val Ember = make(
        0x1D1A17, 0x2A2521, 0x38322C, 0x453E37, 0xFE8019, 0xB8BB26, 0xFABD2F, 0xFF6B5A,
        0x4A2622, 0xEBDBB2, 0xA89984, 0x504841, 0x2B1600,
        0x2A1F16, 0x6B3F1E, 0x33231A,
        listOf(
            0x1D1A17, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
            0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
        ),
    )

    private val Solar = make(
        0x00252E, 0x073642, 0x0F4553, 0x18535F, 0x2AA198, 0x9DB000, 0xD9A520, 0xF2605D,
        0x3F2A2D, 0xEEE8D5, 0x93A1A1, 0x1E5461, 0x002B36,
        0x04303B, 0x1E6A72, 0x073642,
        listOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ),
    )

    val presets: List<ColorPreset> = listOf(
        ColorPreset(DEFAULT_ID, "Cyan Veil", CyanVeil),
        ColorPreset("violet", "Violet", Violet),
        ColorPreset("frost", "Frost", Frost),
        ColorPreset("ember", "Ember", Ember),
        ColorPreset("solar", "Solar", Solar),
    )

    fun preset(id: String?): ColorPreset = presets.firstOrNull { it.id == id } ?: presets.first()

    /** Linear blend of two ARGB colors (alpha forced opaque). t=0 -> a, t=1 -> b. */
    /** Relative luminance (sRGB, 0..1). */
    fun luminance(c: Int): Float {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= .03928) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4) }
        return (.2126 * ch(c shr 16 and 0xFF) + .7152 * ch(c shr 8 and 0xFF) + .0722 * ch(c and 0xFF)).toFloat()
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return rgb((ch(16) shl 16) or (ch(8) shl 8) or ch(0))
    }

    /** "#RRGGBB" (alpha dropped). */
    fun toHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    /** Accepts "#RRGGBB" / "RRGGBB" (also 3-digit); returns an opaque ARGB int or null. */
    fun parseHex(text: String?): Int? {
        var s = text?.trim()?.removePrefix("#") ?: return null
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        if (s.length != 6) return null
        val v = s.toIntOrNull(16) ?: return null
        return rgb(v)
    }

    /** Derives a full palette from a base preset plus background/accent/text/secondary overrides. */
    fun custom(
        base: ColorPalette, background: Int? = null, accent: Int? = null, text: Int? = null, secondary: Int? = null,
    ): ColorPalette {
        val bg = rgb((background ?: base.background) and 0xFFFFFF)
        val ac = rgb((accent ?: base.accent) and 0xFFFFFF)
        val tx = rgb((text ?: base.text) and 0xFFFFFF)
        val sec = rgb((secondary ?: base.secondary) and 0xFFFFFF)
        val err = base.error
        return base.copy(
            background = bg, accent = ac, text = tx, secondary = sec,
            surface = blend(bg, tx, .06f), raised = blend(bg, tx, .11f), surfaceHighest = blend(bg, tx, .16f),
            border = blend(bg, tx, .2f), muted = blend(bg, tx, .62f),
            // Text on accent fills: dark on a light accent, light on a dark one.
            ink = if (luminance(ac) > .4f) blend(bg, 0xFF000000.toInt(), .45f) else blend(tx, 0xFFFFFFFF.toInt(), .5f),
            errorContainer = blend(bg, err, .22f),
            aurora1 = blend(bg, ac, .12f), aurora2 = blend(bg, ac, .32f), aurora3 = blend(bg, ac, .14f),
            terminalForeground = tx, terminalBackground = bg, terminalCursor = ac,
            ansi = base.ansi.toMutableList().also { it[0] = bg },
        )
    }
}

/** What the user picked: a preset id, or "custom" built on [customBase] with optional overrides. */
data class ColorSelection(
    val id: String = ColorProfiles.DEFAULT_ID,
    val customBase: String = ColorProfiles.DEFAULT_ID,
    val background: Int? = null,
    val accent: Int? = null,
    val text: Int? = null,
    val secondary: Int? = null,
) {
    val isCustom: Boolean get() = id == ColorProfiles.CUSTOM_ID

    fun resolve(): ColorPalette {
        val base = ColorProfiles.preset(customBase).palette
        return if (isCustom) ColorProfiles.custom(base, background, accent, text, secondary)
        else ColorProfiles.preset(id).palette
    }

    /** Switches to custom, starting from the currently resolved colors of this selection. */
    fun toCustom(): ColorSelection {
        if (isCustom) return this
        val base = ColorProfiles.preset(id)
        val p = base.palette
        return ColorSelection(ColorProfiles.CUSTOM_ID, base.id, p.background, p.accent, p.text, p.secondary)
    }

    /** "id;base;bg;accent;text;secondary" with empty fields for null. */
    fun encode(): String = listOf(
        id, customBase, background?.let(ColorProfiles::toHex) ?: "", accent?.let(ColorProfiles::toHex) ?: "",
        text?.let(ColorProfiles::toHex) ?: "", secondary?.let(ColorProfiles::toHex) ?: "",
    ).joinToString(";")

    companion object {
        /** Missing or invalid data falls back to Cyan Veil. */
        fun decode(s: String?): ColorSelection {
            val f = s?.split(";") ?: return ColorSelection()
            if (f.size != 6) return ColorSelection()
            val known = ColorProfiles.presets.map { it.id }
            val id = f[0].takeIf { it == ColorProfiles.CUSTOM_ID || it in known } ?: return ColorSelection()
            val base = f[1].takeIf { it in known } ?: ColorProfiles.DEFAULT_ID
            return ColorSelection(id, base, ColorProfiles.parseHex(f[2]), ColorProfiles.parseHex(f[3]),
                ColorProfiles.parseHex(f[4]), ColorProfiles.parseHex(f[5]))
        }
    }
}
