package io.github.xntso.vendroid.ventoy

class VentoyDiskScanner {
    fun scan(device: RawBlockDevice): VentoyDiskInfo? {
        if (device.blockSize != VentoyDiskLayout.SECTOR_SIZE) return null
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        if (!VentoyMbr.hasBootSignature(mbr)) return null

        val entries = VentoyMbr.parse(mbr)
        val part1 = entries[0]
        val part2 = entries[1]

        if (part1.startSector != VentoyDiskLayout.PARTITION1_START_SECTOR) return null
        if (part1.type != VentoyDiskLayout.PARTITION1_TYPE) return null
        if (part2.type != VentoyDiskLayout.PARTITION2_TYPE) return null
        if (part2.sectorCount != VentoyDiskLayout.PARTITION2_SECTOR_COUNT) return null
        if (part2.startSector != part1.startSector + part1.sectorCount) return null
        if (part2.startSector % 8L != 0L) return null
        if (part2.startSector + part2.sectorCount > device.sizeBytes / VentoyDiskLayout.SECTOR_SIZE) {
            return null
        }

        val installedVersion = runCatching {
            Fat16Reader(device, part2.startSector).readText("/ventoy/version")?.trim()
        }.getOrNull()

        return VentoyDiskInfo(
            diskSizeBytes = device.sizeBytes,
            partition1StartSector = part1.startSector,
            partition1EndSector = part1.startSector + part1.sectorCount - 1,
            partition2StartSector = part2.startSector,
            partition2EndSector = part2.startSector + part2.sectorCount - 1,
            installedVersion = installedVersion,
            supportedForUpgrade = true,
            reservedSpaceBytes = device.sizeBytes -
                (part2.startSector + part2.sectorCount) * VentoyDiskLayout.SECTOR_SIZE,
        )
    }

    fun hasAnyMbrPartition(device: RawBlockDevice): Boolean {
        if (device.blockSize != VentoyDiskLayout.SECTOR_SIZE) return false
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        if (!VentoyMbr.hasBootSignature(mbr)) return false
        return VentoyMbr.parse(mbr).any { it.type != 0 || it.startSector != 0L || it.sectorCount != 0L }
    }
}
