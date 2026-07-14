package io.github.xntso.vendroid

import android.net.Uri
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.service.WorkerService
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class VentoyIntentTest {
    private val device = UsbMassStorageDeviceDescriptor(
        previewUsbDevice = PreviewUsbDevice("Test USB", "1234:5678"),
    )

    @Test
    fun `progress status preserves Ventoy force install state`() {
        val intent = getProgressUpdateIntent(
            sourceUri = VENTOY_INSTALL_URI,
            destDevice = device,
            jobId = 42,
            speed = 0f,
            processedBytes = 25,
            totalBytes = 100,
            operation = Intents.OPERATION_VENTOY_INSTALL,
            forceInstall = true,
        )

        val status = intent.safeParcelableExtra<JobStatusInfo>("status")!!
        assertEquals(Intents.OPERATION_VENTOY_INSTALL, status.operation)
        assertTrue(status.forceInstall)
    }

    @Test
    fun `restart intent preserves Ventoy force install state`() {
        val intent = getStartJobIntent(
            sourceUri = Uri.parse("vendroid://ventoy/install"),
            destDevice = device,
            jobId = 42,
            packageContext = null,
            cls = WorkerService::class.java,
            operation = Intents.OPERATION_VENTOY_INSTALL,
            forceInstall = true,
        )

        assertEquals(Intents.OPERATION_VENTOY_INSTALL, intent.getStringExtra(Intents.EXTRA_OPERATION))
        assertTrue(intent.getBooleanExtra(Intents.EXTRA_FORCE_INSTALL, false))
    }
}
