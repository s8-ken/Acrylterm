package com.shibaken.androidterminal.ui

/** One quick-reply chip for a pane that is waiting for the user. */
data class QuickChoice(
    val kind: Kind,
    /** Short label on the chip ("1", "y"); empty for icon-only kinds. */
    val label: String,
    /** Spoken / accessibility description. */
    val description: String,
    /** Text to type (before [keys]). */
    val text: String? = null,
    /** herdr key names sent after [text]. */
    val keys: List<String> = emptyList(),
) {
    enum class Kind { ENTER, OPTION, ESC }
}

/** Derives chips from the last lines of a blocked agent's pane. */
object QuickReplies {
    val ENTER = QuickChoice(QuickChoice.Kind.ENTER, "", "承認(Enter)", keys = listOf("enter"))
    val ESC = QuickChoice(QuickChoice.Kind.ESC, "", "キャンセル(Esc)", keys = listOf("esc"))

    private val NUMBERED = Regex("""^\s*[❯>›»▶→*●○]?\s*(\d{1,2})[.)]\s+(\S.*?)\s*$""")
    private val YES_NO = Regex("""[(\[]\s*([yY])\s*/\s*([nN])\s*[)\]]""")
    private const val MAX_OPTIONS = 5

    fun fallback(): List<QuickChoice> = listOf(ENTER, ESC)

    private const val BORDERS = "│┃┆┊|╎╏ \t"
    private const val CONTINUATION_INDENT = 4

    /** Drops box-drawing borders and surrounding blanks from both ends of a line. */
    internal fun stripBorders(line: String): String = line.trim { it in BORDERS }

    private fun indentAfterLeftBorder(line: String): Int {
        val body = line.trimStart { it in "│┃┆┊|╎╏" }
        return body.takeWhile { it == ' ' }.length
    }

    fun parse(text: String): List<QuickChoice> {
        // (stripped text, indent of the raw line inside its left border); border-only / blank lines vanish.
        val lines = text.lines().map { stripBorders(it) to indentAfterLeftBorder(it) }.filter { it.first.isNotEmpty() }
        val out = ArrayList<QuickChoice>()
        out += ENTER

        // Last contiguous block of "N. label" lines (wrapped, deeply indented continuation lines tolerated).
        val options = ArrayList<Pair<Int, String>>()
        var i = lines.lastIndex
        while (i >= 0) {
            val m = NUMBERED.find(lines[i].first)
            if (m != null) {
                options.add(0, m.groupValues[1].toInt() to m.groupValues[2])
            } else if (options.isNotEmpty()) {
                if (lines[i].second < CONTINUATION_INDENT) break
            }
            i--
        }
        // A stray earlier "1." directly above the real list: start from the last "1".
        val start = options.indexOfLast { it.first == 1 }
        val seq = if (start < 0) emptyList() else options.drop(start).withIndex().takeWhile { (idx, p) -> p.first == idx + 1 }.map { it.value }
        if (seq.size >= 2) {
            seq.take(MAX_OPTIONS).forEach { (n, label) ->
                out += QuickChoice(QuickChoice.Kind.OPTION, n.toString(), "$n. $label", text = n.toString())
            }
        } else if (lines.takeLast(6).any { YES_NO.containsMatchIn(it.first) }) {
            out += QuickChoice(QuickChoice.Kind.OPTION, "y", "はい(y)", text = "y", keys = listOf("enter"))
            out += QuickChoice(QuickChoice.Kind.OPTION, "n", "いいえ(n)", text = "n", keys = listOf("enter"))
        }
        out += ESC
        return out
    }
}
