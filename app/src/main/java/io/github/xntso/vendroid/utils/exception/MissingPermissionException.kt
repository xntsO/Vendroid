package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.RecoverableException
import kotlinx.parcelize.Parcelize

@Parcelize
class MissingPermissionException : RecoverableException("Missing permission to access USB device") {
    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.missing_permission_to_access_the_usb_device)
    }
}