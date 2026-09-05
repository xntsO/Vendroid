package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.ventoy.VentoyDiskLayout
import io.github.xntso.vendroid.ventoy.VentoyPartitionStyle
import io.github.xntso.vendroid.ventoy.VentoyVersionRelation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VentoyInstallPolicyTest {
    @Test
    fun `stable allows MBR through exactly 2 TiB and rejects GPT`() {
        for (size in listOf(8L * 1024 * 1024 * 1024, LIMIT - 512, LIMIT)) {
            assertTrue(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Mbr, size, false, false))
            assertFalse(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Gpt, size, false, false))
        }
    }

    @Test
    fun `promoted stable GPT allows both styles through 2 TiB but neither above it`() {
        for (style in VentoyPartitionStyle.entries) {
            assertTrue(isVentoyInstallSelectionSupported(style, LIMIT, true, false))
            assertFalse(isVentoyInstallSelectionSupported(style, LIMIT + 512, true, false))
            assertFalse(isVentoyInstallSelectionSupported(style, 3 * LIMIT / 2, true, false))
        }
    }

    @Test
    fun `Preview requires explicit GPT selection for a large drive`() {
        assertTrue(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Mbr, LIMIT, true, true))
        for (size in listOf(LIMIT + 512, 3 * LIMIT / 2)) {
            assertFalse(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Mbr, size, true, true))
            assertTrue(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Gpt, size, true, true))
        }
    }

    @Test
    fun `large capacity permission cannot enable GPT`() {
        assertFalse(isVentoyInstallSelectionSupported(VentoyPartitionStyle.Gpt, LIMIT, false, true))
        for (style in VentoyPartitionStyle.entries) {
            assertFalse(isVentoyInstallSelectionSupported(style, LIMIT + 512, false, true))
        }
    }

    @Test
    fun `target changes keep unknown and newer payloads blocked unless metadata can be repaired`() {
        assertEquals(
            VentoyDriveState.UnknownVersion,
            ventoyMaintenanceState(VentoyVersionRelation.Unknown, canRepairMetadata = false),
        )
        assertEquals(
            VentoyDriveState.NewerVersion,
            ventoyMaintenanceState(VentoyVersionRelation.Newer, canRepairMetadata = false),
        )
        for (relation in listOf(VentoyVersionRelation.Unknown, VentoyVersionRelation.Newer)) {
            assertEquals(
                VentoyDriveState.ReadyToRepair,
                ventoyMaintenanceState(relation, canRepairMetadata = true),
            )
        }
    }

    @Test
    fun `known compatible payloads retain update and repair actions`() {
        for (canRepairMetadata in listOf(false, true)) {
            assertEquals(
                VentoyDriveState.UpdateAvailable,
                ventoyMaintenanceState(VentoyVersionRelation.Older, canRepairMetadata),
            )
            assertEquals(
                VentoyDriveState.ReadyToRepair,
                ventoyMaintenanceState(VentoyVersionRelation.Same, canRepairMetadata),
            )
        }
    }

    private companion object {
        const val LIMIT = VentoyDiskLayout.MAX_MBR_DISK_BYTES
    }
}
