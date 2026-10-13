package io.github.xntso.vendroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class AppLanguageTest {
    @Test
    fun `automatic recognizes Arabic regions and falls back to English for unsupported languages`() {
        assertEquals("ar", AppLanguage.AUTOMATIC.resolve(Locale.forLanguageTag("ar-SA")).language)
        assertEquals("ar", AppLanguage.AUTOMATIC.resolve(Locale.forLanguageTag("ar-EG")).language)
        for (locale in listOf(Locale.US, Locale.UK, Locale.FRENCH, Locale("he"), Locale("fa"), null)) {
            assertEquals("en", AppLanguage.AUTOMATIC.resolve(locale).language)
        }
    }

    @Test
    fun `explicit language overrides the device and malformed saved values use Automatic`() {
        assertEquals("en", AppLanguage.ENGLISH.resolve(Locale("ar")).language)
        assertEquals("ar", AppLanguage.ARABIC.resolve(Locale.ENGLISH).language)
        for (language in AppLanguage.entries) assertEquals(language, AppLanguage.fromPreference(language.name))
        for (value in listOf(null, "", "GERMAN", "ar", "AUTOMATIC ")) {
            assertEquals(AppLanguage.AUTOMATIC, AppLanguage.fromPreference(value))
        }
    }
}
