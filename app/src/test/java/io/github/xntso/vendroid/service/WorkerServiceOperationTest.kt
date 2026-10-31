package io.github.xntso.vendroid.service

import android.net.Uri
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.getStartJobIntent
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class WorkerServiceOperationTest {
    @Test
    fun `raw image jobs stop before the service takes the drive`() {
        val intent = startJobIntent().apply {
            putExtra(Intents.EXTRA_OPERATION, Intents.OPERATION_WRITE_IMAGE)
        }

        assertRefused(intent)
    }

    @Test
    fun `jobs without an operation stop before the service takes the drive`() {
        val intent = startJobIntent().apply { removeExtra(Intents.EXTRA_OPERATION) }

        assertRefused(intent)
    }

    private fun assertRefused(intent: android.content.Intent) {
        val service = Robolectric.buildService(WorkerService::class.java, intent)
            .create()
            .startCommand(0, 1)
            .get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    private fun startJobIntent() = getStartJobIntent(
        Uri.parse("content://vendroid/boot.iso"),
        UsbMassStorageDeviceDescriptor(
            previewUsbDevice = PreviewUsbDevice("Test USB", "1234:5678"),
        ),
        jobId = 7,
        packageContext = RuntimeEnvironment.getApplication(),
        cls = WorkerService::class.java,
    )
}
