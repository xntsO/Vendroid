package io.github.xntso.vendroid.plugins.reviews

interface IWriteReviewHelper {
    val isGPlayFlavor: Boolean
    fun launchReviewFlow()
}