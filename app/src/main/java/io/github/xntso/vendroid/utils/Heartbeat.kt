package io.github.xntso.vendroid.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tells a heartbeat when real progress stopped, so it cannot hide a hung worker forever. */
class ProgressStallDetector(
    private val limitMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastProgressMillis = clock()

    val isStalled: Boolean
        get() = clock() - lastProgressMillis >= limitMillis

    fun recordProgress() {
        lastProgressMillis = clock()
    }
}

/**
 * Runs [work] while calling [beat] every [periodMillis] from another thread, so blocking work
 * can show it is alive. No beat can follow the return of this function.
 */
suspend fun withHeartbeat(
    periodMillis: Long,
    beat: () -> Unit,
    work: suspend () -> Unit,
) = coroutineScope {
    val heartbeat = launch(Dispatchers.Default) {
        while (isActive) {
            delay(periodMillis)
            beat()
        }
    }
    try {
        work()
    } finally {
        heartbeat.cancelAndJoin()
    }
}
