package com.termux.terminal;

/** Serializes a screen/transcript row back to ANSI text (colours and attributes kept), for replaying into another emulator. */
public final class AnsiRows {
    private AnsiRows() {}

    /** Row in external coordinates (0 = top of the screen). Trailing default-styled blanks are dropped. */
    public static String row(TerminalEmulator emu, int externalRow) {
        TerminalBuffer buf = emu.getScreen();
        TerminalRow line = buf.mLines[buf.externalToInternalRow(externalRow)];
        final int columns = emu.mColumns;
        final int used = line.getSpaceUsed();
        final char[] text = line.mText;
        StringBuilder sb = new StringBuilder(columns + 16);
        long current = TextStyle.NORMAL;
        int column = 0;
        int i = 0;
        int pendingBlanks = 0; // default-styled blanks are only emitted if something visible follows
        while (i < used && column < columns) {
            char c = text[i];
            int cp = c;
            int n = 1;
            if (Character.isHighSurrogate(c) && i + 1 < used) { cp = Character.toCodePoint(c, text[i + 1]); n = 2; }
            int w = WcWidth.width(cp);
            if (w <= 0) { // combining: attaches to the previous cell
                if (pendingBlanks > 0) {
                    if (current != TextStyle.NORMAL) { sb.append("\u001b[0m"); current = TextStyle.NORMAL; }
                    for (; pendingBlanks > 0; pendingBlanks--) sb.append(' ');
                }
                sb.append(text, i, n);
                i += n;
                continue;
            }
            long style = line.getStyle(column);
            if (cp == ' ' && style == TextStyle.NORMAL) {
                pendingBlanks += w;
            } else {
                if (pendingBlanks > 0) {
                    if (current != TextStyle.NORMAL) { sb.append("\u001b[0m"); current = TextStyle.NORMAL; }
                    for (; pendingBlanks > 0; pendingBlanks--) sb.append(' ');
                }
                if (style != current) { appendSgr(sb, style); current = style; }
                sb.append(text, i, n);
            }
            column += w;
            i += n;
        }
        if (current != TextStyle.NORMAL) sb.append("\u001b[0m");
        return sb.toString();
    }

    private static void appendSgr(StringBuilder sb, long style) {
        sb.append("\u001b[0");
        int effect = TextStyle.decodeEffect(style);
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0) sb.append(";1");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) sb.append(";2");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) sb.append(";3");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) sb.append(";4");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0) sb.append(";5");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) sb.append(";7");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) sb.append(";8");
        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) sb.append(";9");
        appendColor(sb, TextStyle.decodeForeColor(style), 38, TextStyle.COLOR_INDEX_FOREGROUND);
        appendColor(sb, TextStyle.decodeBackColor(style), 48, TextStyle.COLOR_INDEX_BACKGROUND);
        sb.append('m');
    }

    private static void appendColor(StringBuilder sb, int color, int base, int defaultIndex) {
        if ((color & 0xff000000) == 0xff000000) {
            sb.append(';').append(base).append(";2;").append((color >> 16) & 0xff).append(';').append((color >> 8) & 0xff).append(';').append(color & 0xff);
        } else if (color != defaultIndex && color >= 0 && color < 256) {
            sb.append(';').append(base).append(";5;").append(color);
        }
    }
}
