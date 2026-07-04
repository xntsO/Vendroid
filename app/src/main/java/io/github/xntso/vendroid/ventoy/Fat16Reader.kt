package io.github.xntso.vendroid.ventoy

class Fat16Reader(
    private val device: RawBlockDevice,
    private val partitionStartSector: Long,
) {
    private val partitionOffsetBytes = partitionStartSector * VentoyDiskLayout.SECTOR_SIZE
    private val bootSector = device.readBytes(partitionOffsetBytes, VentoyDiskLayout.SECTOR_SIZE)
    private val bytesPerSector = bootSector.readUInt16Le(11)
    private val sectorsPerCluster = bootSector[13].toInt() and 0xff
    private val reservedSectorCount = bootSector.readUInt16Le(14)
    private val numberOfFats = bootSector[16].toInt() and 0xff
    private val rootEntryCount = bootSector.readUInt16Le(17)
    private val totalSectors = bootSector.readUInt16Le(19).takeIf { it != 0 }?.toLong()
        ?: bootSector.readUInt32Le(32)
    private val fatSizeSectors = bootSector.readUInt16Le(22)
    private val rootDirSectors = ((rootEntryCount * 32) + bytesPerSector - 1) / bytesPerSector
    private val firstFatSector = reservedSectorCount
    private val firstRootDirSector = reservedSectorCount + numberOfFats * fatSizeSectors
    private val firstDataSector = firstRootDirSector + rootDirSectors

    init {
        require(bytesPerSector == VentoyDiskLayout.SECTOR_SIZE) { "Only 512-byte FAT16 sectors are supported" }
        require(sectorsPerCluster > 0) { "Invalid FAT16 sectors per cluster" }
        require(numberOfFats > 0) { "Invalid FAT16 FAT count" }
        require(fatSizeSectors > 0) { "Invalid FAT16 FAT size" }
        require(totalSectors > firstDataSector) { "Invalid FAT16 layout" }
    }

    fun readText(path: String): String? =
        readFile(path)?.decodeToString()?.trimEnd('\u0000', '\r', '\n', ' ')

    fun readFile(path: String): ByteArray? {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        var directory = readRootDirectoryEntries()
        parts.forEachIndexed { index, part ->
            val entry = directory.firstOrNull { it.matches(part) } ?: return null
            val isLast = index == parts.lastIndex
            if (isLast) {
                if (entry.isDirectory) return null
                return readClusterChain(entry.firstCluster, entry.size.toInt()).copyOf(entry.size.toInt())
            }
            if (!entry.isDirectory) return null
            directory = readDirectoryEntries(entry.firstCluster)
        }
        return null
    }

    private fun readRootDirectoryEntries(): List<FatDirectoryEntry> {
        val bytes = device.readBytes(
            partitionOffsetBytes + firstRootDirSector.toLong() * bytesPerSector,
            rootDirSectors * bytesPerSector,
        )
        return parseDirectoryEntries(bytes)
    }

    private fun readDirectoryEntries(firstCluster: Int): List<FatDirectoryEntry> =
        parseDirectoryEntries(readClusterChain(firstCluster, Int.MAX_VALUE))

    private fun parseDirectoryEntries(bytes: ByteArray): List<FatDirectoryEntry> {
        val entries = mutableListOf<FatDirectoryEntry>()
        var offset = 0
        while (offset + 32 <= bytes.size) {
            val first = bytes[offset].toInt() and 0xff
            if (first == 0x00) break
            if (first != 0xE5) {
                val attributes = bytes[offset + 11].toInt() and 0xff
                if (attributes != 0x0F) {
                    val name = bytes.copyOfRange(offset, offset + 11).decodeToString()
                    val firstCluster = bytes.readUInt16Le(offset + 26)
                    val size = bytes.readUInt32Le(offset + 28)
                    entries += FatDirectoryEntry(name, attributes, firstCluster, size)
                }
            }
            offset += 32
        }
        return entries
    }

    private fun readClusterChain(firstCluster: Int, maxBytes: Int): ByteArray {
        if (firstCluster < 2) return ByteArray(0)
        val result = ArrayList<ByteArray>()
        var cluster = firstCluster
        var total = 0
        val clusterBytes = sectorsPerCluster * bytesPerSector

        while (cluster in 2 until 0xFFF8) {
            val bytes = readCluster(cluster)
            result += bytes
            total += bytes.size
            if (total >= maxBytes) break
            cluster = readFatEntry(cluster)
        }

        val output = ByteArray(total.coerceAtMost(maxBytes))
        var offset = 0
        for (chunk in result) {
            val copyLength = minOf(chunk.size, output.size - offset)
            if (copyLength <= 0) break
            chunk.copyInto(output, offset, 0, copyLength)
            offset += copyLength
        }
        return output
    }

    private fun readCluster(cluster: Int): ByteArray {
        val sector = firstDataSector + (cluster - 2) * sectorsPerCluster
        return device.readBytes(partitionOffsetBytes + sector.toLong() * bytesPerSector, sectorsPerCluster * bytesPerSector)
    }

    private fun readFatEntry(cluster: Int): Int {
        val offset = partitionOffsetBytes + firstFatSector.toLong() * bytesPerSector + cluster * 2L
        return device.readBytes(offset, 2).readUInt16Le(0)
    }

    private data class FatDirectoryEntry(
        val rawName: String,
        val attributes: Int,
        val firstCluster: Int,
        val size: Long,
    ) {
        val isDirectory: Boolean
            get() = attributes and 0x10 != 0

        fun matches(pathPart: String): Boolean =
            toDisplayName().equals(pathPart, ignoreCase = true)

        private fun toDisplayName(): String {
            val base = rawName.substring(0, 8).trim()
            val extension = rawName.substring(8, 11).trim()
            return if (extension.isBlank()) base else "$base.$extension"
        }
    }
}
