package io.github.xntso.vendroid.utils

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

object ProjectLinks {
    const val WEBSITE = "https://vendroid.fhas.sa"
    const val HELP = "$WEBSITE/#faq"

    fun open(context: Context, url: String = WEBSITE) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}
