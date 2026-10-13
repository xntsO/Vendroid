package io.github.xntso.vendroid.utils.ktexts

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.github.xntso.vendroid.VendroidApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

// This JUnit5 extension reads SDK configuration only from the outer test class.
@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [23])
class ConnectedDeviceServiceApi23Test {
    @Test
    fun `minimum supported Android uses the compatible foreground call`() = assertGrantedUsbForegroundCall(null)
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [28])
class ConnectedDeviceServiceApi28Test {
    @Test
    fun `Android 9 uses the compatible foreground call`() = assertGrantedUsbForegroundCall(null)
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [29])
class ConnectedDeviceServiceApi29Test {
    @Test
    fun `Android 10 uses the connected-device foreground type`() =
        assertGrantedUsbForegroundCall(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [34])
class ConnectedDeviceServiceApi34Test {
    @Test
    fun `Android 14 uses the connected-device foreground type`() =
        assertGrantedUsbForegroundCall(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
}

private fun assertGrantedUsbForegroundCall(expectedType: Int?) {
    val usbManager = RuntimeEnvironment.getApplication().getSystemService(Context.USB_SERVICE) as UsbManager
    val device = Mockito.mock(UsbDevice::class.java)
    Mockito.`when`(device.deviceName).thenReturn("/dev/bus/usb/001/007")
    shadowOf(usbManager).addOrUpdateUsbDevice(device, true)
    val service = Mockito.mock(Service::class.java)
    Mockito.`when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager)
    val notification = Notification()

    service.startForegroundConnectedDevice(42, notification, device)

    Mockito.verify(service).getSystemService(Context.USB_SERVICE)
    if (expectedType == null) {
        Mockito.verify(service).startForeground(42, notification)
    } else {
        Mockito.verify(service).startForeground(42, notification, expectedType)
    }
    Mockito.verifyNoMoreInteractions(service)
}
