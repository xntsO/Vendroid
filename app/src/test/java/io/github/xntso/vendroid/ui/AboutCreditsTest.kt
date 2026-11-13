package io.github.xntso.vendroid.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.test.core.app.ApplicationProvider
import io.github.xntso.vendroid.BuildConfig
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.utils.ProjectLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.Locale

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class AboutCreditsTest {
    // Read lazily: the Robolectric environment exists only once a test runs.
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun localized(language: String): Context {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return context.createConfigurationContext(configuration)
    }

    private fun links(context: Context): Map<String, String> {
        val credits = aboutCreditsText(aboutCreditLines(context), SpanStyle())
        return credits.getLinkAnnotations(0, credits.length).associate { range ->
            credits.text.substring(range.start, range.end) + "@" + range.start to (range.item as LinkAnnotation.Url).url
        }
    }

    @Test
    fun `Ventoy source opens the exact bundled commit from a word, not a raw address`() {
        for (language in listOf("en", "ar")) {
            val lines = aboutCreditLines(localized(language)).flatten()
            val ventoy = lines.single { it.url == ProjectLinks.ventoySource(BuildConfig.VENTOY_SOURCE_COMMIT) }

            assertEquals("GitHub", ventoy.linkedText, language)
            assertTrue(ventoy.text.contains("Ventoy ${BuildConfig.VENTOY_VERSION}"), language)
            assertTrue(Regex("[0-9a-f]{40}").matches(BuildConfig.VENTOY_SOURCE_COMMIT))
            for (line in lines) {
                assertFalse(line.text.contains("github.com"), "$language shows a raw address: ${line.text}")
            }
        }
    }

    @Test
    fun `every credit link is attached to its visible word`() {
        val links = links(context)
        val expected = setOf(
            ProjectLinks.DEVELOPER_WEBSITE,
            ProjectLinks.SOURCE_CODE,
            ProjectLinks.ETCHDROID_SOURCE,
            ProjectLinks.ventoySource(BuildConfig.VENTOY_SOURCE_COMMIT),
        )
        assertEquals(expected, links.values.toSet())
        assertEquals(
            setOf("Faisal Sumayli", "GitHub", "EtchDroid"),
            links.keys.map { it.substringBefore("@") }.toSet(),
        )
        assertEquals(4, links.size)
    }

    @Test
    fun `the developer name opens the developer website`() {
        val developer = aboutCreditLines(context).first().first()
        assertEquals(context.getString(R.string.developer_name), developer.linkedText)
        assertEquals("https://fhas.sa", developer.url)
    }

    @Test
    fun `the Arabic GitHub star button keeps the GitHub name`() {
        assertEquals("أضف نجمة على GitHub", localized("ar").getString(R.string.star_on_github))
    }
}
