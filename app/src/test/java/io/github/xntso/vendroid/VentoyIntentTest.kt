package io.github.xntso.vendroid

import android.net.Uri
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.service.WorkerService
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.xntso.vendroid.ventoy.VentoyClusterSize
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
        val options = VentoyJobOptions(
            forceInstall = true,
            label = "TOOLS",
            reservedSpaceBytes = 1024L * 1024 * 1024,
            clusterSize = VentoyClusterSize.KiB64,
        )
        val intent = getProgressUpdateIntent(
            sourceUri = VENTOY_INSTALL_URI,
            destDevice = device,
            jobId = 42,
            speed = 0f,
            processedBytes = 25,
            totalBytes = 100,
            operation = Intents.OPERATION_VENTOY_INSTALL,
            forceInstall = true,
            ventoyOptions = options,
        )

        val status = intent.safeParcelableExtra<JobStatusInfo>("status")!!
        assertEquals(Intents.OPERATION_VENTOY_INSTALL, status.operation)
        assertTrue(status.forceInstall)
        assertEquals(options, status.ventoyOptions)
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

    @Test
    fun `update intent preserves operation and options`() {
        val options = VentoyJobOptions(label = "RECOVERY")

        val intent = getStartVentoyUpdateJobIntent(
            destDevice = device,
            jobId = 42,
            ventoyOptions = options,
        )

        assertEquals(Intents.OPERATION_VENTOY_UPDATE, intent.getStringExtra(Intents.EXTRA_OPERATION))
        assertEquals(
            options,
            intent.safeParcelableExtra<VentoyJobOptions>(Intents.EXTRA_VENTOY_OPTIONS),
        )
    }
}
