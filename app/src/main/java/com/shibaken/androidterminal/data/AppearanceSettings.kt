package com.shibaken.androidterminal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppearanceSettings(
    val transparencyPercent: Int = 28,
    val blurBackground: Boolean = true,
    val colors: ColorSelection = ColorSelection(),
) {
    val backgroundOpacity: Float get() = 1f - transparencyPercent.coerceIn(0, 100) / 100f

    fun normalized() = copy(transparencyPercent = transparencyPercent.coerceIn(0, 100))
}

/** App-wide appearance; settings previews are drafts until the user taps Apply. */
class AppearanceRepository(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(
        AppearanceSettings(
            preferences.getInt("transparency_percent", 28),
            preferences.getBoolean("blur_background", true),
            ColorSelection.decode(preferences.getString("color_profile", null)),
        ).normalized()
    )
    val settings = mutableSettings.asStateFlow()

    fun save(settings: AppearanceSettings) {
        val normalized = settings.normalized()
        preferences.edit()
            .putInt("transparency_percent", normalized.transparencyPercent)
            .putBoolean("blur_background", normalized.blurBackground)
            .putString("color_profile", normalized.colors.encode())
            .apply()
        mutableSettings.value = normalized
    }
}
