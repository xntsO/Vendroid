package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.Intents
import io.github.xntso.vendroid.VentoyJobOptions
import io.github.xntso.vendroid.ventoy.VentoyDiskInfo
import io.github.xntso.vendroid.ventoy.VentoyDiskLayout
import io.github.xntso.vendroid.ventoy.VentoyPartitionStyle
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

    @Test
    fun `downloaded payload becomes the update target`() {
        val viewModel = ConfirmOperationActivityViewModel()
        viewModel.setState(
            viewModel.state.value.copy(
                ventoyOptions = VentoyJobOptions(onlinePayloadVersion = "1.1.17"),
            ),
        )

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.1.16"),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
        )

        assertEquals(VentoyDriveState.UpdateAvailable, viewModel.state.value.ventoyDriveState)
        assertEquals("1.1.17", viewModel.state.value.targetVentoyVersion)
    }

    @Test
    fun `stable directs drives larger than 2 TiB to V-Preview`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = null,
            hasAnyPartition = false,
            diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512,
            bundledVersion = "1.1.16",
            supportsGpt = false,
        )

        assertEquals(VentoyDriveState.RequiresPreview, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `V-Preview allows GPT installation above 2 TiB`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = null,
            hasAnyPartition = false,
            diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512,
            bundledVersion = "1.1.16",
            supportsGpt = true,
        )

        assertEquals(VentoyDriveState.ReadyToInstall, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `stable routes an existing GPT drive to V-Preview without offering force install`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.1.16", VentoyPartitionStyle.Gpt),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
            supportsGpt = false,
        )

        assertEquals(VentoyDriveState.RequiresPreview, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `blocks a GPT downgrade`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.2.0", VentoyPartitionStyle.Gpt),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
            supportsGpt = true,
        )

        assertEquals(VentoyDriveState.NewerVersion, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `offers metadata repair without downgrading a newer GPT install`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo(
                version = "1.2.0",
                partitionStyle = VentoyPartitionStyle.Gpt,
                needsRepair = true,
            ),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
            supportsGpt = true,
        )

        assertEquals(VentoyDriveState.ReadyToRepair, viewModel.state.value.ventoyDriveState)
    }

    private fun diskInfo(
        version: String,
        partitionStyle: VentoyPartitionStyle = VentoyPartitionStyle.Mbr,
        needsRepair: Boolean = false,
    ) = VentoyDiskInfo(
        diskSizeBytes = DISK_SIZE,
        partition1StartSector = 2048,
        partition1EndSector = 1_000_000,
        partition2StartSector = 1_000_001,
        partition2EndSector = 1_065_536,
        installedVersion = version,
        supportedForUpgrade = true,
        reservedSpaceBytes = 0,
        partitionStyle = partitionStyle,
        needsRepair = needsRepair,
    )

    private companion object {
        const val DISK_SIZE = 8L * 1024 * 1024 * 1024
    }
}
