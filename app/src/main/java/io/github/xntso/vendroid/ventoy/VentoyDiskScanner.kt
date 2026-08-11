package io.github.xntso.vendroid.ventoy

class VentoyDiskScanner {
    fun scan(device: RawBlockDevice): VentoyDiskInfo? {
        if (device.blockSize != VentoyDiskLayout.SECTOR_SIZE) return null
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        if (!VentoyMbr.hasBootSignature(mbr)) return null

        val entries = VentoyMbr.parse(mbr)
        return if (entries.first().type == 0xEE && entries.first().startSector == 1L) {
            scanGpt(device)
        } else {
            scanMbr(device, entries)
        }
    }

    private fun scanMbr(
        device: RawBlockDevice,
        entries: List<MbrPartitionEntry>,
    ): VentoyDiskInfo? {
        val part1 = entries[0]
        val part2 = entries[1]

        if (part1.startSector != VentoyDiskLayout.PARTITION1_START_SECTOR) return null
        // Do not use the MBR type bytes as Ventoy identity. Partitioning tools and operating
        // systems can rewrite them without changing the standard Ventoy layout; Ventoy's own
        // detector intentionally ignores these bytes for the same reason.
        if (part2.sectorCount != VentoyDiskLayout.PARTITION2_SECTOR_COUNT) return null
        if (part2.startSector != part1.startSector + part1.sectorCount) return null
        if (part2.startSector % 8L != 0L) return null
        if (part2.startSector + part2.sectorCount > device.sizeBytes / VentoyDiskLayout.SECTOR_SIZE) {
            return null
        }

        return VentoyDiskInfo(
            diskSizeBytes = device.sizeBytes,
            partition1StartSector = part1.startSector,
            partition1EndSector = part1.startSector + part1.sectorCount - 1,
            partition2StartSector = part2.startSector,
            partition2EndSector = part2.startSector + part2.sectorCount - 1,
            installedVersion = readInstalledVersion(device, part2.startSector),
            supportedForUpgrade = true,
            reservedSpaceBytes = device.sizeBytes -
                (part2.startSector + part2.sectorCount) * VentoyDiskLayout.SECTOR_SIZE,
            partitionStyle = VentoyPartitionStyle.Mbr,
        )
    }

    private fun scanGpt(device: RawBlockDevice): VentoyDiskInfo? {
        val gpt = runCatching { VentoyGpt.read(device) }.getOrNull() ?: return null
        val part1 = gpt.partition1
        val part2 = gpt.partition2
        val totalSectors = device.sizeBytes / VentoyDiskLayout.SECTOR_SIZE

        if (part1.startSector != VentoyDiskLayout.PARTITION1_START_SECTOR) return null
        if (part2.sectorCount != VentoyDiskLayout.PARTITION2_SECTOR_COUNT) return null
        if (part2.startSector != part1.endSector + 1) return null
        if (part2.startSector % 8L != 0L) return null
        if (part2.endSector > totalSectors - VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT - 1) {
            return null
        }

        return VentoyDiskInfo(
            diskSizeBytes = device.sizeBytes,
            partition1StartSector = part1.startSector,
            partition1EndSector = part1.endSector,
            partition2StartSector = part2.startSector,
            partition2EndSector = part2.endSector,
            installedVersion = readInstalledVersion(device, part2.startSector),
            supportedForUpgrade = true,
            reservedSpaceBytes = device.sizeBytes -
                (part2.endSector + 1 + VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT) *
                VentoyDiskLayout.SECTOR_SIZE,
            partitionStyle = VentoyPartitionStyle.Gpt,
        )
    }

    private fun readInstalledVersion(device: RawBlockDevice, partitionStartSector: Long): String? =
        runCatching {
            val fat = Fat16Reader(device, partitionStartSector)
            fat.readText("/grub/grub.cfg")
                ?.let(VentoyVersion::fromGrubConfig)
                ?: fat.readText("/ventoy/version")?.trim()
        }.getOrNull()

    fun hasAnyPartition(device: RawBlockDevice): Boolean {
        if (device.blockSize != VentoyDiskLayout.SECTOR_SIZE) return false
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        if (VentoyMbr.hasBootSignature(mbr) &&
            VentoyMbr.parse(mbr).any {
                it.type != 0 || it.startSector != 0L || it.sectorCount != 0L
            }
        ) {
            return true
        }
        return device.sizeBytes >= 2L * VentoyDiskLayout.SECTOR_SIZE &&
            device.readBytes(VentoyDiskLayout.SECTOR_SIZE.toLong(), 8)
                .contentEquals("EFI PART".encodeToByteArray())
    }
}
