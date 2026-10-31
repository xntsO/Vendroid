package io.github.xntso.vendroid.utils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

class HeartbeatTest {
    @Test
    fun `beats while blocking work runs and stops when it returns`() {
        val beats = AtomicInteger()

        runBlocking {
            withHeartbeat(periodMillis = 10, beat = { beats.incrementAndGet() }) {
                Thread.sleep(200)
            }
        }

        val beatsAtReturn = beats.get()
        assertTrue(beatsAtReturn >= 2, "Expected repeated beats, saw $beatsAtReturn")
        Thread.sleep(50)
        assertEquals(beatsAtReturn, beats.get())
    }

    @Test
    fun `failed work stops the heartbeat and keeps its exception`() {
        val beats = AtomicInteger()

        assertThrows<IllegalStateException> {
            runBlocking {
                withHeartbeat(periodMillis = 10, beat = { beats.incrementAndGet() }) {
                    Thread.sleep(50)
                    throw IllegalStateException("USB write failed")
                }
            }
        }

        val beatsAtReturn = beats.get()
        Thread.sleep(50)
        assertEquals(beatsAtReturn, beats.get())
    }
}
