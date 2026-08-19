package io.github.xntso.vendroid.ventoy

import android.net.Uri
import io.github.xntso.vendroid.MemoryBufferBlockDeviceDriver
import io.github.xntso.vendroid.VendroidApplication
import io.github.xntso.vendroid.utils.exception.UsbCommunicationException
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.CRC32

class VentoyLayoutTest {
    @Test
    fun `builds MBR layout with aligned end partition`() {
        val plan = VentoyDiskLayout.plan(8L * 1024 * 1024 * 1024, 512, "1.1.16")

        assertEquals(2048, plan.partition1StartSector)
        assertEquals(65536, plan.partition2SectorCount)
        assertEquals(0, plan.partition2StartSector % 8)
        assertEquals(plan.partition2StartSector - 1, plan.partition1EndSector)
        assertTrue(plan.partition2EndSector < plan.diskSizeBytes / 512)
    }

    @Test
    fun `rejects unsupported disk sizes and blocks`() {
        assertThrows<IllegalArgumentException> {
            VentoyDiskLayout.plan(40L * 1024 * 1024, 4096, "1.1.16")
        }
        assertThrows<IllegalArgumentException> {
            VentoyDiskLayout.plan(VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512, 512, "1.1.16")
        }
        assertThrows<IllegalArgumentException> {
            VentoyDiskLayout.plan(16L * 1024 * 1024, 512, "1.1.16")
        }
    }

    @Test
    fun `builds GPT layout with space for backup table`() {
        val diskSize = 8L * 1024 * 1024 * 1024
        val plan = VentoyDiskLayout.plan(
            diskSizeBytes = diskSize,
            blockSize = 512,
            payloadVersion = "1.1.16",
            partitionStyle = VentoyPartitionStyle.Gpt,
        )

        assertEquals(VentoyPartitionStyle.Gpt, plan.partitionStyle)
        assertEquals(0, plan.partition2StartSector % 8)
        assertTrue(
            plan.partition2EndSector <=
                diskSize / 512 - VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT - 1,
        )
    }

    @Test
    fun `allows GPT disks larger than MBR limit`() {
        val plan = VentoyDiskLayout.plan(
            diskSizeBytes = VentoyDiskLayout.MAX_MBR_DISK_BYTES + 512,
            blockSize = 512,
            payloadVersion = "1.1.16",
            partitionStyle = VentoyPartitionStyle.Gpt,
        )

        assertEquals(VentoyPartitionStyle.Gpt, plan.partitionStyle)
    }

    @Test
    fun `reserves aligned space at the end of the disk`() {
        val diskSize = 8L * 1024 * 1024 * 1024
        val requested = 2L * 1024 * 1024 * 1024
        val plan = VentoyDiskLayout.plan(
            diskSizeBytes = diskSize,
            blockSize = 512,
            payloadVersion = "1.1.16",
            reservedSpaceBytes = requested,
        )

        val actualReserved = diskSize - (plan.partition2EndSector + 1) * 512
        assertTrue(actualReserved >= requested)
        assertTrue(actualReserved < requested + 4096)
        assertEquals(0, plan.partition2StartSector % 8)
    }

    @Test
    fun `rejects reserved space that leaves no data partition`() {
        assertThrows<IllegalArgumentException> {
            VentoyDiskLayout.plan(
                diskSizeBytes = 64L * 1024 * 1024,
                blockSize = 512,
                payloadVersion = "1.1.16",
                reservedSpaceBytes = 40L * 1024 * 1024,
            )
        }
    }
}

class VentoyMbrTest {
    @Test
    fun `writes boot signature partition entries uuid and disk signature`() {
        val bootImage = ByteArray(512) { 0x5A }
        val plan = VentoyDiskLayout.plan(64L * 1024 * 1024, 512, "1.1.16")
        val uuid = ByteArray(16) { it.toByte() }
        val diskSignature = byteArrayOf(1, 2, 3, 4)

        val mbr = VentoyMbr.build(
            bootImage = bootImage,
            plan = plan,
            random = SecureRandom(),
            preservedVentoyUuid = uuid,
            preservedDiskSignature = diskSignature,
        )
        val entries = VentoyMbr.parse(mbr)

        assertTrue(VentoyMbr.hasBootSignature(mbr))
        assertArrayEquals(uuid, mbr.copyOfRange(384, 400))
        assertArrayEquals(diskSignature, mbr.copyOfRange(440, 444))
        assertEquals(0x80, entries[0].active)
        assertEquals(0x07, entries[0].type)
        assertEquals(plan.partition1StartSector, entries[0].startSector)
        assertEquals(plan.partition1SectorCount, entries[0].sectorCount)
        assertEquals(0x00, entries[1].active)
        assertEquals(0xEF, entries[1].type)
        assertEquals(plan.partition2StartSector, entries[1].startSector)
        assertEquals(plan.partition2SectorCount, entries[1].sectorCount)
    }

    @Test
    fun `writes GPT protective partition entry`() {
        val mbr = VentoyMbr.buildProtective(
            bootImage = ByteArray(512) { 0x5A },
            diskSizeBytes = 64L * 1024 * 1024,
            random = SecureRandom(),
        )
        val entries = VentoyMbr.parse(mbr)

        assertEquals(0x22, mbr[92].toInt() and 0xff)
        assertEquals(0xEE, entries[0].type)
        assertEquals(1, entries[0].startSector)
        assertEquals(64L * 1024 * 1024 / 512 - 1, entries[0].sectorCount)
        assertTrue(entries.drop(1).all { it.type == 0 && it.sectorCount == 0L })
    }
}

class VentoyDiskScannerTest {
    @Test
    fun `recognizes standard Ventoy layout after partition type bytes change`() {
        val device = memoryDevice(40L * 1024 * 1024)
        VentoyInstaller(syntheticPayload()).install(
            device,
            VentoyInstallOptions(forceInstall = true),
        )
        device.write(
            (VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 4).toLong(),
            byteArrayOf(0x0C),
        )
        device.write(
            (VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 16 + 4).toLong(),
            byteArrayOf(0x06),
        )

        assertNotNull(VentoyDiskScanner().scan(device))
    }

    @Test
    fun `recognizes GPT Ventoy layout`() {
        val device = memoryDevice(40L * 1024 * 1024)
        VentoyInstaller(syntheticPayload()).install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )

        val info = VentoyDiskScanner().scan(device)

        assertNotNull(info)
        assertEquals(VentoyPartitionStyle.Gpt, info?.partitionStyle)
        assertFalse(info?.needsRepair ?: true)
    }

    @Test
    fun `recognizes GPT from backup when primary header CRC is damaged`() {
        val device = installedGptDevice()
        device.write(512 + 24, byteArrayOf(0x7F))

        val info = VentoyDiskScanner().scan(device)

        assertEquals(VentoyPartitionStyle.Gpt, info?.partitionStyle)
        assertTrue(info?.needsRepair == true)
        assertThrows<IllegalArgumentException> { VentoyGpt.read(device) }
    }

    @Test
    fun `recognizes backup GPT when protective MBR and primary signature are damaged`() {
        val device = installedGptDevice()
        device.write(510, byteArrayOf(0, 0))
        device.write(512, ByteArray(8))

        val info = VentoyDiskScanner().scan(device)

        assertEquals(VentoyPartitionStyle.Gpt, info?.partitionStyle)
        assertTrue(info?.needsRepair == true)
        assertTrue(VentoyDiskScanner().hasAnyPartition(device))
    }

    @Test
    fun `marks an incomplete protective MBR span for repair`() {
        val device = installedGptDevice()
        val mbr = device.readBytes(0, 512)
        mbr.writeUInt32Le(VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 12, 1)
        device.write(0, mbr)

        val info = VentoyDiskScanner().scan(device)

        assertEquals(VentoyPartitionStyle.Gpt, info?.partitionStyle)
        assertTrue(info?.needsRepair == true)
    }

    @Test
    fun `failed primary repair leaves the valid backup GPT untouched`() {
        val device = installedGptDevice()
        device.write(512 + 24, byteArrayOf(0x7F))
        val backupOffset = device.sizeBytes -
            VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT * VentoyDiskLayout.SECTOR_SIZE
        val backup = device.readBytes(
            backupOffset,
            (VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT * 512).toInt(),
        )
        val failingDevice = FailingWriteRawBlockDevice(
            delegate = device,
            failingOffset = VentoyDiskLayout.GPT_PRIMARY_TABLE_START_SECTOR * 512,
        )

        assertThrows<IOException> { VentoyGpt.repair(failingDevice) }

        assertArrayEquals(backup, device.readBytes(backupOffset, backup.size))
        assertTrue(VentoyDiskScanner().scan(device)?.needsRepair == true)
    }

    @Test
    fun `recognizes GPT from primary when backup table CRC is damaged`() {
        val device = installedGptDevice()
        val backupTableOffset = device.sizeBytes -
            VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT * VentoyDiskLayout.SECTOR_SIZE
        device.write(backupTableOffset, byteArrayOf(0x7F))

        val info = VentoyDiskScanner().scan(device)

        assertEquals(VentoyPartitionStyle.Gpt, info?.partitionStyle)
        assertTrue(info?.needsRepair == true)
        assertTrue(VentoyGpt.repair(device).redundancyHealthy)
    }

    @Test
    fun `repairs a damaged backup GPT header CRC from the primary copy`() {
        val device = installedGptDevice()
        val backupHeaderOffset = device.sizeBytes - VentoyDiskLayout.SECTOR_SIZE
        device.write(backupHeaderOffset + 16, byteArrayOf(0, 0, 0, 0))

        assertTrue(VentoyDiskScanner().scan(device)?.needsRepair == true)

        val repaired = VentoyGpt.repair(device)

        assertTrue(repaired.redundancyHealthy)
        assertFalse(VentoyDiskScanner().scan(device)?.needsRepair ?: true)
    }

    @Test
    fun `rejects GPT when both partition table CRCs are damaged`() {
        val device = installedGptDevice()
        val backupTableOffset = device.sizeBytes -
            VentoyDiskLayout.GPT_BACKUP_SECTOR_COUNT * VentoyDiskLayout.SECTOR_SIZE
        device.write(2L * 512, byteArrayOf(0x7F))
        device.write(backupTableOffset, byteArrayOf(0x7F))

        assertNull(VentoyDiskScanner().scan(device))
    }

    @Test
    fun `rejects a valid foreign GPT with Ventoy-like geometry`() {
        val device = installedGptDevice()
        rewriteGptPartitionType(device, tableStartSector = 2, headerSector = 1)
        rewriteGptPartitionType(
            device,
            tableStartSector = device.sizeBytes / 512 - 33,
            headerSector = device.sizeBytes / 512 - 1,
        )

        assertNull(VentoyDiskScanner().scan(device))
    }
}

class VentoyPayloadTest {
    @Test
    fun `rejects missing manifest asset`() {
        val validHash = "0".repeat(64)
        assertThrows<IllegalArgumentException> {
            VentoyPayloadManifest.parse(
                """
                version=1.1.16
                file=boot/boot.img|1|$validHash
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects wrong payload hash`() {
        val files = syntheticPayloadFiles()
        val manifest = VentoyPayloadManifest(
            version = "1.1.16",
            files = files.mapValues { (path, bytes) ->
                VentoyPayloadFile(path, bytes.size.toLong(), "0".repeat(64))
            },
        )
        val payload = VentoyPayload(manifest) { path -> ByteArrayInputStream(files.getValue(path)) }

        assertThrows<IllegalArgumentException> {
            payload.validate()
        }
    }

    @Test
    fun `rejects wrong version file`() {
        val files = syntheticPayloadFiles(version = "1.1.16")
        val manifest = VentoyPayloadManifest(
            version = "1.1.15",
            files = files.mapValues { (path, bytes) ->
                VentoyPayloadFile(path, bytes.size.toLong(), bytes.sha256Hex())
            },
        ).also { it.requireComplete() }
        val payload = VentoyPayload(manifest) { path -> ByteArrayInputStream(files.getValue(path)) }

        assertThrows<IllegalArgumentException> {
            payload.validate()
        }
    }
}

class ExFatFormatterTest {
    @Test
    fun `writes boot checksum volume label and metadata placement`() {
        val device = memoryDevice(64L * 1024 * 1024)
        val plan = VentoyDiskLayout.plan(device.sizeBytes, device.blockSize, "1.1.16")

        val info = ExFatFormatter().format(
            device = device,
            partitionStartSector = plan.partition1StartSector,
            partitionSectorCount = plan.partition1SectorCount,
            label = "Ventoy",
        )

        val bootRegion = device.readBytes(plan.partition1StartSector * 512, 12 * 512)
        val checksum = bootRegion.readUInt32Le(11 * 512)
        assertEquals(info.bootChecksum.toLong() and 0xFFFF_FFFFL, checksum)

        val rootOffset = plan.partition1StartSector * 512 +
            info.clusterHeapOffsetSector * 512 +
            (info.rootDirectoryFirstCluster - 2) * info.clusterSizeBytes
        val root = device.readBytes(rootOffset, info.clusterSizeBytes)
        assertEquals(0x83, root[0].toInt() and 0xff)
        assertEquals(6, root[1].toInt())
        assertEquals('V'.code, root.readUInt16Le(2))
        assertEquals(0x81, root[32].toInt() and 0xff)
        assertEquals(0x82, root[64].toInt() and 0xff)
    }

    @Test
    fun `uses an explicitly selected cluster size`() {
        val device = memoryDevice(64L * 1024 * 1024)
        val plan = VentoyDiskLayout.plan(device.sizeBytes, device.blockSize, "1.1.16")

        val info = ExFatFormatter().format(
            device = device,
            partitionStartSector = plan.partition1StartSector,
            partitionSectorCount = plan.partition1SectorCount,
            clusterSize = VentoyClusterSize.KiB64,
        )

        val bootSector = device.readBytes(plan.partition1StartSector * 512, 512)
        assertEquals(64 * 1024, info.clusterSizeBytes)
        assertEquals(7, bootSector[109].toInt())
    }
}

class VentoyVersionTest {
    @Test
    fun `reads installed version from official grub config format`() {
        val config = """
            set timeout=10
            set VENTOY_VERSION="1.1.17"
            export VENTOY_VERSION
        """.trimIndent()

        assertEquals("1.1.17", VentoyVersion.fromGrubConfig(config))
    }

    @Test
    fun `compares numeric Ventoy versions`() {
        assertEquals(VentoyVersionRelation.Older, VentoyVersion.compare("1.1.9", "1.1.16"))
        assertEquals(VentoyVersionRelation.Same, VentoyVersion.compare("v1.1.16", "1.1.16"))
        assertEquals(VentoyVersionRelation.Newer, VentoyVersion.compare("1.2.0", "1.1.16"))
        assertEquals(VentoyVersionRelation.Unknown, VentoyVersion.compare("dev", "1.1.16"))
        assertTrue(VentoyVersion.isPayloadCompatible("1.1.17", "1.1.16"))
        assertFalse(VentoyVersion.isPayloadCompatible("1.2.0", "1.1.16"))
        assertFalse(VentoyVersion.isPayloadCompatible("1.1.15", "1.1.16"))
    }
}

class VentoyInstallerTest {
    @Test
    fun `streams xz payload into a memory block device`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val payload = syntheticPayload()

        val plan = VentoyInstaller(payload).install(
            device = device,
            options = VentoyInstallOptions(forceInstall = true),
        )

        val mbr = device.readBytes(0, 512)
        assertTrue(VentoyMbr.hasBootSignature(mbr))
        val entries = VentoyMbr.parse(mbr)
        assertEquals(plan.partition2StartSector, entries[1].startSector)
        assertEquals(0x83, device.readBytes(plan.partition1StartSector * 512 + rootEntryOffset(device, plan), 1)[0].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), device.readBytes(512, 4))
    }

    @Test
    fun `installs GPT layout with primary and backup tables`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val plan = VentoyInstaller(syntheticPayload()).install(
            device = device,
            options = VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )

        val protectiveMbr = VentoyMbr.parse(device.readBytes(0, 512)).first()
        val primaryHeader = device.readBytes(512, 512)
        val primaryEntries = device.readBytes(2L * 512, 32 * 512)
        val gpt = VentoyGpt.read(device)
        assertEquals(0xEE, protectiveMbr.type)
        assertArrayEquals("EFI PART".encodeToByteArray(), primaryHeader.copyOfRange(0, 8))
        assertEquals(1, primaryHeader.readUInt64Le(24))
        assertEquals(device.sizeBytes / 512 - 1, primaryHeader.readUInt64Le(32))
        assertEquals(34, primaryHeader.readUInt64Le(40))
        assertArrayEquals(
            byteArrayOf(
                0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
                0xE5.toByte(), 0xB9.toByte(), 0x33, 0x44,
                0x87.toByte(), 0xC0.toByte(), 0x68, 0xB6.toByte(),
                0xB7.toByte(), 0x26, 0x99.toByte(), 0xC7.toByte(),
            ),
            primaryEntries.copyOfRange(0, 16),
        )
        assertEquals(plan.partition1StartSector, gpt.partition1.startSector)
        assertEquals(plan.partition2StartSector, gpt.partition2.startSector)
        assertEquals("VTOYEFI", gpt.partition2.name)
        assertEquals(Long.MIN_VALUE, gpt.partition2.attributes)
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3),
            device.readBytes(VentoyDiskLayout.GPT_FIRST_USABLE_SECTOR * 512, 4),
        )
        assertEquals(0x23, device.readBytes(17908, 1)[0].toInt() and 0xff)
    }

    @Test
    fun `upgrade preserves partition one bytes`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val payload = syntheticPayload()
        val installer = VentoyInstaller(payload)
        val plan = installer.install(device, VentoyInstallOptions(forceInstall = true))
        val markerOffset = plan.partition1StartSector * 512 + 4L * 1024 * 1024
        val marker = "keep-me".encodeToByteArray()
        device.write(markerOffset, marker)
        val identity = device.readBytes(VentoyDiskLayout.VENTOY_UUID_OFFSET.toLong(), 60)

        val info = installer.upgrade(device)

        assertTrue(info.supportedForUpgrade)
        assertArrayEquals(marker, device.readBytes(markerOffset, marker.size))
        assertArrayEquals(
            identity.copyOfRange(0, 16),
            device.readBytes(VentoyDiskLayout.VENTOY_UUID_OFFSET.toLong(), 16),
        )
        assertArrayEquals(
            identity.copyOfRange(56, 60),
            device.readBytes(VentoyDiskLayout.DISK_SIGNATURE_OFFSET.toLong(), 4),
        )
    }

    @Test
    fun `upgrade preserves extra MBR partitions in reserved space`() {
        val device = memoryDevice(64L * 1024 * 1024)
        val installer = VentoyInstaller(syntheticPayload())
        installer.install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                reservedSpaceBytes = 8L * 1024 * 1024,
            ),
        )
        val extraEntriesOffset = VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 2 * 16
        val extraEntries = ByteArray(32) { (it + 1).toByte() }
        device.write(extraEntriesOffset.toLong(), extraEntries)

        installer.upgrade(device)

        assertArrayEquals(extraEntries, device.readBytes(extraEntriesOffset.toLong(), 32))
    }

    @Test
    fun `upgrade preserves GPT tables and partition one bytes`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val installer = VentoyInstaller(syntheticPayload())
        val plan = installer.install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )
        val markerOffset = plan.partition1StartSector * 512 + 2L * 1024 * 1024
        val marker = "keep-gpt".encodeToByteArray()
        device.write(markerOffset, marker)
        val coreTailOffset = 2040L * 512
        val coreTailMarker = "keep-core-tail".encodeToByteArray()
        device.write(coreTailOffset, coreTailMarker)
        val primaryGpt = device.readBytes(512, 33 * 512)
        val backupGpt = device.readBytes(device.sizeBytes - 33L * 512, 33 * 512)

        val info = installer.upgrade(device)

        assertEquals(VentoyPartitionStyle.Gpt, info.partitionStyle)
        assertArrayEquals(marker, device.readBytes(markerOffset, marker.size))
        assertArrayEquals(
            coreTailMarker,
            device.readBytes(coreTailOffset, coreTailMarker.size),
        )
        assertArrayEquals(primaryGpt, device.readBytes(512, primaryGpt.size))
        assertArrayEquals(
            backupGpt,
            device.readBytes(device.sizeBytes - backupGpt.size, backupGpt.size),
        )
    }

    @Test
    fun `repair rebuilds damaged primary GPT and preserves disk identity and files`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val installer = VentoyInstaller(syntheticPayload())
        val plan = installer.install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )
        val diskGuid = device.readBytes(512 + 56, 16)
        val partitionGuids = device.readBytes(2L * 512 + 16, 16 + 128)
        val markerOffset = plan.partition1StartSector * 512 + 1024L * 1024
        val marker = "repair-keeps-me".encodeToByteArray()
        device.write(markerOffset, marker)
        device.write(512 + 24, byteArrayOf(0x7F))

        val damagedInfo = VentoyDiskScanner().scan(device)
        assertTrue(damagedInfo?.needsRepair == true)

        val repairedInfo = installer.upgrade(device)

        assertFalse(repairedInfo.needsRepair)
        assertTrue(VentoyGpt.read(device).redundancyHealthy)
        assertArrayEquals(diskGuid, device.readBytes(512 + 56, 16))
        assertArrayEquals(partitionGuids, device.readBytes(2L * 512 + 16, 16 + 128))
        assertArrayEquals(marker, device.readBytes(markerOffset, marker.size))
    }

    @Test
    fun `repair restores a damaged protective MBR and preserves files`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val installer = VentoyInstaller(syntheticPayload())
        val plan = installer.install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )
        val markerOffset = plan.partition1StartSector * 512 + 1024L * 1024
        val marker = "protective-mbr-repair".encodeToByteArray()
        device.write(markerOffset, marker)
        device.write(510, byteArrayOf(0, 0))

        assertTrue(VentoyDiskScanner().scan(device)?.needsRepair == true)

        val repaired = installer.upgrade(device)

        assertFalse(repaired.needsRepair)
        assertTrue(VentoyMbr.hasBootSignature(device.readBytes(0, 512)))
        assertEquals(0xEE, VentoyMbr.parse(device.readBytes(0, 512)).first().type)
        assertArrayEquals(marker, device.readBytes(markerOffset, marker.size))
    }

    @Test
    fun `repairs newer GPT metadata without downgrading its payload`() {
        val device = memoryDevice(40L * 1024 * 1024)
        val installedPayload = syntheticPayload("1.1.17")
        val plan = VentoyInstaller(installedPayload).install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )
        val scanner = Mockito.mock(VentoyDiskScanner::class.java)
        val detected = VentoyDiskScanner().scan(device)!!.copy(
            installedVersion = "1.1.17",
            needsRepair = true,
        )
        Mockito.`when`(scanner.scan(device)).thenReturn(
            detected,
            detected.copy(needsRepair = false),
        )
        val coreBefore = device.readBytes(34L * 512, 2014 * 512)
        val payloadBefore = device.readBytes(plan.partition2StartSector * 512, 1024 * 1024)
        device.write(512 + 24, byteArrayOf(0x7F))

        val repaired = VentoyInstaller(
            payload = syntheticPayload("1.1.16"),
            scanner = scanner,
        ).upgrade(device)

        assertFalse(repaired.needsRepair)
        assertTrue(VentoyGpt.read(device).redundancyHealthy)
        assertArrayEquals(coreBefore, device.readBytes(34L * 512, coreBefore.size))
        assertArrayEquals(
            payloadBefore,
            device.readBytes(plan.partition2StartSector * 512, payloadBefore.size),
        )
    }

    @Test
    fun `post-write verification rejects a corrupted VTOYEFI payload`() {
        val backing = memoryDevice(40L * 1024 * 1024)
        val plan = VentoyDiskLayout.plan(backing.sizeBytes, backing.blockSize, "1.1.16")
        val device = CorruptingRawBlockDevice(
            delegate = backing,
            targetOffset = plan.partition2StartSector * 512 + 4096,
        )

        assertThrows<IllegalArgumentException> {
            VentoyInstaller(syntheticPayload()).install(
                device,
                VentoyInstallOptions(forceInstall = true),
            )
        }
    }
}

class BlockDeviceRawBlockDeviceTest {
    @Test
    fun `batches aligned transfers into one driver command`() {
        val driver = CountingBlockDeviceDriver(blocks = 16, blockSize = 512)
        val device = BlockDeviceRawBlockDevice(driver)
        val bytes = ByteArray(4 * 512) { (it % 251).toByte() }

        device.write(2L * 512, bytes)
        val readBack = device.readBytes(2L * 512, bytes.size)

        assertArrayEquals(bytes, readBack)
        assertEquals(1, driver.writeCalls)
        assertEquals(1, driver.readCalls)
    }

    @Test
    fun `classifies driver read failures as recoverable USB errors`() {
        val exception = assertThrows<UsbCommunicationException> {
            BlockDeviceRawBlockDevice(failingDriver()).read(0, ByteArray(512))
        }

        assertTrue(exception.cause is IOException)
        assertTrue(exception.cause?.message?.contains("MAX_RECOVERY_ATTEMPTS") == true)
    }

    @Test
    fun `classifies driver write failures as recoverable USB errors`() {
        val exception = assertThrows<UsbCommunicationException> {
            BlockDeviceRawBlockDevice(failingDriver()).write(0, ByteArray(512))
        }

        assertTrue(exception.cause is IOException)
        assertTrue(exception.cause?.message?.contains("MAX_RECOVERY_ATTEMPTS") == true)
    }

    private fun failingDriver() = object : BlockDeviceDriver {
        override val blockSize = 512
        override val blocks = 1024L

        override fun init() = Unit

        override fun read(deviceOffset: Long, buffer: ByteBuffer) {
            throw transferFailure()
        }

        override fun write(deviceOffset: Long, buffer: ByteBuffer) {
            throw transferFailure()
        }
    }

    private fun transferFailure() = IOException(
        "MAX_RECOVERY_ATTEMPTS Exceeded while trying to transfer command to device"
    )
}

private class CountingBlockDeviceDriver(
    override val blocks: Long,
    override val blockSize: Int,
) : BlockDeviceDriver {
    private val bytes = ByteArray((blocks * blockSize).toInt())
    var readCalls = 0
    var writeCalls = 0

    override fun init() = Unit

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        readCalls++
        val offset = (deviceOffset * blockSize).toInt()
        buffer.put(bytes, offset, buffer.remaining())
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        writeCalls++
        val offset = (deviceOffset * blockSize).toInt()
        buffer.get(bytes, offset, buffer.remaining())
    }
}

@ExtendWith(RobolectricExtension::class)
@Config(application = VendroidApplication::class)
class VentoyVolumeManagerTest {
    @Test
    fun `lists copies and deletes supported images`() {
        val root = FakeDocumentNode("Ventoy", Uri.parse("content://vendroid/root"), isDirectory = true)
        root.children += FakeDocumentNode("boot.iso", Uri.parse("content://vendroid/boot.iso"), isFile = true, bytes = byteArrayOf(1))
        root.children += FakeDocumentNode("notes.txt", Uri.parse("content://vendroid/notes.txt"), isFile = true, bytes = byteArrayOf(2))
        val streams = mutableMapOf<Uri, ByteArrayOutputStream>()
        val source = Uri.parse("content://vendroid/source.img")
        val manager = VentoyVolumeManager(
            root = root,
            openInputStream = { uri -> if (uri == source) ByteArrayInputStream(byteArrayOf(3, 4, 5)) else null },
            openOutputStream = { uri ->
                streams.getOrPut(uri) { ByteArrayOutputStream() }
            },
        )

        assertEquals(listOf("boot.iso"), manager.listImages().map { it.name })
        val copied = manager.copyImage(source, "source.img")
        assertEquals(byteArrayOf(3, 4, 5).toList(), streams.getValue(copied.uri).toByteArray().toList())
        assertTrue(manager.deleteImage(copied))
        assertFalse(manager.listImages().any { it.uri == copied.uri })
    }
}

private fun syntheticPayload(version: String = "1.1.16"): VentoyPayload =
    VentoyPayload.fromBytes(version, syntheticPayloadFiles(version))

private fun syntheticPayloadFiles(version: String = "1.1.16"): Map<String, ByteArray> {
    val core = ByteArray(1024 * 1024 - 512) { (it % 251).toByte() }
    val disk = ByteArray(32 * 1024 * 1024) { if (it == 0) 0xEB.toByte() else 0 }
    return mapOf(
        "boot/boot.img" to ByteArray(512) { 0x42 },
        "boot/core.img.xz" to xz(core),
        "ventoy/ventoy.disk.img.xz" to xz(disk),
        "ventoy/version" to version.encodeToByteArray(),
    )
}

private fun xz(bytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    XZOutputStream(output, LZMA2Options(1)).use { it.write(bytes) }
    return output.toByteArray()
}

private fun memoryDevice(size: Long): RawBlockDevice =
    BlockDeviceRawBlockDevice(MemoryBufferBlockDeviceDriver(size, 512))

private fun installedGptDevice(): RawBlockDevice =
    memoryDevice(40L * 1024 * 1024).also { device ->
        VentoyInstaller(syntheticPayload()).install(
            device,
            VentoyInstallOptions(
                forceInstall = true,
                partitionStyle = VentoyPartitionStyle.Gpt,
            ),
        )
    }

private fun rewriteGptPartitionType(
    device: RawBlockDevice,
    tableStartSector: Long,
    headerSector: Long,
) {
    val tableLength = VentoyDiskLayout.GPT_PARTITION_ENTRY_COUNT *
        VentoyDiskLayout.GPT_PARTITION_ENTRY_SIZE
    val table = device.readBytes(tableStartSector * 512, tableLength)
    table[0] = (table[0].toInt() xor 0x01).toByte()
    device.write(tableStartSector * 512, table)

    val tableCrc = CRC32().run {
        update(table)
        value
    }
    val header = device.readBytes(headerSector * 512, 512)
    header.writeUInt32Le(88, tableCrc)
    header.writeUInt32Le(16, 0)
    val headerSize = header.readUInt32Le(12).toInt()
    val headerCrc = CRC32().run {
        update(header, 0, headerSize)
        value
    }
    header.writeUInt32Le(16, headerCrc)
    device.write(headerSector * 512, header)
}

private class CorruptingRawBlockDevice(
    private val delegate: RawBlockDevice,
    private val targetOffset: Long,
) : RawBlockDevice by delegate {
    private var corrupted = false

    override fun write(
        offset: Long,
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
    ) {
        delegate.write(offset, source, sourceOffset, length)
        if (!corrupted && targetOffset in offset until offset + length) {
            val byte = delegate.readBytes(targetOffset, 1)
            byte[0] = (byte[0].toInt() xor 0x01).toByte()
            delegate.write(targetOffset, byte)
            corrupted = true
        }
    }
}

private class FailingWriteRawBlockDevice(
    private val delegate: RawBlockDevice,
    private val failingOffset: Long,
) : RawBlockDevice by delegate {
    override fun write(
        offset: Long,
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
    ) {
        if (failingOffset in offset until offset + length) {
            throw IOException("Injected GPT repair failure")
        }
        delegate.write(offset, source, sourceOffset, length)
    }
}

private fun rootEntryOffset(device: RawBlockDevice, plan: VentoyInstallPlan): Long {
    val bootSector = device.readBytes(plan.partition1StartSector * 512, 512)
    val clusterHeapOffset = bootSector.readUInt32Le(88)
    val rootCluster = bootSector.readUInt32Le(96)
    val clusterSize = 1 shl ((bootSector[108].toInt() and 0xff) + (bootSector[109].toInt() and 0xff))
    return clusterHeapOffset * 512 + (rootCluster - 2) * clusterSize
}

private class FakeDocumentNode(
    override val name: String,
    override val uri: Uri,
    override val isFile: Boolean = false,
    override val isDirectory: Boolean = false,
    bytes: ByteArray = ByteArray(0),
) : VentoyDocumentNode {
    val children = mutableListOf<FakeDocumentNode>()
    private var deleted = false
    override val length: Long = bytes.size.toLong()

    override fun listFiles(): List<VentoyDocumentNode> =
        children.filterNot { it.deleted }

    override fun createFile(mimeType: String, displayName: String): VentoyDocumentNode {
        val child = FakeDocumentNode(
            name = displayName,
            uri = Uri.parse("content://vendroid/$displayName"),
            isFile = true,
        )
        children += child
        return child
    }

    override fun delete(): Boolean {
        deleted = true
        return true
    }
}
