package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import kotlinx.parcelize.Parcelize

/** The drive returned different bytes than Vendroid wrote while installing Ventoy. */
@Parcelize
class VentoyVerificationFailedException(val deviceOffset: Long) :
    FatalException("Ventoy payload verification failed at device offset $deviceOffset.") {
    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.ventoy_verification_failed, deviceOffset)
    }
}
