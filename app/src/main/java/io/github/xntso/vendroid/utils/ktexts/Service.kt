package io.github.xntso.vendroid.utils.ktexts

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import io.github.xntso.vendroid.utils.exception.MissingPermissionException

fun Service.startForegroundConnectedDevice(id: Int, notification: Notification, device: UsbDevice) {
    // USB grants satisfy connectedDevice without requesting unrelated permissions.
    // Check the selected device again because permission can disappear on disconnect.
    val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
    if (!usbManager.hasPermission(device)) throw MissingPermissionException()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } else {
        startForeground(id, notification)
    }
}
