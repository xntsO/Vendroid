package io.github.xntso.vendroid.ui

import io.github.xntso.vendroid.ventoy.VentoyDiskLayout
import io.github.xntso.vendroid.ventoy.VentoyPartitionStyle
import io.github.xntso.vendroid.ventoy.VentoyVersionRelation

internal fun isVentoyInstallSelectionSupported(
    partitionStyle: VentoyPartitionStyle,
    diskSizeBytes: Long,
    supportsGpt: Boolean,
    supportsLargeDrives: Boolean,
): Boolean {
    val isLargeDrive = diskSizeBytes > VentoyDiskLayout.MAX_MBR_DISK_BYTES
    if (isLargeDrive && !supportsLargeDrives) return false
    return when (partitionStyle) {
        VentoyPartitionStyle.Mbr -> !isLargeDrive
        VentoyPartitionStyle.Gpt -> supportsGpt
    }
}

internal fun ventoyMaintenanceState(
    versionRelation: VentoyVersionRelation,
    canRepairMetadata: Boolean,
): VentoyDriveState = when (versionRelation) {
    VentoyVersionRelation.Older -> VentoyDriveState.UpdateAvailable
    VentoyVersionRelation.Same -> VentoyDriveState.ReadyToRepair
    VentoyVersionRelation.Unknown -> if (canRepairMetadata) {
        VentoyDriveState.ReadyToRepair
    } else {
        VentoyDriveState.UnknownVersion
    }
    VentoyVersionRelation.Newer -> if (canRepairMetadata) {
        VentoyDriveState.ReadyToRepair
    } else {
        VentoyDriveState.NewerVersion
    }
}
