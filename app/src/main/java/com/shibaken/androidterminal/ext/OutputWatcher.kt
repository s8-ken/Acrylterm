package com.shibaken.androidterminal.ext

/**
 * 画面スナップショットの差分から「変化した行」を取り出す純ロジック。ペインごとに 1 インスタンス。
 * 行番号(行 index)ごとに前回と比較し、末尾空白を除いた非空行のうち変わったものだけ返す。
 * 最初の [update] は基準を保存するだけで空を返す(接続直後の既存画面でトリガーを発火させない)。
 */
class OutputWatcher {
    private var previous: List<String>? = null

    fun update(lines: List<String>): List<String> {
        val cur = lines.map { it.trimEnd() }
        val prev = previous
        previous = cur
        if (prev == null) return emptyList()
        val out = ArrayList<String>()
        for (i in cur.indices) {
            val l = cur[i]
            if (l.isBlank()) continue
            if (i >= prev.size || prev[i] != l) out += l
        }
        return out
    }

    /** 基準を捨てる(リサイズ・画面切替時など)。次の [update] が再び基準になる。 */
    fun reset() {
        previous = null
    }
}
