package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VENTOY_INSTALL_URI
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.massstorage.PreviewUsbDevice
import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.IOException

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class, sdk = [35])
class VentoyScanRequestTest {
    private val device = device("Original USB")
    private val result = VentoyDriveScanResult(null, false, 256L * 1024 * 1024, "1.1.16")

    @Test
    fun `entering Scanning preserves the effect key until pending IO completes`() = runBlocking {
        withTimeout(5000) {
            val viewModel = readyViewModel()
            val request = viewModel.state.value.ventoyScanRequest!!
            val releaseRead = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                scan(viewModel, request) {
                    releaseRead.await()
                    result
                }
            }

            assertEquals(VentoyDriveState.Scanning, viewModel.state.value.ventoyDriveState)
            assertEquals(request, viewModel.state.value.ventoyScanRequest)
            assertTrue(job.isActive)
            releaseRead.complete(Unit)
            job.join()
            assertEquals(VentoyDriveState.ReadyToInstall, viewModel.state.value.ventoyDriveState)
            assertNull(viewModel.state.value.ventoyScanRequest)
        }
    }

    @Test
    fun `explicit retry produces a fresh request after a failed scan`() = runBlocking {
        val viewModel = readyViewModel()
        val first = viewModel.state.value.ventoyScanRequest!!
        scan(viewModel, first) { throw IOException("Fixture read failed") }
        assertEquals(VentoyDriveState.ScanFailed, viewModel.state.value.ventoyDriveState)
        assertNull(viewModel.state.value.ventoyScanRequest)

        viewModel.retryVentoyScan()
        val retry = viewModel.state.value.ventoyScanRequest!!
        assertNotEquals(first, retry)
        scan(viewModel, retry) { result }
        assertEquals(VentoyDriveState.ReadyToInstall, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `revoked detached and replaced USB requests reject stale success and failure`() = runBlocking {
        withTimeout(5000) {
            val mutations: List<(ConfirmOperationActivityViewModel) -> Unit> = listOf(
                { it.setPermission(false) },
                { it.setState(it.state.value.copy(selectedDevice = null)) },
                {
                    it.init(VENTOY_INSTALL_URI, device("Replacement USB"), Intents.OPERATION_VENTOY_INSTALL)
                    it.setPermission(true)
                },
            )
            for (mutate in mutations) {
                for (fail in listOf(false, true)) {
                    val viewModel = readyViewModel()
                    val request = viewModel.state.value.ventoyScanRequest!!
                    val releaseRead = CompletableDeferred<Unit>()
                    val job = launch(start = CoroutineStart.UNDISPATCHED) {
                        scan(viewModel, request) {
                            releaseRead.await()
                            if (fail) throw IOException("Stale read failed")
                            result
                        }
                    }
                    mutate(viewModel)
                    val expected = viewModel.state.value
                    releaseRead.complete(Unit)
                    job.join()
                    assertEquals(expected, viewModel.state.value)
                    val replacement = viewModel.state.value.ventoyScanRequest
                    if (replacement != null) {
                        scan(viewModel, replacement) { result }
                        assertEquals(VentoyDriveState.ReadyToInstall, viewModel.state.value.ventoyDriveState)
                    }
                }
            }
        }
    }

    @Test
    fun `cancelled IO cannot apply a late result even when native work ignores cancellation`() = runBlocking {
        withTimeout(5000) {
            val viewModel = readyViewModel()
            val request = viewModel.state.value.ventoyScanRequest!!
            val releaseRead = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                scan(viewModel, request) {
                    withContext(NonCancellable) {
                        releaseRead.await()
                        result
                    }
                }
            }
            job.cancel()
            releaseRead.complete(Unit)
            job.cancelAndJoin()
            assertEquals(VentoyDriveState.Scanning, viewModel.state.value.ventoyDriveState)
        }
    }

    private suspend fun scan(
        viewModel: ConfirmOperationActivityViewModel,
        request: VentoyScanRequest,
        readDrive: suspend () -> VentoyDriveScanResult,
    ) = runVentoyScan(viewModel, request, false, false, {}, readDrive)

    private fun readyViewModel() = ConfirmOperationActivityViewModel().apply {
        init(VENTOY_INSTALL_URI, device, Intents.OPERATION_VENTOY_INSTALL)
        assertNull(state.value.ventoyScanRequest)
        setPermission(true)
    }

    private fun device(name: String) = UsbMassStorageDeviceDescriptor(
        previewUsbDevice = PreviewUsbDevice(name, "1234:5678"),
    )
}
