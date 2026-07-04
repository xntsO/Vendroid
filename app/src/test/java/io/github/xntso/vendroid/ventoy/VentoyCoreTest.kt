package io.github.xntso.vendroid.ventoy

import android.net.Uri
import io.github.xntso.vendroid.MemoryBufferBlockDeviceDriver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

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
        assertNotEquals(0, device.readBytes(512, 1)[0].toInt())
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

        val info = installer.upgrade(device)

        assertTrue(info.supportedForUpgrade)
        assertArrayEquals(marker, device.readBytes(markerOffset, marker.size))
    }
}

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
