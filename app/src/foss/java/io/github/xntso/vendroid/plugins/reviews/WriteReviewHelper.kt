package io.github.xntso.vendroid.plugins.reviews

import android.app.Activity
import io.github.xntso.vendroid.utils.ProjectLinks

class WriteReviewHelper(private val mActivity: Activity) : IWriteReviewHelper {
    override val isGPlayFlavor: Boolean
        get() = false

    override fun launchReviewFlow() {
        ProjectLinks.open(mActivity, "https://github.com/xntsO/Vendroid/")
    }
}
