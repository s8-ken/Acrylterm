package com.shibaken.androidterminal.ext

import java.io.File

/**
 * 拡張スクリプトのファイル保管庫。[root] は `context.filesDir/ext` を想定。
 *  - `global/ 配下の .lua`: アプリ内で編集する全体スクリプト
 *  - `hosts/<hostId>/ 配下の .lua`: ホストから取り込んだスクリプトのキャッシュ(丸ごと置換)
 */
class ScriptStore(private val root: File) {
    private val globalDir get() = File(root, "global")
    private fun hostDir(hostId: String): File {
        require(isValidId(hostId)) { "invalid host id: $hostId" }
        return File(File(root, "hosts"), hostId)
    }

    fun listGlobal(): List<String> = listNames(globalDir)

    fun readGlobal(name: String): String? = read(File(globalDir, checkName(name)))

    fun writeGlobal(name: String, source: String) = write(File(globalDir, checkName(name)), source)

    fun deleteGlobal(name: String): Boolean = File(globalDir, checkName(name)).delete()

    fun listHost(hostId: String): List<String> = listNames(hostDir(hostId))

    fun readHost(hostId: String, name: String): String? = read(File(hostDir(hostId), checkName(name)))

    /** キャッシュを持つホスト ID(`hosts/` 配下の有効なディレクトリ名)を名前順で返す。 */
    fun listHostIds(): List<String> =
        File(root, "hosts").listFiles()?.filter { it.isDirectory && isValidId(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    /**
     * ホストのスクリプトを全体へコピーする。ホスト側に無い、または全体に [asName] が既にあり
     * [overwrite] でない場合は何も書かず false を返す。
     */
    fun importHostToGlobal(hostId: String, name: String, asName: String = name, overwrite: Boolean = false): Boolean {
        val target = File(globalDir, checkName(asName))
        val source = readHost(hostId, name) ?: return false
        if (target.exists() && !overwrite) return false
        write(target, source)
        return true
    }

    /** ホストのキャッシュを [scripts] で置き換える。不正な名前のものは無視する。 */
    fun replaceHost(hostId: String, scripts: List<ExtScript>) {
        val dir = hostDir(hostId)
        dir.deleteRecursively()
        scripts.filter { isValidName(it.name) }.forEach { write(File(dir, it.name), it.source) }
    }

    fun clearHost(hostId: String) {
        hostDir(hostId).deleteRecursively()
    }

    /**
     * 読み込み順: 全体スクリプト(ファイル名順)→ホストのスクリプト(同)。
     * 名前はエラー表示用で、全体は `foo.lua`、ホストは `host:foo.lua`。
     * 全体に同名・同一内容のスクリプトがある場合(取り込み済み)、ホスト側は二重読み込みを避けて除く。
     */
    fun loadOrder(hostId: String?, includeHost: Boolean): List<ExtScript> {
        val out = ArrayList<ExtScript>()
        for (n in listGlobal()) readGlobal(n)?.let { out += ExtScript(n, it) }
        if (includeHost && hostId != null) {
            val dir = hostDir(hostId)
            for (n in listNames(dir)) {
                val src = read(File(dir, n)) ?: continue
                if (readGlobal(n) == src) continue
                out += ExtScript("host:$n", src)
            }
        }
        return out
    }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*\\.lua$")
        private val ID = Regex("^[A-Za-z0-9_-][A-Za-z0-9_.-]*$")

        /** 英数字・`_`・`-`・`.` のみ、`.lua` 終わり、先頭ドット不可(パス区切り不可)。 */
        fun isValidName(name: String): Boolean = name.length <= 100 && NAME.matches(name) && !name.contains("..")
        private fun isValidId(id: String) = id.length <= 100 && ID.matches(id) && !id.contains("..")
        private fun checkName(name: String): String {
            require(isValidName(name)) { "invalid script name: $name" }
            return name
        }

        private fun listNames(dir: File): List<String> =
            dir.listFiles()?.filter { it.isFile && isValidName(it.name) }?.map { it.name }?.sorted() ?: emptyList()

        private fun read(f: File): String? = if (f.isFile) f.readText() else null

        private fun write(f: File, text: String) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "write failed: ${f.name}" }
            }
        }
    }
}
