package io.github.xntso.vendroid.plugins.reviews

import android.app.Activity
import android.content.Intent
import androidx.core.net.toUri

class WriteReviewHelper(private val mActivity: Activity) : IWriteReviewHelper {
    override val isGPlayFlavor: Boolean
        get() = false

    override fun launchReviewFlow() {
        mActivity.startActivity(
            Intent(Intent.ACTION_VIEW, "https://github.com/Vendroid/Vendroid/".toUri())
        )
    }
}