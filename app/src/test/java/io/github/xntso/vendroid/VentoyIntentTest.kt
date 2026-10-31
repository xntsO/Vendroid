package io.github.xntso.vendroid

import android.content.Intent
import android.net.Uri
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.service.WorkerService
import io.github.xntso.vendroid.utils.ktexts.safeParcelableExtra
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.xntso.vendroid.ventoy.VentoyClusterSize
import io.github.xntso.vendroid.ventoy.VentoyPartitionStyle
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class VentoyIntentTest {
    private val device = UsbMassStorageDeviceDescriptor(
        previewUsbDevice = PreviewUsbDevice("Test USB", "1234:5678"),
    )

    @Test
    fun `the launcher stays reachable while no activity opens image files`() {
        val context = RuntimeEnvironment.getApplication()
        val packageManager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        assertEquals(1, packageManager.queryIntentActivities(launcher, 0).size)

        val images = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(
                Uri.parse("content://vendroid/boot.iso"),
                "application/x-iso9660-image",
            ),
            Intent(Intent.ACTION_VIEW, Uri.parse("file:///storage/emulated/0/boot.iso")),
            Intent(Intent.ACTION_VIEW, Uri.parse("content://vendroid/boot.img")),
        )
        for (image in images) {
            image.setPackage(context.packageName)
            assertTrue(packageManager.queryIntentActivities(image, 0).isEmpty())
        }
    }

    @Test
    fun `progress status preserves Ventoy force install state`() {
        val options = VentoyJobOptions(
            forceInstall = true,
            label = "TOOLS",
            reservedSpaceBytes = 1024L * 1024 * 1024,
            clusterSize = VentoyClusterSize.KiB64,
            partitionStyle = VentoyPartitionStyle.Gpt,
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
        val options = VentoyJobOptions(
            label = "RECOVERY",
            onlinePayloadVersion = "1.1.17",
        )

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
