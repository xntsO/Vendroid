package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import kotlinx.parcelize.Parcelize

@Parcelize
class UsbDriveTooLargeException :
    FatalException("The USB drive is too large and Vendroid does not support it") {
    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.the_usb_drive_is_too_large)
    }
}
