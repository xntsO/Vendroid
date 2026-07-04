package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import kotlinx.parcelize.Parcelize

@Parcelize
class VerificationFailedException : FatalException("Verification failed") {
    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.the_usb_device_content_does_not_match_the_image)
    }
}