package io.github.xntso.vendroid.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.xntso.vendroid.BuildConfig
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.utils.ProjectLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class VentoyLicenseAssetsTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    private fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    @Test
    fun `packaged Ventoy licenses and source notice belong to the bundled payload`() {
        val licenses = assets.list("ventoy_licenses/License").orEmpty().toSet()
        val required = setOf(
            "gpl-2.0.txt", "gpl-3.0.txt", "license-busybox.txt", "license-grub2.txt", "license-ventoy.txt",
        )
        assertTrue(licenses.containsAll(required), "Missing ${required - licenses}")
        assertTrue(licenses.size >= 40, "Only ${licenses.size} Ventoy license files are packaged")
        assertTrue(read("ventoy_licenses/License/gpl-3.0.txt").contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(read("ventoy_licenses/License/license-busybox.txt").contains("BusyBox"))

        assertEquals(BuildConfig.VENTOY_VERSION, read("ventoy_payload/ventoy/version").trim())
        assertTrue(Regex("[0-9a-f]{40}").matches(BuildConfig.VENTOY_SOURCE_COMMIT))
        val notice = read("ventoy_licenses/NOTICE.txt")
        assertTrue(notice.contains("Ventoy ${BuildConfig.VENTOY_VERSION}"))
        assertTrue(notice.contains("https://github.com/ventoy/Ventoy/tree/v${BuildConfig.VENTOY_VERSION}"))
        assertTrue(
            notice.contains("https://github.com/ventoy/Ventoy/commit/${BuildConfig.VENTOY_SOURCE_COMMIT}"),
        )
        assertTrue(notice.contains("busybox.net/downloads/busybox-"))
        assertTrue(read("ventoy_licenses/source-lock.json").contains(BuildConfig.VENTOY_SOURCE_COMMIT))
        assertEquals(
            "https://github.com/ventoy/Ventoy/tree/${BuildConfig.VENTOY_SOURCE_COMMIT}",
            ProjectLinks.ventoySource(BuildConfig.VENTOY_SOURCE_COMMIT),
        )
    }
}
