package io.github.xntso.vendroid

import android.content.Context
import io.github.xntso.vendroid.ui.utils.SettingsChangeNotifier
import io.github.xntso.vendroid.ui.utils.delegate


interface SettingChangeListener {
    fun refreshSettings(settings: AppSettings)
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

class AppSettings(context: Context, val tileId: Int? = null) : SettingsChangeNotifier {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<SettingChangeListener>()

    fun addListener(listener: SettingChangeListener) {
        listeners.add(listener)
    }

    override fun notifyListeners() {
        listeners.forEach { it.refreshSettings(this) }
    }

    var themeMode: ThemeMode by settings.delegate("theme_mode", ThemeMode.SYSTEM)
    var dynamicColors: Boolean by settings.delegate("dynamic_colors", false)
    var showNotificationsBanner: Boolean by settings.delegate("show_notifications_banner", true)
    var showTelemetryBanner: Boolean by settings.delegate("show_telemetry_banner", true)
}