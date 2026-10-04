package io.github.xntso.vendroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VENTOY_INSTALL_URI
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.getErrorIntent
import io.github.xntso.vendroid.getFinishedIntent
import io.github.xntso.vendroid.getProgressUpdateIntent
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.ui.JobState
import io.github.xntso.vendroid.ui.ProgressActivityViewModel
import io.github.xntso.vendroid.utils.exception.InitException
import io.github.xntso.vendroid.utils.exception.ServiceTimeoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class JobStatusBroadcastTest {
    private val device = UsbMassStorageDeviceDescriptor(
        previewUsbDevice = PreviewUsbDevice("Test USB", "1234:5678"),
    )
    private val operations = listOf(
        Intents.OPERATION_WRITE_IMAGE,
        Intents.OPERATION_VENTOY_INSTALL,
        Intents.OPERATION_VENTOY_UPDATE,
    )

    @BeforeEach
    @AfterEach
    fun resetLocalBroadcastManager() {
        // Robolectric resets the Android environment between methods, but the
        // AndroidX singleton can retain a Handler bound to the previous Looper.
        ReflectionHelpers.setStaticField(LocalBroadcastManager::class.java, "mInstance", null)
    }

    @Test
    fun `finished follows queued progress and leaves the screen successful`() {
        for (operation in operations) {
            assertTerminalFollowsProgress(
                getFinishedIntent(
                    sourceUri = VENTOY_INSTALL_URI,
                    destDevice = device,
                    totalBytes = 100,
                    operation = operation,
                ),
                operation,
                JobState.SUCCESS,
            )
        }
    }

    @Test
    fun `recoverable error follows queued progress and preserves the recovery screen`() {
        for (operation in operations) {
            assertTerminalFollowsProgress(
                getErrorIntent(
                    sourceUri = VENTOY_INSTALL_URI,
                    destDevice = device,
                    jobId = 42,
                    processedBytes = 99,
                    totalBytes = 100,
                    exception = InitException("Fixture failure"),
                    operation = operation,
                ),
                operation,
                JobState.RECOVERABLE_ERROR,
            )
        }
    }

    @Test
    fun `fatal error follows queued progress and preserves the failure screen`() {
        for (operation in operations) {
            assertTerminalFollowsProgress(
                getErrorIntent(
                    sourceUri = VENTOY_INSTALL_URI,
                    destDevice = device,
                    jobId = 42,
                    processedBytes = 99,
                    totalBytes = 100,
                    exception = ServiceTimeoutException(),
                    operation = operation,
                ),
                operation,
                JobState.FATAL_ERROR,
            )
        }
    }

    private fun assertTerminalFollowsProgress(terminal: Intent, operation: String, expectedState: JobState) {
        val context = RuntimeEnvironment.getApplication()
        val broadcasts = LocalBroadcastManager.getInstance(context)
        val viewModel = ProgressActivityViewModel()
        val received = mutableListOf<String?>()
        val receiverThreads = mutableListOf<Thread>()
        val progressSnapshotStarted = CountDownLatch(1)
        val terminalPosted = CountDownLatch(1)
        var firstProgress = true

        // Hold an older main-thread snapshot while the worker posts the result.
        // A synchronous terminal send delivers SUCCESS/ERROR on the worker here,
        // then the older progress snapshot resets the ViewModel to IN_PROGRESS.
        val gate = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (firstProgress) {
                    firstProgress = false
                    progressSnapshotStarted.countDown()
                    assertTrue(terminalPosted.await(5, TimeUnit.SECONDS), "Worker did not post its result")
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received += intent.action
                receiverThreads += Thread.currentThread()
                viewModel.updateFromIntent(intent)
            }
        }
        broadcasts.registerReceiver(gate, IntentFilter(Intents.JOB_PROGRESS))
        broadcasts.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intents.JOB_PROGRESS)
            addAction(Intents.FINISHED)
            addAction(Intents.ERROR)
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (percent in listOf(99L, 100L)) {
                getProgressUpdateIntent(
                    sourceUri = VENTOY_INSTALL_URI,
                    destDevice = device,
                    jobId = 42,
                    speed = 0f,
                    processedBytes = percent,
                    totalBytes = 100,
                    operation = operation,
                ).enqueueJobStatus(context)
            }
            assertTrue(received.isEmpty(), "Status delivery must remain queued until the main Looper runs")
            val worker = executor.submit {
                try {
                    assertTrue(progressSnapshotStarted.await(5, TimeUnit.SECONDS), "Progress was not dispatched")
                    terminal.enqueueJobStatus(context)
                } finally {
                    terminalPosted.countDown()
                }
            }

            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                0L,
                progressSnapshotStarted.count,
                "Queued progress was not dispatched by the current main Looper",
            )
            worker.get(5, TimeUnit.SECONDS)

            assertEquals(listOf(Intents.JOB_PROGRESS, Intents.JOB_PROGRESS, terminal.action), received, operation)
            assertEquals(expectedState, viewModel.state.value.jobState, operation)
            assertEquals(operation, viewModel.state.value.operation)
            for (thread in receiverThreads) {
                assertSame(Looper.getMainLooper().thread, thread, "Status must be delivered on the main thread")
            }
        } finally {
            progressSnapshotStarted.countDown()
            terminalPosted.countDown()
            executor.shutdownNow()
            broadcasts.unregisterReceiver(gate)
            broadcasts.unregisterReceiver(receiver)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
