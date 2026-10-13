package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.massstorage.UsbMassStorageDeviceDescriptor
import io.github.xntso.vendroid.ventoy.VentoyDiskInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class VentoyScanRequest(val device: UsbMassStorageDeviceDescriptor, val generation: Int)

internal data class VentoyDriveScanResult(
    val diskInfo: VentoyDiskInfo?,
    val hasAnyPartition: Boolean,
    val diskSizeBytes: Long,
    val bundledVersion: String,
)

internal suspend fun runVentoyScan(
    viewModel: ConfirmOperationActivityViewModel,
    request: VentoyScanRequest,
    supportsGpt: Boolean,
    supportsLargeDrives: Boolean,
    onError: (Exception) -> Unit,
    readDrive: suspend () -> VentoyDriveScanResult,
) {
    if (viewModel.state.value.ventoyScanRequest != request) return
    viewModel.setVentoyScanning()
    try {
        val result = readDrive()
        currentCoroutineContext().ensureActive()
        if (viewModel.state.value.ventoyScanRequest != request) return
        viewModel.setVentoyScanResult(
            result.diskInfo, result.hasAnyPartition, result.diskSizeBytes, result.bundledVersion,
            supportsGpt, supportsLargeDrives,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        if (viewModel.state.value.ventoyScanRequest == request) {
            onError(exception)
            viewModel.setVentoyScanError(exception.message)
        }
    }
}
