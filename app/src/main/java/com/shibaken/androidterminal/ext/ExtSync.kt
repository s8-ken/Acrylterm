package com.shibaken.androidterminal.ext

import java.util.Base64

/**
 * ホストの `~/.config/acrylterm/` とのやり取りに使うコマンド生成とパースの純ロジック(ユニットテスト対象)。
 * 全コマンドは `sh -c '…'` で包んで実行する想定(ログインシェルが fish 等でも動くように)。
 */
object ExtSync {
    fun newNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun marker(nonce: String) = "--@@FILE:$nonce "
    private const val DIR = "\$HOME/.config/acrylterm"
    private const val LEGACY_DIR = "\$HOME/.config/androidterminal"

    /** 旧ディレクトリだけがあれば新しい名前へ移す(1 回きり。以降は何もしない)。 */
    private const val MIGRATE = "[ -e \"$DIR\" ] || [ ! -d \"$LEGACY_DIR\" ] || mv \"$LEGACY_DIR\" \"$DIR\"; "

    /** Runs [script] under POSIX sh regardless of the user's login shell. */
    fun sh(script: String): String = "sh -c '" + script.replace("'", "'\\''") + "'"

    /** 全 `*.lua` を区切り行付きで出力する。一致なしでも成功(空出力)。 */
    fun fetchCommand(nonce: String): String = sh(
        MIGRATE + "d=\"$DIR\"; for f in \"\$d\"/*.lua; do [ -f \"\$f\" ] || continue; " +
            "printf '\\n${marker(nonce)}%s\\n' \"\${f##*/}\"; cat \"\$f\"; done; true"
    )

    /** 変更検知用。一致なし/ディレクトリなしは空出力。 */
    val CKSUM: String = sh(MIGRATE + "cksum \"$DIR\"/*.lua 2>/dev/null; true")

    /** `~/.config/acrylterm/<name>` を消す(無ければ成功扱い)。 */
    fun deleteCommand(name: String): String {
        require(ScriptStore.isValidName(name)) { "invalid script name: $name" }
        return sh("rm -f \"$DIR/$name\"")
    }

    /** ディレクトリが存在すれば 0、無ければ 1。 */
    val DIR_EXISTS: String = sh("[ -d \"$DIR\" ]")

    /** [fetchCommand] の出力(区切りは fetch ごとのランダム nonce 付き)を `name` → source に分解する。名前が不正なものは捨てる。 */
    fun parseBundle(out: String, nonce: String): List<ExtScript> {
        val res = ArrayList<ExtScript>()
        val pieces = ("\n" + out.removePrefix("\n")).split("\n" + marker(nonce))
        for (p in pieces.drop(1)) {
            val nl = p.indexOf('\n')
            val name = (if (nl < 0) p else p.substring(0, nl)).trim()
            val body = if (nl < 0) "" else p.substring(nl + 1)
            if (ScriptStore.isValidName(name)) res += ExtScript(name, body)
        }
        return res.sortedBy { it.name }
    }

    /**
     * `~/.config/acrylterm/.app/<name>` を書くコマンド列(順に実行する)。親ディレクトリが無ければ何もしない(失敗扱いにしない)。
     * 1 コマンドあたり base64 で約 [chunkBytes]*4/3 バイト。先頭は上書き、以降は追記。
     */
    fun writeCommands(name: String, text: String, chunkBytes: Int = 45_000): List<String> {
        require(Regex("^[A-Za-z0-9_.-]+$").matches(name))
        return chunkCommands(text, chunkBytes) { i, last, b64 ->
            val redir = if (i == 0) ">" else ">>"
            "d=\"$DIR\"; [ -d \"\$d\" ] || exit 0; mkdir -p \"\$d/.app\" || exit 1; " +
                "printf %s '$b64' | { base64 -d 2>/dev/null || base64 -D; } $redir \"\$d/.app/$name\""
        }
    }

    /**
     * `~/.config/acrylterm/<name>` を書くコマンド列(順に実行する)。ディレクトリは `mkdir -p` で作る。
     * 一時ファイル(`.app/<name>.part`)に書き、最後のチャンクで `mv` するので、ポーリングが書きかけを取り込まない。
     */
    fun writeScriptCommands(name: String, text: String, chunkBytes: Int = 45_000): List<String> {
        require(ScriptStore.isValidName(name)) { "invalid script name: $name" }
        return chunkCommands(text, chunkBytes) { i, last, b64 ->
            val redir = if (i == 0) ">" else ">>"
            MIGRATE + "d=\"$DIR\"; mkdir -p \"\$d/.app\" || exit 1; " +
                "printf %s '$b64' | { base64 -d 2>/dev/null || base64 -D; } $redir \"\$d/.app/$name.part\"" +
                (if (last) " && mv -f \"\$d/.app/$name.part\" \"\$d/$name\"" else "")
        }
    }

    private fun chunkCommands(text: String, chunkBytes: Int, build: (index: Int, last: Boolean, b64: String) -> String): List<String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val chunks = if (bytes.isEmpty()) listOf(ByteArray(0)) else bytes.toList().chunked(chunkBytes).map { it.toByteArray() }
        return chunks.mapIndexed { i, c -> sh(build(i, i == chunks.lastIndex, Base64.getEncoder().encodeToString(c))) }
    }

    /** AI CLI に渡すプロンプト。[existing] があれば修正依頼になる。 */
    fun generatePrompt(name: String, request: String, apiDoc: String, existing: String?): String = buildString {
        appendLine("You are writing a Lua extension script for the Acrylterm Android terminal app.")
        appendLine("The script file name is: $name")
        appendLine()
        appendLine("The API reference follows between the markers.")
        appendLine("=== BEGIN API REFERENCE ===")
        appendLine(apiDoc.trimEnd())
        appendLine("=== END API REFERENCE ===")
        appendLine()
        if (existing != null) {
            appendLine("This is the current content of the script. Modify this script per the request.")
            appendLine("=== BEGIN CURRENT SCRIPT ===")
            appendLine(existing.trimEnd())
            appendLine("=== END CURRENT SCRIPT ===")
            appendLine()
        }
        appendLine("Request from the user:")
        appendLine(request.trim())
        appendLine()
        appendLine("The script MUST begin with a header comment block exactly in this form:")
        appendLine("--[[acrylterm")
        appendLine("summary: <one line: what it does>")
        appendLine("usage:")
        appendLine("- <how to use, one bullet per line>")
        appendLine("]]")
        appendLine("Write the header in the same language as the user's request.")
        if (existing != null) appendLine("Update the header too so it matches the modified script.")
        appendLine()
        append("Output ONLY the complete Lua source code. No explanations, no markdown fences. Do not create or modify any files.")
    }

    private fun resultMarker(nonce: String) = "--@@EXIT:$nonce "

    /** [RemoteCommand.viaLoginShell] 経由で実行する想定。プロンプトは stdin で [agentCommand] に渡す。 */
    fun generateCommand(agentCommand: String, prompt: String, nonce: String): String {
        require(agentCommand.isNotBlank()) { "agent command is blank" }
        val b64 = Base64.getEncoder().encodeToString(prompt.toByteArray(Charsets.UTF_8))
        return sh(
            "cd \"\$HOME\"; t=\$(mktemp) || exit 1; " +
                "printf %s '$b64' | { base64 -d 2>/dev/null || base64 -D; } | $agentCommand 2>\"\$t\"; rc=\$?; " +
                "printf '\\n${resultMarker(nonce)}%s\\n' \"\$rc\"; " +
                "[ \"\$rc\" -eq 0 ] || tail -c 2000 \"\$t\"; rm -f \"\$t\""
        )
    }

    data class GenerateResult(val stdout: String, val exitCode: Int?, val stderr: String)

    /** [generateCommand] の出力を分解する。マーカーが無ければ exitCode は null(時間切れ/強制終了)。 */
    fun parseGenerateOutput(out: String, nonce: String): GenerateResult {
        val m = "\n" + resultMarker(nonce)
        val i = out.lastIndexOf(m)
        if (i < 0) return GenerateResult(out, null, "")
        val rest = out.substring(i + m.length)
        val nl = rest.indexOf('\n')
        val rcText = (if (nl < 0) rest else rest.substring(0, nl)).trim()
        val err = if (nl < 0) "" else rest.substring(nl + 1)
        return GenerateResult(out.substring(0, i), rcText.toIntOrNull(), err.trim())
    }

    /** 出力から Lua を取り出す。フェンスがあれば最大のブロック、無ければ全体。末尾の改行は 1 つに揃える。 */
    fun extractLua(output: String): String {
        val fence = Regex("```[A-Za-z]*[ \\t]*\\r?\\n(.*?)\\r?\\n?```", RegexOption.DOT_MATCHES_ALL)
        val best = fence.findAll(output).map { it.groupValues[1] }.maxByOrNull { it.length }
        val body = (best ?: output).trim()
        return if (body.isEmpty()) "" else body + "\n"
    }
}
