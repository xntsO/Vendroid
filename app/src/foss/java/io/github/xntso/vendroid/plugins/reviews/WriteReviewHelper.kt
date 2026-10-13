package io.github.xntso.vendroid.plugins.reviews

import android.app.Activity
import io.github.xntso.vendroid.online.ExternalProjectLinks

class WriteReviewHelper(private val mActivity: Activity) : IWriteReviewHelper {
    override val isGPlayFlavor: Boolean
        get() = false

    override fun launchReviewFlow() {
        ExternalProjectLinks().open(mActivity, "https://github.com/xntsO/Vendroid/")
    }
}
