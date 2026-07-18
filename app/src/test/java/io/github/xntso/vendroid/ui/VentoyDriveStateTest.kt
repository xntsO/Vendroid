package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.ventoy.VentoyDiskInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VentoyDriveStateTest {
    @Test
    fun `offers update for an older supported installation`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.1.15"),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
        )

        assertEquals(VentoyDriveState.UpdateAvailable, viewModel.state.value.ventoyDriveState)
        assertEquals(Intents.OPERATION_VENTOY_UPDATE, viewModel.state.value.operation)
    }

    @Test
    fun `blocks a downgrade when the installed version is newer`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.2.0"),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
        )

        assertEquals(VentoyDriveState.NewerVersion, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `requires force install for an unrecognized partitioned drive`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = null,
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
        )

        assertEquals(VentoyDriveState.ExistingPartitions, viewModel.state.value.ventoyDriveState)
    }

    private fun diskInfo(version: String) = VentoyDiskInfo(
        diskSizeBytes = DISK_SIZE,
        partition1StartSector = 2048,
        partition1EndSector = 1_000_000,
        partition2StartSector = 1_000_001,
        partition2EndSector = 1_065_536,
        installedVersion = version,
        supportedForUpgrade = true,
        reservedSpaceBytes = 0,
    )

    private companion object {
        const val DISK_SIZE = 8L * 1024 * 1024 * 1024
    }
}
