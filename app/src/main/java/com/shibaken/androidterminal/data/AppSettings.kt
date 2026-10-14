package com.shibaken.androidterminal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val extensionsEnabled: Boolean = false,
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
        mutableSettings = MutableStateFlow(AppSettings(preferences.getBoolean(KEY_EXTENSIONS, false)))
    }

    val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    val extensionsEnabled: Boolean get() = settings.value.extensionsEnabled

    fun setExtensionsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_EXTENSIONS, enabled).apply()
        mutableSettings.value = mutableSettings.value.copy(extensionsEnabled = enabled)
    }

    private companion object {
        const val KEY_EXTENSIONS = "extensions_enabled"
    }
}
