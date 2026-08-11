package io.github.xntso.vendroid.ventoy

import java.security.SecureRandom
import java.util.zip.CRC32

internal data class GptPartitionEntry(
    val startSector: Long,
    val endSector: Long,
    val attributes: Long,
    val name: String,
) {
    val sectorCount: Long
        get() = endSector - startSector + 1
}

internal data class VentoyGptInfo(
    val partition1: GptPartitionEntry,
    val partition2: GptPartitionEntry,
)

internal object VentoyGpt {
    private const val HEADER_SIZE = 92
    private const val HEADER_REVISION = 0x0001_0000L
    private const val VENTOY_EFI_PARTITION_ATTRIBUTES = Long.MIN_VALUE
    private val signature = "EFI PART".encodeToByteArray()
    private val basicDataPartitionType = byteArrayOf(
        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
        0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
        0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(),
        0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte(),
    )

    fun write(
        device: RawBlockDevice,
        plan: VentoyInstallPlan,
        random: SecureRandom,
    ) {
        require(plan.partitionStyle == VentoyPartitionStyle.Gpt) {
            "GPT structures require a GPT install plan."
        }
        require(device.blockSize == VentoyDiskLayout.SECTOR_SIZE) {
            "GPT support requires 512-byte logical sectors."
        }

        val totalSectors = device.sizeBytes / VentoyDiskLayout.SECTOR_SIZE
        val backupHeaderSector = totalSectors - 1
        val backupTableStartSector = backupHeaderSector - VentoyDiskLayout.GPT_TABLE_SECTOR_COUNT
        val entries = ByteArray(
            VentoyDiskLayout.GPT_PARTITION_ENTRY_COUNT *
                VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE,
        )
        writePartitionEntry(
            destination = entries,
            index = 0,
            startSector = plan.partition1StartSector,
            endSector = plan.partition1EndSector,
            attributes = 0,
            name = "Ventoy",
            random = random,
        )
        writePartitionEntry(
            destination = entries,
            index = 1,
            startSector = plan.partition2StartSector,
            endSector = plan.partition2EndSector,
            attributes = VENTOY_EFI_PARTITION_ATTRIBUTES,
            name = "VTOYEFI",
            random = random,
        )

        val diskGuid = randomGuid(random)
        val entriesCrc = crc32(entries)
        val primaryHeader = buildHeader(
            currentSector = VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR,
            backupSector = backupHeaderSector,
            lastUsableSector = totalSectors - VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT - 1,
            partitionTableStartSector = VentoyDiskLayout.GPT_PRIMARY_TABLE_START_SECTOR,
            diskGuid = diskGuid,
            entriesCrc = entriesCrc,
        )
        val backupHeader = buildHeader(
            currentSector = backupHeaderSector,
            backupSector = VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR,
            lastUsableSector = totalSectors - VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT - 1,
            partitionTableStartSector = backupTableStartSector,
            diskGuid = diskGuid,
            entriesCrc = entriesCrc,
        )

        device.write(backupTableStartSector * VentoyDiskLayout.SECTOR_SIZE, entries)
        device.write(backupHeaderSector * VentoyDiskLayout.SECTOR_SIZE, backupHeader)
        device.write(
            VentoyDiskLayout.GPT_PRIMARY_TABLE_START_SECTOR * VentoyDiskLayout.SECTOR_SIZE,
            entries,
        )
        device.write(
            VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR * VentoyDiskLayout.SECTOR_SIZE,
            primaryHeader,
        )
    }

    fun read(device: RawBlockDevice): VentoyGptInfo {
        require(device.blockSize == VentoyDiskLayout.SECTOR_SIZE) {
            "GPT support requires 512-byte logical sectors."
        }
        val totalSectors = device.sizeBytes / VentoyDiskLayout.SECTOR_SIZE
        val lastSector = totalSectors - 1
        val primaryHeaderBytes = device.readBytes(
            VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR * VentoyDiskLayout.SECTOR_SIZE,
            VentoyDiskLayout.SECTOR_SIZE,
        )
        val primaryHeader = parseHeader(
            bytes = primaryHeaderBytes,
            expectedCurrentSector = VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR,
            expectedBackupSector = lastSector,
        )
        require(primaryHeader.partitionTableStartSector == VentoyDiskLayout.GPT_PRIMARY_TABLE_START_SECTOR) {
            "Unexpected primary GPT partition table location."
        }
        val expectedLastUsableSector =
            totalSectors - VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT - 1
        require(primaryHeader.lastUsableSector == expectedLastUsableSector) {
            "Unexpected last usable GPT sector."
        }

        val primaryEntries = readAndValidateEntries(device, primaryHeader)
        val backupHeaderBytes = device.readBytes(
            lastSector * VentoyDiskLayout.SECTOR_SIZE,
            VentoyDiskLayout.SECTOR_SIZE,
        )
        val backupHeader = parseHeader(
            bytes = backupHeaderBytes,
            expectedCurrentSector = lastSector,
            expectedBackupSector = VentoyDiskLayout.GPT_PRIMARY_HEADER_SECTOR,
        )
        require(
            backupHeader.partitionTableStartSector ==
                lastSector - VentoyDiskLayout.GPT_TABLE_SECTOR_COUNT,
        ) {
            "Unexpected backup GPT partition table location."
        }
        require(primaryHeader.diskGuid.contentEquals(backupHeader.diskGuid)) {
            "Primary and backup GPT disk GUIDs differ."
        }
        require(backupHeader.lastUsableSector == expectedLastUsableSector) {
            "Primary and backup GPT usable ranges differ."
        }
        val backupEntries = readAndValidateEntries(device, backupHeader)
        require(primaryEntries.contentEquals(backupEntries)) {
            "Primary and backup GPT partition tables differ."
        }

        val partition1 = parsePartitionEntry(primaryEntries, 0)
        val partition2 = parsePartitionEntry(primaryEntries, 1)
        require(partition1.startSector >= VentoyDiskLayout.GPT_FIRST_USABLE_SECTOR &&
            partition1.endSector >= partition1.startSector
        ) {
            "Invalid first GPT partition range."
        }
        require(partition2.startSector > partition1.endSector &&
            partition2.endSector >= partition2.startSector &&
            partition2.endSector <= expectedLastUsableSector
        ) {
            "Invalid second GPT partition range."
        }
        return VentoyGptInfo(partition1, partition2)
    }

    private fun buildHeader(
        currentSector: Long,
        backupSector: Long,
        lastUsableSector: Long,
        partitionTableStartSector: Long,
        diskGuid: ByteArray,
        entriesCrc: Long,
    ): ByteArray = ByteArray(VentoyDiskLayout.SECTOR_SIZE).also { header ->
        signature.copyInto(header, 0)
        header.writeUInt32Le(8, HEADER_REVISION)
        header.writeUInt32Le(12, HEADER_SIZE.toLong())
        header.writeUInt64Le(24, currentSector)
        header.writeUInt64Le(32, backupSector)
        header.writeUInt64Le(40, VentoyDiskLayout.GPT_FIRST_USABLE_SECTOR)
        header.writeUInt64Le(48, lastUsableSector)
        diskGuid.copyInto(header, 56)
        header.writeUInt64Le(72, partitionTableStartSector)
        header.writeUInt32Le(80, VentoyDiskLayout.GPT_PARTITION_ENTRY_COUNT.toLong())
        header.writeUInt32Le(84, VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE.toLong())
        header.writeUInt32Le(88, entriesCrc)
        header.writeUInt32Le(16, crc32(header, HEADER_SIZE))
    }

    private fun parseHeader(
        bytes: ByteArray,
        expectedCurrentSector: Long,
        expectedBackupSector: Long,
    ): GptHeader {
        require(bytes.copyOfRange(0, signature.size).contentEquals(signature)) {
            "Missing GPT signature."
        }
        require(bytes.readUInt32Le(8) == HEADER_REVISION) { "Unsupported GPT revision." }
        val headerSize = bytes.readUInt32Le(12).toInt()
        require(headerSize in HEADER_SIZE..VentoyDiskLayout.SECTOR_SIZE) {
            "Invalid GPT header size."
        }
        val expectedHeaderCrc = bytes.readUInt32Le(16)
        val crcBytes = bytes.copyOfRange(0, headerSize)
        crcBytes.writeUInt32Le(16, 0)
        require(crc32(crcBytes) == expectedHeaderCrc) { "GPT header checksum mismatch." }
        require(bytes.readUInt64Le(24) == expectedCurrentSector) {
            "GPT header is stored at the wrong sector."
        }
        require(bytes.readUInt64Le(32) == expectedBackupSector) {
            "GPT header points to the wrong backup sector."
        }
        require(bytes.readUInt64Le(40) == VentoyDiskLayout.GPT_FIRST_USABLE_SECTOR) {
            "Unexpected first usable GPT sector."
        }
        require(bytes.readUInt32Le(80) == VentoyDiskLayout.GPT_PARTITION_ENTRY_COUNT.toLong()) {
            "Unsupported GPT partition entry count."
        }
        require(bytes.readUInt32Le(84) == VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE.toLong()) {
            "Unsupported GPT partition entry size."
        }
        return GptHeader(
            lastUsableSector = bytes.readUInt64Le(48),
            diskGuid = bytes.copyOfRange(56, 72),
            partitionTableStartSector = bytes.readUInt64Le(72),
            partitionTableCrc = bytes.readUInt32Le(88),
        )
    }

    private fun readAndValidateEntries(
        device: RawBlockDevice,
        header: GptHeader,
    ): ByteArray {
        val length = VentoyDiskLayout.GPT_PARTITION_ENTRY_COUNT *
            VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE
        val entries = device.readBytes(
            header.partitionTableStartSector * VentoyDiskLayout.SECTOR_SIZE,
            length,
        )
        require(crc32(entries) == header.partitionTableCrc) {
            "GPT partition table checksum mismatch."
        }
        return entries
    }

    private fun writePartitionEntry(
        destination: ByteArray,
        index: Int,
        startSector: Long,
        endSector: Long,
        attributes: Long,
        name: String,
        random: SecureRandom,
    ) {
        val offset = index * VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE
        basicDataPartitionType.copyInto(destination, offset)
        randomGuid(random).copyInto(destination, offset + 16)
        destination.writeUInt64Le(offset + 32, startSector)
        destination.writeUInt64Le(offset + 40, endSector)
        destination.writeUInt64Le(offset + 48, attributes)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        require(nameBytes.size <= 72) { "GPT partition name is too long." }
        nameBytes.copyInto(destination, offset + 56)
    }

    private fun parsePartitionEntry(entries: ByteArray, index: Int): GptPartitionEntry {
        val offset = index * VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE
        require(entries.copyOfRange(offset, offset + 16).any { it.toInt() != 0 }) {
            "Missing GPT partition ${index + 1}."
        }
        val nameBytes = entries.copyOfRange(offset + 56, offset + 128)
        var nameLength = 0
        while (nameLength + 1 < nameBytes.size &&
            (nameBytes[nameLength].toInt() != 0 || nameBytes[nameLength + 1].toInt() != 0)
        ) {
            nameLength += 2
        }
        return GptPartitionEntry(
            startSector = entries.readUInt64Le(offset + 32),
            endSector = entries.readUInt64Le(offset + 40),
            attributes = entries.readUInt64Le(offset + 48),
            name = nameBytes.copyOfRange(0, nameLength).toString(Charsets.UTF_16LE),
        )
    }

    private fun randomGuid(random: SecureRandom): ByteArray =
        ByteArray(16).also { guid ->
            random.nextBytes(guid)
            guid[7] = ((guid[7].toInt() and 0x0F) or 0x40).toByte()
            guid[8] = ((guid[8].toInt() and 0x3F) or 0x80).toByte()
        }

    private fun crc32(bytes: ByteArray, length: Int = bytes.size): Long =
        CRC32().run {
            update(bytes, 0, length)
            value
        }

    private data class GptHeader(
        val lastUsableSector: Long,
        val diskGuid: ByteArray,
        val partitionTableStartSector: Long,
        val partitionTableCrc: Long,
    )
}
