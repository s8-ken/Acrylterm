package com.shibaken.androidterminal.ext

/** クイック返信の選択肢。[text] を送り、続けて [keys](herdr キー名)を送る。どちらも空なら何もしない。 */
data class ExtChoice(val label: String, val text: String?, val keys: List<String> = emptyList())

/** `command(name, fn)` で登録されたコマンド。実行は [ExtEngine.runCommand]。 */
data class ExtCommand(val name: String)

/** `keys.add(label, spec)` で登録された補助キー。実行は [ExtEngine.pressKey](index は [ExtRegistry.keys] の添字)。 */
data class ExtKey(val label: String)

/** `quick_reply.rule(regex, choices)`。照合は [ExtEngine.matchQuickReply]。 */
data class ExtQuickRule(val pattern: String, val choices: List<ExtChoice>) {
    internal val regex: Regex by lazy { Regex(pattern, RegexOption.MULTILINE) }
}

/**
 * 登録内容の不変スナップショット。Lua の値は含まない。
 * [outputTriggerPatterns] が空なら出力監視(OutputWatcher)は不要。
 */
data class ExtRegistry(
    val commands: List<ExtCommand> = emptyList(),
    val keys: List<ExtKey> = emptyList(),
    val quickReplyRules: List<ExtQuickRule> = emptyList(),
    val outputTriggerPatterns: List<String> = emptyList(),
    val eventNames: List<String> = emptyList(),
) {
    val hasOutputTriggers: Boolean get() = outputTriggerPatterns.isNotEmpty()

    companion object {
        val EMPTY = ExtRegistry()
    }
}

/** 読み込むスクリプト 1 本。[name] はエラー表示に使う。 */
data class ExtScript(val name: String, val source: String)

/** [ExtEngine.reload] の結果。[errors] が空なら成功して登録内容が差し替わった。 */
data class ExtLoadResult(val errors: List<String>, val registry: ExtRegistry) {
    val ok: Boolean get() = errors.isEmpty()
}
