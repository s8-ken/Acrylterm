package com.shibaken.androidterminal.ext

import android.content.res.AssetManager

/**
 * アプリ同梱の拡張テンプレート(assets の `ext/templates/` 配下の .lua)。読み取り専用で、既定はすべて off。
 * on/off はアプリ全体の設定(`AppSettings.enabledTemplates`、ファイル名の集合)で、ホストごとの無効化とは別。
 */
object ExtTemplates {
    const val DIR = "ext/templates"

    /** テンプレートの読み込み名の接頭辞(`template:foo.lua`)。ホスト由来の `host:` と同じ流儀。 */
    const val PREFIX = "template:"

    fun loadName(fileName: String) = PREFIX + fileName

    fun isTemplateName(loadName: String) = loadName.startsWith(PREFIX)

    /** 同梱テンプレート 1 本。[fileName] は `foo.lua`。 */
    data class Template(val fileName: String, val source: String) {
        val loadName: String get() = loadName(fileName)
    }

    /** assets から同梱テンプレートを名前順で読む。 */
    fun list(assets: AssetManager): List<Template> =
        (assets.list(DIR) ?: emptyArray()).filter { ScriptStore.isValidName(it) }.sorted().map { n ->
            Template(n, assets.open("$DIR/$n").bufferedReader().use { it.readText() })
        }

    /**
     * 最終的な読み込み順。有効なテンプレート(名前順、`template:` 接頭辞)→[base](= `ScriptStore.loadOrder` の結果)。
     * ユーザーのスクリプトが後に読まれるので、同名のコマンド等はユーザー側が上書きできる。
     * [enabled] のうち [templates] に無い名前(削除・改名されたもの)は無視する。
     */
    fun merge(templates: List<Template>, enabled: Set<String>, base: List<ExtScript>): List<ExtScript> {
        if (enabled.isEmpty()) return base
        val on = templates.filter { it.fileName in enabled }.sortedBy { it.fileName }.map { ExtScript(it.loadName, it.source) }
        return on + base
    }

    /** [existing] と衝突しない `foo.lua` / `foo-2.lua` / `foo-3.lua` … を返す。 */
    fun copyName(fileName: String, existing: Collection<String>): String {
        if (fileName !in existing) return fileName
        val stem = fileName.removeSuffix(".lua")
        var i = 2
        while ("$stem-$i.lua" in existing) i++
        return "$stem-$i.lua"
    }
}
