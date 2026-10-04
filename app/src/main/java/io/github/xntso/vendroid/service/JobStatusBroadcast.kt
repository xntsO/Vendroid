package io.github.xntso.vendroid.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

internal fun Intent.enqueueJobStatus(context: Context) {
    // Keep every status on the main delivery queue. Synchronous worker delivery
    // can overtake queued progress and let it overwrite a finished/error state.
    LocalBroadcastManager.getInstance(context).sendBroadcast(this)
}
