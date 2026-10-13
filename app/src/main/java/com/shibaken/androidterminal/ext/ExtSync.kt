package com.shibaken.androidterminal.ext

import java.util.Base64

/**
 * ホストの `~/.config/androidterminal/` とのやり取りに使うコマンド生成とパースの純ロジック(ユニットテスト対象)。
 * 全コマンドは `sh -c '…'` で包んで実行する想定(ログインシェルが fish 等でも動くように)。
 */
object ExtSync {
    fun newNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    private fun marker(nonce: String) = "--@@FILE:$nonce "
    private const val DIR = "\$HOME/.config/androidterminal"

    /** Runs [script] under POSIX sh regardless of the user's login shell. */
    fun sh(script: String): String = "sh -c '" + script.replace("'", "'\\''") + "'"

    /** 全 `*.lua` を区切り行付きで出力する。一致なしでも成功(空出力)。 */
    fun fetchCommand(nonce: String): String = sh(
        "d=\"$DIR\"; for f in \"\$d\"/*.lua; do [ -f \"\$f\" ] || continue; " +
            "printf '\\n${marker(nonce)}%s\\n' \"\${f##*/}\"; cat \"\$f\"; done; true"
    )

    /** 変更検知用。一致なし/ディレクトリなしは空出力。 */
    val CKSUM: String = sh("cksum \"$DIR\"/*.lua 2>/dev/null; true")

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
     * `~/.config/androidterminal/.app/<name>` を書くコマンド列(順に実行する)。親ディレクトリが無ければ何もしない(失敗扱いにしない)。
     * 1 コマンドあたり base64 で約 [chunkBytes]*4/3 バイト。先頭は上書き、以降は追記。
     */
    fun writeCommands(name: String, text: String, chunkBytes: Int = 45_000): List<String> {
        require(Regex("^[A-Za-z0-9_.-]+$").matches(name))
        val bytes = text.toByteArray(Charsets.UTF_8)
        val chunks = if (bytes.isEmpty()) listOf(ByteArray(0)) else bytes.toList().chunked(chunkBytes).map { it.toByteArray() }
        return chunks.mapIndexed { i, c ->
            val b64 = Base64.getEncoder().encodeToString(c)
            val redir = if (i == 0) ">" else ">>"
            sh(
                "d=\"$DIR\"; [ -d \"\$d\" ] || exit 0; mkdir -p \"\$d/.app\" || exit 1; " +
                    "printf %s '$b64' | { base64 -d 2>/dev/null || base64 -D; } $redir \"\$d/.app/$name\""
            )
        }
    }
}
