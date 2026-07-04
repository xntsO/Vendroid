package io.github.xntso.vendroid.utils.exception

import android.content.Context
import io.github.xntso.vendroid.R
import io.github.xntso.vendroid.utils.exception.base.FatalException
import io.github.xntso.vendroid.utils.ktexts.toHRSize
import kotlinx.parcelize.Parcelize

@Parcelize
open class NotEnoughSpaceException(val sourceSize: Long, val destSize: Long) :
    FatalException("Image size ($sourceSize) is larger than device size ($destSize)") {

    override fun getUiMessage(context: Context): String {
        return context.getString(
            R.string.the_image_is_is_larger_than_the_device, sourceSize.toHRSize(),
            destSize.toHRSize()
        )
    }
}