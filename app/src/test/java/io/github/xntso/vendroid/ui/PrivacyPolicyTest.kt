package io.github.xntso.vendroid.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.xntso.vendroid.VendroidApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class PrivacyPolicyTest {
    @Test
    fun `packaged policy is readable offline and describes first-party downloads without age entry`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = context.assets.open("privacy-policy.md").bufferedReader().use { it.readText() }
        assertTrue(policy.startsWith("# Vendroid privacy policy"))
        assertTrue(policy.contains("updates.vendroid.fhas.sa"))
        assertTrue(policy.contains("IP address"))
        assertTrue(policy.contains("contact@fhas.sa"))
        assertFalse(policy.contains("asks for a birth date"))
    }
}
