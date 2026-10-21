package com.shibaken.androidterminal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val extensionsEnabled: Boolean = false,
    /** 有効にした同梱テンプレートのファイル名(アプリ全体、既定は空 = すべて off)。 */
    val enabledTemplates: Set<String> = emptySet(),
    /** ⋮ メニューに「ペインを分割」を出す。ホスト側で分割済みのタブは、オフでも分割のまま表示する。 */
    val paneSplitEnabled: Boolean = false,
)

/** App-wide feature switches; changes are saved immediately. */
class AppSettingsRepository(context: Context, defaultExtensionsEnabled: () -> Boolean) {
    private val preferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val mutableSettings: MutableStateFlow<AppSettings>

    init {
        // Migration: users who already use extensions keep them on; everyone else starts with them hidden.
        if (!preferences.contains(KEY_EXTENSIONS)) {
            preferences.edit().putBoolean(KEY_EXTENSIONS, defaultExtensionsEnabled()).apply()
        }
        mutableSettings = MutableStateFlow(AppSettings(
            preferences.getBoolean(KEY_EXTENSIONS, false),
            preferences.getStringSet(KEY_TEMPLATES, null)?.toSet() ?: emptySet(),
            preferences.getBoolean(KEY_PANE_SPLIT, false),
        ))
    }

    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    val extensionsEnabled: Boolean get() = settings.value.extensionsEnabled

    fun setExtensionsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_EXTENSIONS, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(extensionsEnabled = enabled)
    }

    fun setPaneSplitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PANE_SPLIT, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(paneSplitEnabled = enabled)
    }

    val enabledTemplates: Set<String> get() = settings.value.enabledTemplates

    fun setTemplateEnabled(fileName: String, enabled: Boolean) {
        val next = if (enabled) enabledTemplates + fileName else enabledTemplates - fileName
        if (next == enabledTemplates) return
        // getStringSet の返り値は書き換えてはいけないので、毎回新しい集合を渡す。
        preferences.edit().putStringSet(KEY_TEMPLATES, HashSet(next)).apply()
        mutableSettings.value = mutableSettings.value.copy(enabledTemplates = next)
    }

    private companion object {
        const val KEY_EXTENSIONS = "extensions_enabled"
        const val KEY_TEMPLATES = "enabled_templates"
        const val KEY_PANE_SPLIT = "pane_split_enabled"
    }
}
