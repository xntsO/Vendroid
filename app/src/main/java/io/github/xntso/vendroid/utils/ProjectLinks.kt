package io.github.xntso.vendroid.utils

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

object ProjectLinks {
    const val WEBSITE = "https://vendroid.fhas.sa"
    const val HELP = "$WEBSITE/#faq"

    /** The EtchDroid commit that Vendroid was derived from on 2026-07-04. */
    const val ETCHDROID_BASE_COMMIT = "e2eda57136d46fd3c465dee2e5ab31cdcb019a6e"
    const val ETCHDROID_SOURCE = "https://github.com/EtchDroid/EtchDroid/tree/$ETCHDROID_BASE_COMMIT"

    /** The Ventoy source tree that the bundled payload was built from. */
    fun ventoySource(commit: String) = "https://github.com/ventoy/Ventoy/tree/$commit"

    fun open(context: Context, url: String = WEBSITE) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
