package io.github.xntso.vendroid.service

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.getStartJobIntent
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class WorkerServiceOperationTest {
    @Test
    fun `raw image jobs enter the foreground and then stop without running`() {
        val intent = startJobIntent().apply {
            putExtra(Intents.EXTRA_OPERATION, Intents.OPERATION_WRITE_IMAGE)
        }

        assertRefusedAfterForeground(intent)
    }

    @Test
    fun `jobs without an operation enter the foreground and then stop without running`() {
        val intent = startJobIntent().apply { removeExtra(Intents.EXTRA_OPERATION) }

        assertRefusedAfterForeground(intent)
    }

    /**
     * startForegroundService obliges the service to call startForeground before stopping, even
     * when it refuses the job. Skipping that crashes the app, so the notification must exist.
     */
    private fun assertRefusedAfterForeground(intent: Intent) {
        val service = Robolectric.buildService(WorkerService::class.java, intent)
            .create()
            .startCommand(0, 1)
            .get()

        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    private fun startJobIntent(): Intent {
        val application = RuntimeEnvironment.getApplication()
        val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = Mockito.mock(UsbDevice::class.java)
        Mockito.`when`(usbDevice.deviceName).thenReturn("/dev/bus/usb/001/007")
        shadowOf(usbManager).addOrUpdateUsbDevice(usbDevice, true)
        return getStartJobIntent(
            Uri.parse("content://vendroid/boot.iso"),
            UsbMassStorageDeviceDescriptor(maybeUsbDevice = usbDevice),
            jobId = 7,
            packageContext = application,
            cls = WorkerService::class.java,
        )
    }
}
