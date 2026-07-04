package io.github.xntso.vendroid.ventoy

import org.tukaani.xz.XZInputStream
import java.security.SecureRandom

class VentoyInstaller(
    private val payload: VentoyPayload,
    private val exFatFormatter: ExFatFormatter = ExFatFormatter(),
    private val scanner: VentoyDiskScanner = VentoyDiskScanner(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun plan(device: RawBlockDevice): VentoyInstallPlan =
        VentoyDiskLayout.plan(device.sizeBytes, device.blockSize, payload.version)

    fun install(
        device: RawBlockDevice,
        options: VentoyInstallOptions = VentoyInstallOptions(),
        onProgress: (VentoyInstallProgress) -> Unit = {},
    ): VentoyInstallPlan {
        require(options.partitionStyle == VentoyPartitionStyle.Mbr) {
            "Only MBR Ventoy installs are supported in this version."
        }
        require(options.secureBoot) {
            "Secure Boot is always enabled in this version."
        }

        onProgress(VentoyInstallProgress(VentoyInstallStage.ValidatingPayload))
        payload.validate()
        val plan = plan(device)

        if (!options.forceInstall && scanner.hasAnyMbrPartition(device) && scanner.scan(device) == null) {
            throw IllegalStateException("The USB drive already has a partition table. Use force install to overwrite it.")
        }

        onProgress(VentoyInstallProgress(VentoyInstallStage.Partitioning))
        zeroInstallAreas(device)

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingBootloader))
        writeMbr(device, plan)
        writeCoreImage(device, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingVentoyPayload))
        writeVentoyDiskImage(device, plan, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.FormattingExFat))
        exFatFormatter.format(
            device = device,
            partitionStartSector = plan.partition1StartSector,
            partitionSectorCount = plan.partition1SectorCount,
            label = options.label,
        )

        onProgress(VentoyInstallProgress(VentoyInstallStage.Verifying))
        verifyMbr(device, plan)
        onProgress(VentoyInstallProgress(VentoyInstallStage.Complete))
        return plan
    }

    fun upgrade(
        device: RawBlockDevice,
        onProgress: (VentoyInstallProgress) -> Unit = {},
    ): VentoyDiskInfo {
        onProgress(VentoyInstallProgress(VentoyInstallStage.ValidatingPayload))
        payload.validate()

        val diskInfo = scanner.scan(device)
            ?: throw IllegalStateException("This drive is not a supported MBR Ventoy disk.")

        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        val preservedUuid = mbr.copyOfRange(
            VentoyDiskLayout.VENTOY_UUID_OFFSET,
            VentoyDiskLayout.VENTOY_UUID_OFFSET + 16,
        )
        val preservedDiskSignature = mbr.copyOfRange(
            VentoyDiskLayout.DISK_SIGNATURE_OFFSET,
            VentoyDiskLayout.DISK_SIGNATURE_OFFSET + 4,
        )
        val plan = VentoyInstallPlan(
            diskSizeBytes = device.sizeBytes,
            partition1StartSector = diskInfo.partition1StartSector,
            partition1EndSector = diskInfo.partition1EndSector,
            partition2StartSector = diskInfo.partition2StartSector,
            partition2EndSector = diskInfo.partition2EndSector,
            payloadVersion = payload.version,
        )

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingBootloader))
        writeMbr(device, plan, preservedUuid, preservedDiskSignature)
        writeCoreImage(device, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingVentoyPayload))
        writeVentoyDiskImage(device, plan, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.Verifying))
        verifyMbr(device, plan)
        onProgress(VentoyInstallProgress(VentoyInstallStage.Complete))
        return scanner.scan(device) ?: diskInfo.copy(installedVersion = payload.version)
    }

    private fun zeroInstallAreas(device: RawBlockDevice) {
        val zeroLength = minOf(2L * 1024L * 1024L, device.sizeBytes)
        device.writeZeros(0, zeroLength)
        if (device.sizeBytes > zeroLength) {
            device.writeZeros(device.sizeBytes - zeroLength, zeroLength)
        }
    }

    private fun writeMbr(
        device: RawBlockDevice,
        plan: VentoyInstallPlan,
        preservedVentoyUuid: ByteArray? = null,
        preservedDiskSignature: ByteArray? = null,
    ) {
        val mbr = VentoyMbr.build(
            bootImage = payload.bootImage(),
            plan = plan,
            random = random,
            preservedVentoyUuid = preservedVentoyUuid,
            preservedDiskSignature = preservedDiskSignature,
        )
        device.write(0, mbr)
    }

    private fun writeCoreImage(
        device: RawBlockDevice,
        onProgress: (VentoyInstallProgress) -> Unit,
    ) {
        payload.openCoreImage().use { compressed ->
            XZInputStream(compressed).use { input ->
                copyToDevice(
                    input = input,
                    device = device,
                    offset = VentoyDiskLayout.SECTOR_SIZE.toLong(),
                    maxBytes = 1024L * 1024L - VentoyDiskLayout.SECTOR_SIZE,
                    expectedBytes = 1024L * 1024L - VentoyDiskLayout.SECTOR_SIZE,
                    stage = VentoyInstallStage.WritingBootloader,
                    onProgress = onProgress,
                )
            }
        }
    }

    private fun writeVentoyDiskImage(
        device: RawBlockDevice,
        plan: VentoyInstallPlan,
        onProgress: (VentoyInstallProgress) -> Unit,
    ) {
        val partitionBytes = plan.partition2SectorCount * VentoyDiskLayout.SECTOR_SIZE
        payload.openVentoyDiskImage().use { compressed ->
            XZInputStream(compressed).use { input ->
                copyToDevice(
                    input = input,
                    device = device,
                    offset = plan.partition2StartSector * VentoyDiskLayout.SECTOR_SIZE,
                    maxBytes = partitionBytes,
                    expectedBytes = partitionBytes,
                    stage = VentoyInstallStage.WritingVentoyPayload,
                    onProgress = onProgress,
                )
            }
        }
    }

    private fun copyToDevice(
        input: java.io.InputStream,
        device: RawBlockDevice,
        offset: Long,
        maxBytes: Long,
        expectedBytes: Long,
        stage: VentoyInstallStage,
        onProgress: (VentoyInstallProgress) -> Unit,
    ) {
        val buffer = ByteArray(1024 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (written + read > maxBytes) {
                throw IllegalStateException("Ventoy payload exceeds reserved disk area.")
            }
            device.write(offset + written, buffer, 0, read)
            written += read
            onProgress(VentoyInstallProgress(stage, written, expectedBytes))
        }
        require(written == expectedBytes) {
            "Ventoy payload decompressed to $written bytes; expected $expectedBytes bytes."
        }
    }

    private fun verifyMbr(device: RawBlockDevice, plan: VentoyInstallPlan) {
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        require(VentoyMbr.hasBootSignature(mbr)) { "Missing MBR boot signature after install." }

        val entries = VentoyMbr.parse(mbr)
        require(entries[0].active == 0x80) { "Ventoy data partition is not active." }
        require(entries[0].type == VentoyDiskLayout.PARTITION1_TYPE) { "Ventoy data partition type mismatch." }
        require(entries[0].startSector == plan.partition1StartSector) { "Ventoy data partition start mismatch." }
        require(entries[0].sectorCount == plan.partition1SectorCount) { "Ventoy data partition size mismatch." }
        require(entries[1].type == VentoyDiskLayout.PARTITION2_TYPE) { "VTOYEFI partition type mismatch." }
        require(entries[1].startSector == plan.partition2StartSector) { "VTOYEFI partition start mismatch." }
        require(entries[1].sectorCount == plan.partition2SectorCount) { "VTOYEFI partition size mismatch." }
    }
}
