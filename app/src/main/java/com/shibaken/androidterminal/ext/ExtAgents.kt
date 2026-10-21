package com.shibaken.androidterminal.ext

/** 拡張生成に使えるホスト側 AI CLI のプリセット(純ロジック)。プロンプトは stdin で渡す。 */
object ExtAgents {
    enum class Kind(val label: String, val command: String?) {
        CLAUDE("Claude", "claude -p"),
        CODEX("Codex", "codex exec --skip-git-repo-check -"),
        CUSTOM("Other", null),
    }

    /** [cmd] が完全一致するプリセット、無ければ [Kind.CUSTOM]。 */
    fun kindOf(cmd: String): Kind = Kind.entries.firstOrNull { it.command != null && it.command == cmd.trim() } ?: Kind.CUSTOM
}
