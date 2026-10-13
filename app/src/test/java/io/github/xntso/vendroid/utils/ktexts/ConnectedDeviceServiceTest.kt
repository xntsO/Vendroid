package io.github.xntso.vendroid.utils.ktexts

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.service.WorkerService
import io.github.xntso.vendroid.utils.exception.MissingPermissionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class ConnectedDeviceServiceTest {
    private lateinit var usbManager: UsbManager
    private lateinit var service: Service
    private lateinit var notification: Notification
    private lateinit var device: UsbDevice

    @BeforeEach
    fun setUp() {
        usbManager = RuntimeEnvironment.getApplication().getSystemService(Context.USB_SERVICE) as UsbManager
        service = Mockito.mock(Service::class.java)
        Mockito.`when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager)
        notification = Notification()
        device = usbDevice("/dev/bus/usb/001/007")
        shadowOf(usbManager).addOrUpdateUsbDevice(device, false)
    }

    @Test
    fun `missing selected-device permission never starts the foreground service`() {
        assertFalse(usbManager.hasPermission(device))

        assertThrows<MissingPermissionException> {
            service.startForegroundConnectedDevice(42, notification, device)
        }

        Mockito.verify(service).getSystemService(Context.USB_SERVICE)
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test
    fun `USB grant starts the connected-device foreground type`() {
        shadowOf(usbManager).addOrUpdateUsbDevice(device, true)
        assertTrue(usbManager.hasPermission(device))

        service.startForegroundConnectedDevice(42, notification, device)

        Mockito.verify(service).getSystemService(Context.USB_SERVICE)
        Mockito.verify(service).startForeground(
            42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test
    fun `permission lost after disconnect requires a fresh grant before resuming`() {
        shadowOf(usbManager).addOrUpdateUsbDevice(device, true)
        shadowOf(usbManager).removeUsbDevice(device)
        val reconnected = usbDevice("/dev/bus/usb/001/019")
        shadowOf(usbManager).addOrUpdateUsbDevice(reconnected, false)

        assertThrows<MissingPermissionException> {
            service.startForegroundConnectedDevice(42, notification, reconnected)
        }
        Mockito.verify(service).getSystemService(Context.USB_SERVICE)
        Mockito.verifyNoMoreInteractions(service)
        Mockito.clearInvocations(service)

        shadowOf(usbManager).addOrUpdateUsbDevice(reconnected, true)
        service.startForegroundConnectedDevice(42, notification, reconnected)

        Mockito.verify(service).getSystemService(Context.USB_SERVICE)
        Mockito.verify(service).startForeground(
            42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test
    fun `permission for a different USB cannot authorize the selected device`() {
        val other = usbDevice("/dev/bus/usb/001/020")
        shadowOf(usbManager).addOrUpdateUsbDevice(other, true)

        assertThrows<MissingPermissionException> {
            service.startForegroundConnectedDevice(42, notification, device)
        }
        Mockito.verify(service).getSystemService(Context.USB_SERVICE)
        Mockito.verifyNoMoreInteractions(service)
    }

    @Test
    fun `merged manifest declares connected-device foreground permission and type`() {
        val application = RuntimeEnvironment.getApplication()
        val serviceInfo = application.packageManager.getServiceInfo(
            ComponentName(application, WorkerService::class.java), 0,
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, serviceInfo.foregroundServiceType)
        val permissions = application.packageManager.getPackageInfo(
            application.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.toSet()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE in permissions)
        assertFalse(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE in permissions)
    }

    private fun usbDevice(name: String): UsbDevice = Mockito.mock(UsbDevice::class.java).also {
        Mockito.`when`(it.deviceName).thenReturn(name)
    }
}
