package io.github.xntso.vendroid.online

import android.app.Activity
import android.content.Intent
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.plugins.reviews.WriteReviewHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class ExternalProjectLinksTest {
    @Test
    fun `unknown and restricted users cannot start an external project activity`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var eligibility = OnlineEligibility.UNKNOWN
            val links = ExternalProjectLinks { eligibility }
            for (band in listOf(OnlineEligibility.UNKNOWN, OnlineEligibility.RESTRICTED)) {
                eligibility = band
                for (url in listOf("https://github.com/xntsO/Vendroid", "https://www.ventoy.net")) {
                    assertThrows<OnlineAccessRequiredException> { links.open(activity, url) }
                    assertNull(shadowOf(activity).nextStartedActivity)
                }
            }
            eligibility = OnlineEligibility.ELIGIBLE
            links.open(activity, "https://github.com/xntsO/Vendroid/issues")
            val launched = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, launched.action)
            assertEquals("https://github.com/xntsO/Vendroid/issues", launched.data.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `FOSS review helper cannot bypass the unknown-session boundary`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            assertThrows<OnlineAccessRequiredException> { WriteReviewHelper(activity).launchReviewFlow() }
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
