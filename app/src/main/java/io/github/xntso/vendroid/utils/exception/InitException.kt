package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.RecoverableException
import kotlinx.parcelize.Parcelize

@Parcelize
open class InitException(override val message: String, override val cause: Throwable? = null) :
    RecoverableException(message, cause) {

    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.the_device_is_not_responding)
    }
}
