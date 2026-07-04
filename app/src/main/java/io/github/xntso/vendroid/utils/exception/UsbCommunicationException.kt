package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.RecoverableException
import io.github.xntso.vendroid.utils.ktexts.rootCause
import io.github.xntso.vendroid.utils.ktexts.toHRSize
import kotlinx.parcelize.Parcelize
import me.jahnen.libaums.libusbcommunication.LibusbError.NO_DEVICE
import me.jahnen.libaums.libusbcommunication.LibusbException

@Parcelize
open class UsbCommunicationException(
    override val cause: Throwable? = null,
) : RecoverableException(
    "Communication failed", cause
) {
    override fun getUiMessage(context: Context): String {
        val rootCause = rootCause
        if (rootCause is LibusbException) {
            val error = rootCause.libusbError
            if (error == NO_DEVICE) {
                return context.getString(R.string.the_usb_drive_was_unplugged)
            }
        }
        return context.getString(R.string.the_usb_drive_stopped_responding)
    }
}