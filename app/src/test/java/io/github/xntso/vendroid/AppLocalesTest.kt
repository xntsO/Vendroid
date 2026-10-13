package io.github.xntso.vendroid

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

abstract class AppLocalesTestSuite {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeEach
    fun resetSettings() {
        assertEquals(requireNotNull(javaClass.getAnnotation(Config::class.java)).sdk.single(), Build.VERSION.SDK_INT)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setQualifiers("en")
        AppLocales.apply(context)
    }

    @AfterEach
    fun restoreAutomatic() {
        AppSettings(context).language = AppLanguage.AUTOMATIC
        RuntimeEnvironment.setQualifiers("en")
        AppLocales.apply(context)
    }

    @Test
    fun `all choices persist across settings instances and corrupt values fall back`() {
        assertEquals(AppLanguage.AUTOMATIC, AppSettings(context).language)
        for (language in AppLanguage.entries) {
            AppSettings(context).language = language
            assertEquals(language, AppSettings(context).language)
        }
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("language", "obsolete").commit()
        assertEquals(AppLanguage.AUTOMATIC, AppSettings(context).language)
    }

    @Test
    fun `automatic follows real system configuration without being overwritten by its resolved locale`() {
        RuntimeEnvironment.setQualifiers("ar-rSA")
        AppLocales.apply(context)
        assertLocale("ar", View.LAYOUT_DIRECTION_RTL, "تثبيت Ventoy")
        assertEquals(AppLanguage.AUTOMATIC, AppSettings(context).language)
        RuntimeEnvironment.setQualifiers("fr")
        AppLocales.apply(context)
        assertLocale("en", View.LAYOUT_DIRECTION_LTR, "Install Ventoy")
        assertEquals(AppLanguage.AUTOMATIC, AppSettings(context).language)
    }

    @Test
    fun `explicit choice remains after device changes and notification strings follow a later choice`() {
        AppSettings(context).language = AppLanguage.ARABIC
        RuntimeEnvironment.setQualifiers("en")
        AppLocales.apply(context)
        assertLocale("ar", View.LAYOUT_DIRECTION_RTL, "تثبيت Ventoy")
        assertEquals("جارٍ تنزيل Ventoy 1.1.18…", AppLocales.getString(context, R.string.downloading_online_ventoy, "1.1.18"))
        val firstServiceContext = AppLocales.localizedContext(context)
        AppSettings(context).language = AppLanguage.ENGLISH
        RuntimeEnvironment.setQualifiers("ar")
        AppLocales.apply(context)
        assertLocale("en", View.LAYOUT_DIRECTION_LTR, "Install Ventoy")
        // A notification constructed by an already-running service uses the current saved choice.
        assertEquals("Downloading Ventoy 1.1.18…", AppLocales.getString(firstServiceContext, R.string.downloading_online_ventoy, "1.1.18"))
    }

    private fun assertLocale(tag: String, direction: Int, installLabel: String) {
        val localized = AppLocales.localizedContext(context)
        assertEquals(tag, ConfigurationCompat.getLocales(localized.resources.configuration).get(0)?.language)
        assertEquals(direction, localized.resources.configuration.layoutDirection)
        assertEquals(installLabel, localized.getString(R.string.install_ventoy))
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            requireNotNull(context.getSystemService(LocaleManager::class.java)).applicationLocales.get(0)?.language
        } else AppCompatDelegate.getApplicationLocales().get(0)?.language
        assertEquals(tag, requested)
    }
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [23])
class AppLocalesApi23Test : AppLocalesTestSuite()

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [32])
class AppLocalesApi32Test : AppLocalesTestSuite()

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class AppLocalesApi35Test : AppLocalesTestSuite()
