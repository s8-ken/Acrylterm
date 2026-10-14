package com.shibaken.androidterminal.ext

/** Lua 拡張の要約。先頭の `--[[veildeck … ]]` ヘッダと静的解析から作る(純ロジック)。 */
data class ExtSummary(
    val summary: String?,
    val usage: List<String>,
    val keys: List<String>,
    val commands: List<String>,
    val triggers: Int,
    val events: List<String>,
    val quickReplies: Int,
    val timers: Int,
) {
    val hasFeatures: Boolean
        get() = keys.isNotEmpty() || commands.isNotEmpty() || triggers > 0 || events.isNotEmpty() || quickReplies > 0 || timers > 0

    /** ユーザーが押すもの(keys.add / command)の数。UI では「マクロ」と呼ぶ。 */
    val macroCount: Int get() = keys.size + commands.size

    /** イベント駆動の処理(term.on_output / on(...) / timer)を持つか。UI では「自動処理」。 */
    val hasAutomation: Boolean get() = triggers > 0 || events.isNotEmpty() || timers > 0

    /** quick_reply.rule を持つか。UI では「返信候補」。 */
    val hasQuickReplies: Boolean get() = quickReplies > 0

    companion object {
        private val HEADER = Regex("""\A\s*--\[\[veildeck[ \t]*\r?\n?(.*?)\]\]""", RegexOption.DOT_MATCHES_ALL)
        private val KEY = Regex("""(?<![A-Za-z0-9_.])keys\s*\.\s*add\s*\(\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""")
        private val COMMAND = Regex("""(?<![A-Za-z0-9_.:])command\s*\(\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""")
        private val EVENT = Regex("""(?<![\w.:])on\s*\(\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)')""")
        private val BLOCK_COMMENT = Regex("""--\[(=*)\[""")
        private val TRIGGER = Regex("""term\s*\.\s*on_output\s*\(""")
        private val QUICK = Regex("""quick_reply\s*\.\s*rule\s*\(""")
        private val TIMER = Regex("""timer\s*\.\s*(?:after|every)\s*\(""")

        fun parse(source: String): ExtSummary {
            var summary: String? = null
            val usage = ArrayList<String>()
            var body = source
            HEADER.find(source)?.let { m ->
                body = source.substring(m.range.last + 1)
                var inUsage = false
                for (raw in m.groupValues[1].lines()) {
                    val line = raw.trim()
                    when {
                        line.startsWith("summary:", ignoreCase = true) -> {
                            summary = line.substring(8).trim().ifEmpty { null }; inUsage = false
                        }
                        line.startsWith("usage:", ignoreCase = true) -> {
                            inUsage = true
                            line.substring(6).trim().takeIf { it.isNotEmpty() }?.let { usage += it.removePrefix("-").trim() }
                        }
                        inUsage && line.isNotEmpty() -> usage += line.removePrefix("-").removePrefix("*").trim()
                    }
                }
                usage.removeAll { it.isEmpty() }
            }
            val code = stripLineComments(body)
            fun names(r: Regex) = r.findAll(code).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
            return ExtSummary(
                summary = summary, usage = usage,
                keys = names(KEY), commands = names(COMMAND),
                triggers = TRIGGER.findAll(code).count(),
                events = names(EVENT).distinct(),
                quickReplies = QUICK.findAll(code).count(),
                timers = TIMER.findAll(code).count(),
            )
        }

        /** `--` 行コメントとブロックコメントを落とす(文字列リテラル内の `--` は残す)。簡易実装。 */
        private fun stripLineComments(src: String): String {
            val sb = StringBuilder()
            var i = 0
            var quote = 0.toChar()
            while (i < src.length) {
                val c = src[i]
                if (quote != 0.toChar()) {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    if (c == quote || c == '\n') quote = 0.toChar()
                    i++
                } else if (c == '"' || c == '\'') {
                    quote = c; sb.append(c); i++
                } else if (c == '-' && src.startsWith("--", i)) {
                    val blk = BLOCK_COMMENT.matchAt(src, i)
                    if (blk != null) {
                        val end = src.indexOf("]${blk.groupValues[1]}]", blk.range.last + 1)
                        i = if (end < 0) src.length else end + blk.groupValues[1].length + 2
                    } else {
                        while (i < src.length && src[i] != '\n') i++
                    }
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
    }
}
