package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import kotlinx.parcelize.Parcelize

@Parcelize
class OpenFileException(override val message: String, override val cause: Throwable?) :
    FatalException(message, cause) {

    override fun getUiMessage(context: Context): String {
        return context.getString(R.string.could_not_open_the_source_file)
    }
}