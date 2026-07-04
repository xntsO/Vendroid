package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import kotlinx.parcelize.Parcelize

@Parcelize
class MissingDeviceException : FatalException("Worker service launched with null device") {
    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.worker_null_device_error)
    }
}