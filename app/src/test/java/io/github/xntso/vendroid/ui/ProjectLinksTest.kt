package io.github.xntso.vendroid.ui

import android.app.Activity
import android.content.Intent
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.plugins.reviews.WriteReviewHelper
import io.github.xntso.vendroid.utils.ProjectLinks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class ProjectLinksTest {
    @Test
    fun `project and attribution links hand off externally without an age gate or personal extras`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            for (url in listOf(ProjectLinks.WEBSITE, ProjectLinks.HELP, "https://www.ventoy.net")) {
                ProjectLinks.open(activity, url)
                val intent = requireNotNull(shadowOf(activity).nextStartedActivity)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(url, intent.data.toString())
                assertNull(intent.component)
                assertNull(intent.extras)
            }
            WriteReviewHelper(activity).launchReviewFlow()
            val review = requireNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, review.action)
            assertEquals("https://github.com/xntsO/Vendroid/", review.data.toString())
            assertNull(review.extras)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
