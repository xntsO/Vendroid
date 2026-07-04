package io.github.xntso.vendroid.ventoy

import java.security.SecureRandom

internal data class MbrPartitionEntry(
    val active: Int,
    val type: Int,
    val startSector: Long,
    val sectorCount: Long,
)

internal object VentoyMbr {
    fun build(
        bootImage: ByteArray,
        plan: VentoyInstallPlan,
        random: SecureRandom,
        preservedVentoyUuid: ByteArray? = null,
        preservedDiskSignature: ByteArray? = null,
    ): ByteArray {
        require(bootImage.size >= VentoyDiskLayout.SECTOR_SIZE) {
            "boot.img must contain at least one 512-byte sector"
        }
        val mbr = bootImage.copyOfRange(0, VentoyDiskLayout.SECTOR_SIZE)

        val uuid = preservedVentoyUuid ?: ByteArray(16).also(random::nextBytes)
        val diskSignature = preservedDiskSignature ?: ByteArray(4).also(random::nextBytes)
        require(uuid.size == 16) { "Ventoy UUID must be 16 bytes" }
        require(diskSignature.size == 4) { "Disk signature must be 4 bytes" }
        uuid.copyInto(mbr, VentoyDiskLayout.VENTOY_UUID_OFFSET)
        diskSignature.copyInto(mbr, VentoyDiskLayout.DISK_SIGNATURE_OFFSET)

        writePartitionEntry(
            mbr,
            index = 0,
            active = 0x80,
            type = VentoyDiskLayout.PARTITION1_TYPE,
            startSector = plan.partition1StartSector,
            sectorCount = plan.partition1SectorCount,
        )
        writePartitionEntry(
            mbr,
            index = 1,
            active = 0x00,
            type = VentoyDiskLayout.PARTITION2_TYPE,
            startSector = plan.partition2StartSector,
            sectorCount = plan.partition2SectorCount,
        )
        for (index in 2..3) {
            val offset = VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + index * 16
            mbr.fill(0, offset, offset + 16)
        }

        mbr[VentoyDiskLayout.MBR_BOOT_SIGNATURE_OFFSET] = 0x55
        mbr[VentoyDiskLayout.MBR_BOOT_SIGNATURE_OFFSET + 1] = 0xAA.toByte()
        return mbr
    }

    fun parse(mbr: ByteArray): List<MbrPartitionEntry> {
        require(mbr.size >= VentoyDiskLayout.SECTOR_SIZE) { "MBR must be 512 bytes" }
        return (0 until 4).map { index ->
            val offset = VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + index * 16
            MbrPartitionEntry(
                active = mbr[offset].toInt() and 0xff,
                type = mbr[offset + 4].toInt() and 0xff,
                startSector = mbr.readUInt32Le(offset + 8),
                sectorCount = mbr.readUInt32Le(offset + 12),
            )
        }
    }

    fun hasBootSignature(mbr: ByteArray): Boolean =
        mbr.size >= VentoyDiskLayout.SECTOR_SIZE &&
            (mbr[510].toInt() and 0xff) == 0x55 &&
            (mbr[511].toInt() and 0xff) == 0xAA

    private fun writePartitionEntry(
        mbr: ByteArray,
        index: Int,
        active: Int,
        type: Int,
        startSector: Long,
        sectorCount: Long,
    ) {
        require(index in 0..3) { "MBR partition index must be 0..3" }
        require(startSector in 0..UInt.MAX_VALUE.toLong()) { "Partition start is outside MBR range" }
        require(sectorCount in 0..UInt.MAX_VALUE.toLong()) { "Partition size is outside MBR range" }

        val offset = VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + index * 16
        val startChs = chs(startSector)
        val endChs = chs(startSector + sectorCount - 1)

        mbr[offset] = active.toByte()
        mbr[offset + 1] = startChs.head.toByte()
        mbr[offset + 2] = startChs.sectorByte.toByte()
        mbr[offset + 3] = startChs.cylinderByte.toByte()
        mbr[offset + 4] = type.toByte()
        mbr[offset + 5] = endChs.head.toByte()
        mbr[offset + 6] = endChs.sectorByte.toByte()
        mbr[offset + 7] = endChs.cylinderByte.toByte()
        mbr.writeUInt32Le(offset + 8, startSector)
        mbr.writeUInt32Le(offset + 12, sectorCount)
    }

    private fun chs(lba: Long): Chs {
        val sectorsPerTrack = 63
        val heads = 255
        val cylinder = lba / (heads * sectorsPerTrack)
        if (cylinder > 1023) {
            return Chs(head = 254, sectorByte = 0xFF, cylinderByte = 0xFF)
        }

        val temp = lba % (heads * sectorsPerTrack)
        val head = (temp / sectorsPerTrack).toInt()
        val sector = (temp % sectorsPerTrack + 1).toInt()
        val cylinderInt = cylinder.toInt()
        return Chs(
            head = head,
            sectorByte = sector or ((cylinderInt shr 2) and 0xC0),
            cylinderByte = cylinderInt and 0xFF,
        )
    }

    private data class Chs(
        val head: Int,
        val sectorByte: Int,
        val cylinderByte: Int,
    )
}

internal fun ByteArray.writeUInt16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

internal fun ByteArray.writeUInt32Le(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

internal fun ByteArray.writeUInt64Le(offset: Int, value: Long) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
    this[offset + 4] = ((value ushr 32) and 0xff).toByte()
    this[offset + 5] = ((value ushr 40) and 0xff).toByte()
    this[offset + 6] = ((value ushr 48) and 0xff).toByte()
    this[offset + 7] = ((value ushr 56) and 0xff).toByte()
}

internal fun ByteArray.readUInt16Le(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.readUInt32Le(offset: Int): Long =
    (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)

internal fun ByteArray.readUInt64Le(offset: Int): Long =
    (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24) or
        ((this[offset + 4].toLong() and 0xffL) shl 32) or
        ((this[offset + 5].toLong() and 0xffL) shl 40) or
        ((this[offset + 6].toLong() and 0xffL) shl 48) or
        ((this[offset + 7].toLong() and 0xffL) shl 56)
