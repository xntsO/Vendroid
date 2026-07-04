package io.github.xntso.vendroid.ventoy

enum class VentoyPartitionStyle {
    Mbr,
}

data class VentoyInstallOptions(
    val forceInstall: Boolean = false,
    val label: String = "Ventoy",
    val partitionStyle: VentoyPartitionStyle = VentoyPartitionStyle.Mbr,
    val secureBoot: Boolean = true,
)

data class VentoyInstallPlan(
    val diskSizeBytes: Long,
    val partition1StartSector: Long,
    val partition1EndSector: Long,
    val partition2StartSector: Long,
    val partition2EndSector: Long,
    val payloadVersion: String,
) {
    val partition1SectorCount: Long
        get() = partition1EndSector - partition1StartSector + 1

    val partition2SectorCount: Long
        get() = partition2EndSector - partition2StartSector + 1
}

enum class VentoyInstallStage {
    ValidatingPayload,
    Partitioning,
    FormattingExFat,
    WritingBootloader,
    WritingVentoyPayload,
    Verifying,
    Complete,
}

data class VentoyInstallProgress(
    val stage: VentoyInstallStage,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
)

data class VentoyDiskInfo(
    val diskSizeBytes: Long,
    val partition1StartSector: Long,
    val partition1EndSector: Long,
    val partition2StartSector: Long,
    val partition2EndSector: Long,
    val installedVersion: String?,
    val supportedForUpgrade: Boolean,
)

internal object VentoyDiskLayout {
    const val SECTOR_SIZE = 512
    const val PARTITION1_START_SECTOR = 2048L
    const val PARTITION2_SECTOR_COUNT = 65536L
    const val MBR_BOOT_SIGNATURE_OFFSET = 510
    const val MBR_PARTITION_TABLE_OFFSET = 446
    const val VENTOY_UUID_OFFSET = 384
    const val DISK_SIGNATURE_OFFSET = 440
    const val MAX_MBR_DISK_BYTES = 2L * 1024L * 1024L * 1024L * 1024L
    const val MIN_PARTITION1_SECTORS = 2048L
    const val PARTITION1_TYPE = 0x07
    const val PARTITION2_TYPE = 0xEF

    fun plan(diskSizeBytes: Long, blockSize: Int, payloadVersion: String): VentoyInstallPlan {
        require(blockSize == SECTOR_SIZE) {
            "Vendroid only supports 512-byte logical USB blocks in this version."
        }
        require(diskSizeBytes % SECTOR_SIZE == 0L) {
            "Disk size must be aligned to 512-byte sectors."
        }
        require(diskSizeBytes <= MAX_MBR_DISK_BYTES) {
            "MBR Ventoy install is limited to disks up to 2 TiB."
        }

        val totalSectors = diskSizeBytes / SECTOR_SIZE
        var partition2Start = totalSectors - PARTITION2_SECTOR_COUNT
        partition2Start -= partition2Start % 8L
        val partition1End = partition2Start - 1
        val partition1Sectors = partition1End - PARTITION1_START_SECTOR + 1

        require(partition1Sectors >= MIN_PARTITION1_SECTORS) {
            "Disk is too small for the Ventoy partition layout."
        }
        require(partition2Start <= UInt.MAX_VALUE.toLong()) {
            "Partition start does not fit in an MBR entry."
        }
        require(partition1Sectors <= UInt.MAX_VALUE.toLong()) {
            "Partition size does not fit in an MBR entry."
        }

        return VentoyInstallPlan(
            diskSizeBytes = diskSizeBytes,
            partition1StartSector = PARTITION1_START_SECTOR,
            partition1EndSector = partition1End,
            partition2StartSector = partition2Start,
            partition2EndSector = partition2Start + PARTITION2_SECTOR_COUNT - 1,
            payloadVersion = payloadVersion,
        )
    }
}
