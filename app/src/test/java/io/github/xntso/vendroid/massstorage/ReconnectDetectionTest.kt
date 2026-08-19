package io.github.xntso.vendroid.massstorage

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.github.xntso.vendroid.massstorage.VendroidUsbMassStorageDevice.Companion.massStorageDevices
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [35])
class ReconnectDetectionTest {
    @Test
    fun `matches the same mass-storage interface after Android renumbers the device`() {
        val original = usbDevice(vendorId = 0x1234, deviceId = 7, deviceName = "/dev/bus/001")
        val reconnected = usbDevice(vendorId = 0x1234, deviceId = 19, deviceName = "/dev/bus/009")
        val expected = original.massStorageDevices.single()

        assertNotNull(expected.findMatchingForNew(reconnected))
    }

    @Test
    fun `rejects a different USB device during reconnect`() {
        val original = usbDevice(vendorId = 0x1234, deviceId = 7, deviceName = "/dev/bus/001")
        val other = usbDevice(vendorId = 0x5678, deviceId = 19, deviceName = "/dev/bus/009")
        val expected = original.massStorageDevices.single()

        assertNull(expected.findMatchingForNew(other))
    }

    private fun usbDevice(
        vendorId: Int,
        deviceId: Int,
        deviceName: String,
    ): UsbDevice {
        val inEndpoint = endpoint(address = 0x81, direction = UsbConstants.USB_DIR_IN)
        val outEndpoint = endpoint(address = 0x02, direction = UsbConstants.USB_DIR_OUT)
        val usbInterface = Mockito.mock(UsbInterface::class.java)
        Mockito.`when`(usbInterface.id).thenReturn(0)
        Mockito.`when`(usbInterface.interfaceClass).thenReturn(UsbConstants.USB_CLASS_MASS_STORAGE)
        Mockito.`when`(usbInterface.interfaceSubclass).thenReturn(6)
        Mockito.`when`(usbInterface.interfaceProtocol).thenReturn(80)
        Mockito.`when`(usbInterface.endpointCount).thenReturn(2)
        Mockito.`when`(usbInterface.getEndpoint(0)).thenReturn(inEndpoint)
        Mockito.`when`(usbInterface.getEndpoint(1)).thenReturn(outEndpoint)

        return Mockito.mock(UsbDevice::class.java).also { device ->
            Mockito.`when`(device.manufacturerName).thenReturn("Vendroid Test")
            Mockito.`when`(device.productName).thenReturn("USB Drive")
            Mockito.`when`(device.vendorId).thenReturn(vendorId)
            Mockito.`when`(device.productId).thenReturn(0x4321)
            Mockito.`when`(device.deviceClass).thenReturn(0)
            Mockito.`when`(device.deviceSubclass).thenReturn(0)
            Mockito.`when`(device.deviceProtocol).thenReturn(0)
            Mockito.`when`(device.configurationCount).thenReturn(1)
            Mockito.`when`(device.interfaceCount).thenReturn(1)
            Mockito.`when`(device.version).thenReturn("3.00")
            Mockito.`when`(device.deviceId).thenReturn(deviceId)
            Mockito.`when`(device.deviceName).thenReturn(deviceName)
            Mockito.`when`(device.getInterface(0)).thenReturn(usbInterface)
        }
    }

    private fun endpoint(address: Int, direction: Int): UsbEndpoint =
        Mockito.mock(UsbEndpoint::class.java).also { endpoint ->
            Mockito.`when`(endpoint.address).thenReturn(address)
            Mockito.`when`(endpoint.attributes).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            Mockito.`when`(endpoint.maxPacketSize).thenReturn(512)
            Mockito.`when`(endpoint.interval).thenReturn(0)
            Mockito.`when`(endpoint.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            Mockito.`when`(endpoint.direction).thenReturn(direction)
        }
}
