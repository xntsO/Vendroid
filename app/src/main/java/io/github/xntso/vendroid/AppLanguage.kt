package io.github.xntso.vendroid

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class AppLanguage {
    AUTOMATIC, ENGLISH, ARABIC;

    fun resolve(systemLocale: Locale?): Locale = when (this) {
        AUTOMATIC -> if (systemLocale?.language == "ar") Locale("ar") else Locale.ENGLISH
        ENGLISH -> Locale.ENGLISH
        ARABIC -> Locale("ar")
    }

    companion object {
        fun fromPreference(value: String?): AppLanguage =
            entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

/** The saved choice remains Automatic even when its current resolved locale is English. */
object AppLocales {
    fun apply(context: Context) {
        val locale = selectedLocale(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = requireNotNull(context.getSystemService(LocaleManager::class.java))
            val requested = LocaleList(locale)
            if (manager.applicationLocales != requested) manager.applicationLocales = requested
        } else {
            val requested = LocaleListCompat.create(locale)
            if (AppCompatDelegate.getApplicationLocales() != requested) {
                AppCompatDelegate.setApplicationLocales(requested)
            }
        }
    }

    // AppCompat only localizes Activity contexts on Android 12 and earlier.
    fun localizedContext(context: Context): Context {
        val locale = selectedLocale(context)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    fun getString(context: Context, @StringRes resource: Int, vararg arguments: Any?): String =
        localizedContext(context).getString(resource, *arguments)

    private fun selectedLocale(context: Context): Locale {
        val systemLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireNotNull(context.getSystemService(LocaleManager::class.java)).systemLocales.get(0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale
        }
        return AppSettings(context).language.resolve(systemLocale)
    }
}
