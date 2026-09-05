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
            supportsLargeDrives = true,
        )

        assertEquals(VentoyDriveState.ReadyToRepair, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `stable accepts MBR at exactly 2 TiB`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = null,
            hasAnyPartition = false,
            diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES,
            bundledVersion = "1.1.16",
        )

        assertEquals(VentoyDriveState.ReadyToInstall, viewModel.state.value.ventoyDriveState)
        assertEquals(VentoyPartitionStyle.Mbr, viewModel.state.value.ventoyOptions.partitionStyle)
    }

    @Test
    fun `promoting stable GPT does not admit a new large drive`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = null,
            hasAnyPartition = false,
            diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512,
            bundledVersion = "1.1.16",
            supportsGpt = true,
        )

        assertEquals(VentoyDriveState.RequiresPreview, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `promoted stable GPT permits ordinary GPT maintenance`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.1.16", VentoyPartitionStyle.Gpt),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
            supportsGpt = true,
            supportsLargeDrives = false,
        )

        assertEquals(VentoyDriveState.ReadyToRepair, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `large drive gate precedes maintenance and force install after GPT promotion`() {
        for (forceInstall in listOf(false, true)) {
            for (version in listOf("1.1.15", "1.1.16", "1.2.0")) {
                for (style in VentoyPartitionStyle.entries) {
                    val viewModel = ConfirmOperationActivityViewModel()
                    viewModel.init(
                        openedImage = null,
                        selectedDevice = null,
                        operation = Intents.OPERATION_VENTOY_INSTALL,
                        forceInstall = forceInstall,
                    )
                    val largeDisk = diskInfo(version, style, needsRepair = true).copy(
                        diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512,
                    )

                    viewModel.setVentoyScanResult(
                        diskInfo = largeDisk,
                        hasAnyPartition = true,
                        diskSizeBytes = largeDisk.diskSizeBytes,
                        bundledVersion = "1.1.16",
                        supportsGpt = true,
                        supportsLargeDrives = false,
                    )

                    assertEquals(
                        VentoyDriveState.RequiresPreview,
                        viewModel.state.value.ventoyDriveState,
                        "forceInstall=$forceInstall, version=$version, style=$style",
                    )
                }
            }
        }
    }

    @Test
    fun `large capacity permission does not bypass the GPT gate`() {
        val viewModel = ConfirmOperationActivityViewModel()

        viewModel.setVentoyScanResult(
            diskInfo = diskInfo("1.1.16", VentoyPartitionStyle.Gpt),
            hasAnyPartition = true,
            diskSizeBytes = DISK_SIZE,
            bundledVersion = "1.1.16",
            supportsGpt = false,
            supportsLargeDrives = true,
        )

        assertEquals(VentoyDriveState.RequiresPreview, viewModel.state.value.ventoyDriveState)
    }

    @Test
    fun `each new installation starts with MBR including force install`() {
        val viewModel = ConfirmOperationActivityViewModel()
        for (forceInstall in listOf(false, true)) {
            viewModel.setVentoyOptions(VentoyJobOptions(partitionStyle = VentoyPartitionStyle.Gpt))

            viewModel.init(
                openedImage = null,
                selectedDevice = null,
                operation = Intents.OPERATION_VENTOY_INSTALL,
                forceInstall = forceInstall,
            )

            assertEquals(VentoyPartitionStyle.Mbr, viewModel.state.value.ventoyOptions.partitionStyle)
        }
    }

    @Test
    fun `unknown installed version blocks maintenance for both styles and payload sources`() {
        for (version in listOf(null, "unreadable")) {
            for (style in VentoyPartitionStyle.entries) {
                for (onlineVersion in listOf(null, "1.1.17")) {
                    val viewModel = ConfirmOperationActivityViewModel()
                    viewModel.setState(
                        viewModel.state.value.copy(
                            ventoyOptions = VentoyJobOptions(onlinePayloadVersion = onlineVersion),
                        ),
                    )

                    viewModel.setVentoyScanResult(
                        diskInfo = diskInfo(version, style),
                        hasAnyPartition = true,
                        diskSizeBytes = DISK_SIZE,
                        bundledVersion = "1.1.16",
                        supportsGpt = true,
                    )

                    assertEquals(
                        VentoyDriveState.UnknownVersion,
                        viewModel.state.value.ventoyDriveState,
                        "version=$version, style=$style, onlineVersion=$onlineVersion",
                    )
                }
            }
        }
    }

    @Test
    fun `unknown version permits only damaged GPT metadata repair`() {
        for (style in VentoyPartitionStyle.entries) {
            val viewModel = ConfirmOperationActivityViewModel()

            viewModel.setVentoyScanResult(
                diskInfo = diskInfo(null, style, needsRepair = true),
                hasAnyPartition = true,
                diskSizeBytes = DISK_SIZE,
                bundledVersion = "1.1.16",
                supportsGpt = true,
            )

            assertEquals(
                if (style == VentoyPartitionStyle.Gpt) {
                    VentoyDriveState.ReadyToRepair
                } else {
                    VentoyDriveState.UnknownVersion
                },
                viewModel.state.value.ventoyDriveState,
            )
            assertEquals(
                style == VentoyPartitionStyle.Gpt,
                viewModel.state.value.canRepairVentoyMetadata,
            )
        }
    }

    @Test
    fun `unknown GPT metadata repair cannot bypass stable support gates`() {
        for (supportsGpt in listOf(false, true)) {
            val viewModel = ConfirmOperationActivityViewModel()
            val size = if (supportsGpt) VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512 else DISK_SIZE

            viewModel.setVentoyScanResult(
                diskInfo = diskInfo(null, VentoyPartitionStyle.Gpt, needsRepair = true).copy(
                    diskSizeBytes = size,
                ),
                hasAnyPartition = true,
                diskSizeBytes = size,
                bundledVersion = "1.1.16",
                supportsGpt = supportsGpt,
            )

            assertEquals(VentoyDriveState.RequiresPreview, viewModel.state.value.ventoyDriveState)
        }
    }

    @Test
    fun `reconnecting repaired GPT with unknown version blocks payload replacement`() {
        val viewModel = ConfirmOperationActivityViewModel()
        for (needsRepair in listOf(true, false)) {
            viewModel.setVentoyScanResult(
                diskInfo = diskInfo(null, VentoyPartitionStyle.Gpt, needsRepair),
                hasAnyPartition = true,
                diskSizeBytes = DISK_SIZE,
                bundledVersion = "1.1.16",
                supportsGpt = true,
            )

            assertEquals(
                if (needsRepair) VentoyDriveState.ReadyToRepair else VentoyDriveState.UnknownVersion,
                viewModel.state.value.ventoyDriveState,
            )
            assertEquals(needsRepair, viewModel.state.value.canRepairVentoyMetadata)
        }
    }

    private fun diskInfo(
        version: String?,
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
