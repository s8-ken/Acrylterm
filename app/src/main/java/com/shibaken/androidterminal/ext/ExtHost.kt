package com.shibaken.androidterminal.ext

/** Lua 拡張から見えるペインのスナップショット。 */
data class ExtPane(
    val id: String,
    val title: String,
    val agent: String?,
    /** idle | working | blocked | done | unknown */
    val status: String,
    val cwd: String?,
)

/**
 * 拡張エンジンがセッションを操作するための窓口。Android 非依存(ユニットテストではフェイクを渡す)。
 *
 * 全メソッドは Lua 専用スレッドから呼ばれる。実装は例外を投げてよい(Lua エラーに変換される)。
 */
interface ExtHost {
    fun panes(): List<ExtPane>
    fun focusedPaneId(): String?
    /** Paste [text] without adding Enter. Waits for transport acceptance; throws on failure. */
    fun sendText(paneId: String, text: String) = sendInput(paneId, text, emptyList())

    /** Paste and keys are one ordered operation; implementations must not split the submission. */
    fun sendInput(paneId: String, text: String?, keys: List<String>)

    fun submit(paneId: String, text: String) = sendInput(paneId, text, listOf("enter"))

    /** UTF-8 text including control characters, without paste wrapping or newline conversion. */
    fun sendRaw(paneId: String, text: String)

    /** herdr のキー名(例 "enter", "esc")。 */
    fun sendKeys(paneId: String, keys: List<String>) = sendInput(paneId, null, keys)

    /** ペインの末尾 [lines] 行を返す。ブロックしてよい。 */
    fun readPane(paneId: String, lines: Int): String
    fun notify(title: String, body: String, paneId: String?)
    fun toast(text: String)

    /** 接続先ホストでコマンドを実行し stdout を返す。ブロックしてよい。失敗時は例外。 */
    fun exec(command: String, timeoutSec: Int): String
    fun log(line: String)
}
