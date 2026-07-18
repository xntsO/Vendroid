package io.github.xntso.vendroid.ventoy

import java.util.Locale
data class ExFatFormatInfo(
    val partitionOffsetSector: Long,
    val volumeLengthSectors: Long,
    val fatOffsetSector: Long,
    val fatLengthSectors: Long,
    val clusterHeapOffsetSector: Long,
    val clusterCount: Long,
    val clusterSizeBytes: Int,
    val allocationBitmapFirstCluster: Long,
    val upcaseTableFirstCluster: Long,
    val rootDirectoryFirstCluster: Long,
    val bootChecksum: UInt,
)

class ExFatFormatter {
    fun format(
        device: RawBlockDevice,
        partitionStartSector: Long,
        partitionSectorCount: Long,
        label: String = "Ventoy",
        clusterSize: VentoyClusterSize = VentoyClusterSize.Automatic,
    ): ExFatFormatInfo {
        require(device.blockSize == VentoyDiskLayout.SECTOR_SIZE) {
            "exFAT formatter requires a 512-byte block device"
        }
        require(partitionStartSector >= 0 && partitionSectorCount > 0) {
            "Invalid exFAT partition range"
        }
        require(label.length in 1..11) { "exFAT volume label must be 1..11 UTF-16 code units" }

        val partitionSizeBytes = partitionSectorCount * VentoyDiskLayout.SECTOR_SIZE
        val clusterSizeBytes = clusterSize.bytes
            ?: if (partitionSizeBytes / GIB <= 32) 32 * 1024 else 128 * 1024
        val layout = calculateLayout(
            partitionStartSector,
            partitionSectorCount,
            clusterSizeBytes,
            label,
        )
        val partitionOffsetBytes = partitionStartSector * VentoyDiskLayout.SECTOR_SIZE

        val bootRegion = buildBootRegion(layout, label)
        device.write(partitionOffsetBytes, bootRegion)
        device.write(partitionOffsetBytes + BACKUP_BOOT_REGION_SECTOR * VentoyDiskLayout.SECTOR_SIZE, bootRegion)

        val fatBytes = layout.fatLengthSectors * VentoyDiskLayout.SECTOR_SIZE
        device.writeZeros(partitionOffsetBytes + layout.fatOffsetSector * VentoyDiskLayout.SECTOR_SIZE, fatBytes)
        writeFat(device, partitionOffsetBytes, layout)

        writeAllocationBitmap(device, partitionOffsetBytes, layout)
        writeUpcaseTable(device, partitionOffsetBytes, layout)
        writeRootDirectory(device, partitionOffsetBytes, layout, label)
        device.flush()

        return layout
    }

    private fun calculateLayout(
        partitionOffsetSector: Long,
        partitionSectorCount: Long,
        clusterSizeBytes: Int,
        label: String,
    ): ExFatFormatInfo {
        val sectorsPerCluster = clusterSizeBytes / VentoyDiskLayout.SECTOR_SIZE
        var clusterCount = 0L
        var fatLengthSectors: Long
        var clusterHeapOffsetSectors: Long

        while (true) {
            fatLengthSectors = alignUp(((clusterCount + 2) * 4), VentoyDiskLayout.SECTOR_SIZE.toLong()) /
                VentoyDiskLayout.SECTOR_SIZE
            clusterHeapOffsetSectors = alignUp(MAIN_AND_BACKUP_BOOT_SECTORS + fatLengthSectors, sectorsPerCluster.toLong())
            val nextClusterCount = (partitionSectorCount - clusterHeapOffsetSectors) / sectorsPerCluster
            require(nextClusterCount > 0) { "Partition is too small for exFAT" }
            if (nextClusterCount == clusterCount) break
            clusterCount = nextClusterCount
        }

        require(clusterCount <= 0xFFFF_FFF5L) { "exFAT cluster count is too large" }

        val allocationBitmapBytes = ceilDiv(clusterCount, 8)
        val allocationBitmapClusters = ceilDiv(allocationBitmapBytes, clusterSizeBytes.toLong())
        val upcaseTableBytes = buildUpcaseTable().size.toLong()
        val upcaseTableClusters = ceilDiv(upcaseTableBytes, clusterSizeBytes.toLong())
        val rootDirectoryClusters = 1L
        val usedClusters = allocationBitmapClusters + upcaseTableClusters + rootDirectoryClusters
        require(usedClusters <= clusterCount) { "Partition is too small for exFAT metadata" }

        val allocationBitmapFirstCluster = 2L
        val upcaseTableFirstCluster = allocationBitmapFirstCluster + allocationBitmapClusters
        val rootDirectoryFirstCluster = upcaseTableFirstCluster + upcaseTableClusters

        val layoutWithoutChecksum = ExFatFormatInfo(
            partitionOffsetSector = partitionOffsetSector,
            volumeLengthSectors = partitionSectorCount,
            fatOffsetSector = FAT_OFFSET_SECTOR,
            fatLengthSectors = fatLengthSectors,
            clusterHeapOffsetSector = clusterHeapOffsetSectors,
            clusterCount = clusterCount,
            clusterSizeBytes = clusterSizeBytes,
            allocationBitmapFirstCluster = allocationBitmapFirstCluster,
            upcaseTableFirstCluster = upcaseTableFirstCluster,
            rootDirectoryFirstCluster = rootDirectoryFirstCluster,
            bootChecksum = 0u,
        )
        val checksum = bootChecksum(buildBootRegion(layoutWithoutChecksum, label, includeChecksum = false))
        return layoutWithoutChecksum.copy(bootChecksum = checksum)
    }

    private fun buildBootRegion(
        layout: ExFatFormatInfo,
        label: String,
        includeChecksum: Boolean = true,
    ): ByteArray {
        val bootRegion = ByteArray(BOOT_REGION_SECTORS * VentoyDiskLayout.SECTOR_SIZE)
        val bootSector = bootRegion.copyOfRange(0, VentoyDiskLayout.SECTOR_SIZE)

        bootSector[0] = 0xEB.toByte()
        bootSector[1] = 0x76
        bootSector[2] = 0x90.toByte()
        "EXFAT   ".encodeToByteArray().copyInto(bootSector, 3)
        bootSector.writeUInt64Le(64, layout.partitionOffsetSector)
        bootSector.writeUInt64Le(72, layout.volumeLengthSectors)
        bootSector.writeUInt32Le(80, layout.fatOffsetSector)
        bootSector.writeUInt32Le(84, layout.fatLengthSectors)
        bootSector.writeUInt32Le(88, layout.clusterHeapOffsetSector)
        bootSector.writeUInt32Le(92, layout.clusterCount)
        bootSector.writeUInt32Le(96, layout.rootDirectoryFirstCluster)
        bootSector.writeUInt32Le(100, volumeSerial(label))
        bootSector.writeUInt16Le(104, 0x0100)
        bootSector.writeUInt16Le(106, 0)
        bootSector[108] = 9
        bootSector[109] = log2(layout.clusterSizeBytes / VentoyDiskLayout.SECTOR_SIZE).toByte()
        bootSector[110] = 1
        bootSector[111] = 0x80.toByte()
        bootSector[112] = 0
        bootSector[510] = 0x55
        bootSector[511] = 0xAA.toByte()
        bootSector.copyInto(bootRegion, 0)

        for (sector in 1..8) {
            val offset = sector * VentoyDiskLayout.SECTOR_SIZE
            bootRegion[offset + 510] = 0x55
            bootRegion[offset + 511] = 0xAA.toByte()
        }

        if (includeChecksum) {
            val checksum = layout.bootChecksum
            val checksumOffset = CHECKSUM_SECTOR * VentoyDiskLayout.SECTOR_SIZE
            for (entry in 0 until 128) {
                bootRegion.writeUInt32Le(checksumOffset + entry * 4, checksum.toLong())
            }
        }
        return bootRegion
    }

    private fun writeFat(device: RawBlockDevice, partitionOffsetBytes: Long, layout: ExFatFormatInfo) {
        fun writeEntry(cluster: Long, value: Long) {
            val bytes = ByteArray(4)
            bytes.writeUInt32Le(0, value)
            device.write(
                partitionOffsetBytes + layout.fatOffsetSector * VentoyDiskLayout.SECTOR_SIZE + cluster * 4,
                bytes,
            )
        }

        writeEntry(0, 0xFFFF_FFF8L)
        writeEntry(1, 0xFFFF_FFFFL)

        val allocationBitmapClusters = allocationBitmapClusterCount(layout)
        writeClusterChain(layout.allocationBitmapFirstCluster, allocationBitmapClusters, ::writeEntry)

        val upcaseClusters = upcaseTableClusterCount(layout)
        writeClusterChain(layout.upcaseTableFirstCluster, upcaseClusters, ::writeEntry)

        writeEntry(layout.rootDirectoryFirstCluster, 0xFFFF_FFFFL)
    }

    private fun writeAllocationBitmap(device: RawBlockDevice, partitionOffsetBytes: Long, layout: ExFatFormatInfo) {
        val bitmapBytes = ceilDiv(layout.clusterCount, 8).toInt()
        val bitmap = ByteArray(bitmapBytes)
        val usedClusterLast = layout.rootDirectoryFirstCluster
        for (cluster in 2..usedClusterLast) {
            val bitIndex = (cluster - 2).toInt()
            bitmap[bitIndex / 8] = (bitmap[bitIndex / 8].toInt() or (1 shl (bitIndex % 8))).toByte()
        }

        val clusterBytes = allocationBitmapClusterCount(layout) * layout.clusterSizeBytes
        val padded = bitmap.copyOf(clusterBytes.toInt())
        device.writeZeros(clusterOffset(partitionOffsetBytes, layout, layout.allocationBitmapFirstCluster), clusterBytes)
        device.write(clusterOffset(partitionOffsetBytes, layout, layout.allocationBitmapFirstCluster), padded)
    }

    private fun writeUpcaseTable(device: RawBlockDevice, partitionOffsetBytes: Long, layout: ExFatFormatInfo) {
        val upcase = buildUpcaseTable()
        val clusterBytes = upcaseTableClusterCount(layout) * layout.clusterSizeBytes
        val padded = upcase.copyOf(clusterBytes.toInt())
        device.writeZeros(clusterOffset(partitionOffsetBytes, layout, layout.upcaseTableFirstCluster), clusterBytes)
        device.write(clusterOffset(partitionOffsetBytes, layout, layout.upcaseTableFirstCluster), padded)
    }

    private fun writeRootDirectory(
        device: RawBlockDevice,
        partitionOffsetBytes: Long,
        layout: ExFatFormatInfo,
        label: String,
    ) {
        val root = ByteArray(layout.clusterSizeBytes)
        writeVolumeLabelEntry(root, 0, label)
        writeAllocationBitmapEntry(root, 32, layout)
        writeUpcaseTableEntry(root, 64, layout)

        val rootOffset = clusterOffset(partitionOffsetBytes, layout, layout.rootDirectoryFirstCluster)
        device.writeZeros(rootOffset, layout.clusterSizeBytes.toLong())
        device.write(rootOffset, root)
    }

    private fun writeVolumeLabelEntry(root: ByteArray, offset: Int, label: String) {
        root[offset] = 0x83.toByte()
        root[offset + 1] = label.length.toByte()
        label.forEachIndexed { index, char ->
            root.writeUInt16Le(offset + 2 + index * 2, char.code)
        }
    }

    private fun writeAllocationBitmapEntry(root: ByteArray, offset: Int, layout: ExFatFormatInfo) {
        root[offset] = 0x81.toByte()
        root[offset + 1] = 0
        root.writeUInt32Le(offset + 20, layout.allocationBitmapFirstCluster)
        root.writeUInt64Le(offset + 24, ceilDiv(layout.clusterCount, 8))
    }

    private fun writeUpcaseTableEntry(root: ByteArray, offset: Int, layout: ExFatFormatInfo) {
        val upcase = buildUpcaseTable()
        root[offset] = 0x82.toByte()
        root.writeUInt32Le(offset + 4, upcaseChecksum(upcase).toLong())
        root.writeUInt32Le(offset + 20, layout.upcaseTableFirstCluster)
        root.writeUInt64Le(offset + 24, upcase.size.toLong())
    }

    private fun clusterOffset(partitionOffsetBytes: Long, layout: ExFatFormatInfo, cluster: Long): Long =
        partitionOffsetBytes +
            layout.clusterHeapOffsetSector * VentoyDiskLayout.SECTOR_SIZE +
            (cluster - 2) * layout.clusterSizeBytes

    private fun allocationBitmapClusterCount(layout: ExFatFormatInfo): Long =
        layout.upcaseTableFirstCluster - layout.allocationBitmapFirstCluster

    private fun upcaseTableClusterCount(layout: ExFatFormatInfo): Long =
        layout.rootDirectoryFirstCluster - layout.upcaseTableFirstCluster

    private fun writeClusterChain(firstCluster: Long, clusterCount: Long, writer: (Long, Long) -> Unit) {
        for (index in 0 until clusterCount) {
            val cluster = firstCluster + index
            val value = if (index == clusterCount - 1) 0xFFFF_FFFFL else cluster + 1
            writer(cluster, value)
        }
    }

    companion object {
        private const val GIB = 1024L * 1024L * 1024L
        private const val BOOT_REGION_SECTORS = 12
        private const val CHECKSUM_SECTOR = 11
        private const val BACKUP_BOOT_REGION_SECTOR = 12L
        private const val MAIN_AND_BACKUP_BOOT_SECTORS = 24L
        private const val FAT_OFFSET_SECTOR = 24L

        fun bootChecksum(firstElevenBootSectors: ByteArray): UInt {
            require(firstElevenBootSectors.size >= 11 * VentoyDiskLayout.SECTOR_SIZE) {
                "exFAT boot checksum requires the first 11 boot sectors"
            }

            var checksum = 0u
            for (index in 0 until 11 * VentoyDiskLayout.SECTOR_SIZE) {
                if (index == 106 || index == 107 || index == 112) continue
                checksum = ((checksum and 1u) shl 31) + (checksum shr 1) +
                    (firstElevenBootSectors[index].toUInt() and 0xffu)
            }
            return checksum
        }

        fun buildUpcaseTable(): ByteArray {
            val table = ByteArray(65536 * 2)
            for (codePoint in 0..0xFFFF) {
                val mapped = when (codePoint) {
                    in 'a'.code..'z'.code -> codePoint - 32
                    else -> codePoint
                }
                table.writeUInt16Le(codePoint * 2, mapped)
            }
            return table
        }

        fun upcaseChecksum(upcaseTable: ByteArray): UInt {
            var checksum = 0u
            upcaseTable.forEach { byte ->
                checksum = ((checksum and 1u) shl 31) + (checksum shr 1) + (byte.toUInt() and 0xffu)
            }
            return checksum
        }

        private fun volumeSerial(label: String): Long {
            var hash = 0x56454E54L
            label.uppercase(Locale.US).forEach { char ->
                hash = ((hash shl 5) - hash + char.code) and 0xFFFF_FFFFL
            }
            return hash
        }

        private fun log2(value: Int): Int {
            require(value > 0 && value and (value - 1) == 0) { "Value must be a power of two" }
            return Integer.numberOfTrailingZeros(value)
        }

        private fun ceilDiv(value: Long, divisor: Long): Long =
            (value + divisor - 1) / divisor

        private fun alignUp(value: Long, alignment: Long): Long =
            ceilDiv(value, alignment) * alignment
    }
}
