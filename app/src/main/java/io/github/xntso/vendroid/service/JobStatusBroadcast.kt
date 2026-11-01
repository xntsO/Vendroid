package io.github.xntso.vendroid.service

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

internal fun Intent.enqueueJobStatus(context: Context) {
    // Keep every status on the main delivery queue. Synchronous worker delivery
    // can overtake queued progress and let it overwrite a finished/error state.
    LocalBroadcastManager.getInstance(context).sendBroadcast(this)
}

/**
 * The result of the current job. Status broadcasts reach only a resumed screen, so a job ending
 * while the screen is paused or still opening would otherwise look stuck once it resumes.
 */
internal object RetainedJobResult {
    private var jobId: Int? = null
    private var result: Intent? = null

    @Synchronized
    fun retain(jobId: Int, result: Intent) {
        this.jobId = jobId
        this.result = result
    }

    /** Hands the result to one reader, so a later resume cannot replay it over a retry. */
    @Synchronized
    fun take(jobId: Int): Intent? {
        if (this.jobId != jobId) return null
        return result.also { clear() }
    }

    @Synchronized
    fun clear() {
        jobId = null
        result = null
    }
}
