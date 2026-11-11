package io.github.xntso.vendroid.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.ui.composables.OpenSourceNotices
import io.github.xntso.vendroid.utils.ProjectLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class OpenSourceNoticesTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    private fun read(path: String) = OpenSourceNotices.read(assets, path)

    @Test
    fun `packaged notice credits EtchDroid and lists the third-party software`() {
        val notice = read(OpenSourceNotices.NOTICE)
        for (phrase in listOf(
            "modified version of EtchDroid", "Davide Depau", ProjectLinks.ETCHDROID_BASE_COMMIT, "WITHOUT ANY",
            "libusb", "GNU Lesser General Public License", "exfatprogs", "Creative Commons 3.0 Attribution",
        )) {
            assertTrue(notice.contains(phrase), "NOTICE lacks \"$phrase\"")
        }
    }

    @Test
    fun `every listed document opens and the license texts are the expected versions`() {
        val documents = OpenSourceNotices.documents(assets)
        assertEquals(OpenSourceNotices.NOTICE, documents.first().assetPath)
        for (document in documents) {
            assertTrue(read(document.assetPath).isNotBlank(), "${document.assetPath} is empty")
        }
        assertTrue(read("licenses/GPL-3.0.txt").contains("Version 3, 29 June 2007"))
        assertTrue(read("licenses/LGPL-2.1.txt").contains("Version 2.1, February 1999"))
        assertTrue(read("licenses/Apache-2.0.txt").contains("Version 2.0, January 2004"))
    }

    @Test
    fun `the dialog lists the whole Ventoy License folder`() {
        val ventoy = OpenSourceNotices.documents(assets)
            .filter { it.assetPath.startsWith("${OpenSourceNotices.VENTOY_LICENSES}/") }
        assertTrue(ventoy.size >= 40, "only ${ventoy.size} Ventoy license files")
        assertTrue(ventoy.any { it.assetPath.endsWith("/license-ventoy.txt") })
    }
}
