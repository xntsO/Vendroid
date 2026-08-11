package io.github.xntso.vendroid.ventoy

import org.tukaani.xz.XZInputStream
import java.security.SecureRandom

class VentoyInstaller(
    private val payload: VentoyPayload,
    private val exFatFormatter: ExFatFormatter = ExFatFormatter(),
    private val scanner: VentoyDiskScanner = VentoyDiskScanner(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun plan(
        device: RawBlockDevice,
        options: VentoyInstallOptions = VentoyInstallOptions(),
    ): VentoyInstallPlan = VentoyDiskLayout.plan(
        diskSizeBytes = device.sizeBytes,
        blockSize = device.blockSize,
        payloadVersion = payload.version,
        reservedSpaceBytes = options.reservedSpaceBytes,
        partitionStyle = options.partitionStyle,
    )

    fun install(
        device: RawBlockDevice,
        options: VentoyInstallOptions = VentoyInstallOptions(),
        onProgress: (VentoyInstallProgress) -> Unit = {},
    ): VentoyInstallPlan {
        require(options.secureBoot) {
            "Secure Boot is always enabled in this version."
        }
        options.validate()

        onProgress(VentoyInstallProgress(VentoyInstallStage.ValidatingPayload))
        payload.validate()
        val plan = plan(device, options)

        if (!options.forceInstall && scanner.hasAnyPartition(device) && scanner.scan(device) == null) {
            throw IllegalStateException("The USB drive already has a partition table. Use force install to overwrite it.")
        }

        onProgress(VentoyInstallProgress(VentoyInstallStage.Partitioning))
        zeroInstallAreas(device)

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingBootloader))
        writePartitionLayout(device, plan)
        writeCoreImage(device, plan, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingVentoyPayload))
        writeVentoyDiskImage(device, plan, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.FormattingExFat))
        exFatFormatter.format(
            device = device,
            partitionStartSector = plan.partition1StartSector,
            partitionSectorCount = plan.partition1SectorCount,
            label = options.label,
            clusterSize = options.clusterSize,
        )

        onProgress(VentoyInstallProgress(VentoyInstallStage.Verifying))
        verifyPartitionLayout(device, plan)
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
            ?: throw IllegalStateException("This drive is not a supported Ventoy disk.")

        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        val preservedUuid = mbr.copyOfRange(
            VentoyDiskLayout.VENTOY_UUID_OFFSET,
            VentoyDiskLayout.VENTOY_UUID_OFFSET + 16,
        )
        val preservedDiskSignature = mbr.copyOfRange(
            VentoyDiskLayout.DISK_SIGNATURE_OFFSET,
            VentoyDiskLayout.DISK_SIGNATURE_OFFSET + 4,
        )
        val preservedExtraPartitionEntries = mbr.copyOfRange(
            VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 2 * 16,
            VentoyDiskLayout.MBR_PARTITION_TABLE_OFFSET + 4 * 16,
        )
        val plan = VentoyInstallPlan(
            diskSizeBytes = device.sizeBytes,
            partition1StartSector = diskInfo.partition1StartSector,
            partition1EndSector = diskInfo.partition1EndSector,
            partition2StartSector = diskInfo.partition2StartSector,
            partition2EndSector = diskInfo.partition2EndSector,
            payloadVersion = payload.version,
            partitionStyle = diskInfo.partitionStyle,
        )

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingBootloader))
        if (plan.partitionStyle == VentoyPartitionStyle.Mbr) {
            writeMbr(
                device,
                plan,
                preservedUuid,
                preservedDiskSignature,
                preservedExtraPartitionEntries,
            )
            writeCoreImage(device, plan, onProgress)
        } else {
            writeGptUpgradeBootRecord(device, preservedUuid)
            val preservedCoreTail = device.readBytes(
                CORE_TAIL_START_SECTOR * VentoyDiskLayout.SECTOR_SIZE,
                CORE_TAIL_SECTOR_COUNT * VentoyDiskLayout.SECTOR_SIZE,
            )
            writeCoreImage(device, plan, onProgress)
            device.write(
                CORE_TAIL_START_SECTOR * VentoyDiskLayout.SECTOR_SIZE,
                preservedCoreTail,
            )
        }

        onProgress(VentoyInstallProgress(VentoyInstallStage.WritingVentoyPayload))
        writeVentoyDiskImage(device, plan, onProgress)

        onProgress(VentoyInstallProgress(VentoyInstallStage.Verifying))
        verifyPartitionLayout(device, plan)
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

    private fun writePartitionLayout(device: RawBlockDevice, plan: VentoyInstallPlan) {
        when (plan.partitionStyle) {
            VentoyPartitionStyle.Mbr -> writeMbr(device, plan)
            VentoyPartitionStyle.Gpt -> {
                VentoyGpt.write(device, plan, random)
                val protectiveMbr = VentoyMbr.buildProtective(
                    bootImage = payload.bootImage(),
                    diskSizeBytes = device.sizeBytes,
                    random = random,
                )
                device.write(0, protectiveMbr)
            }
        }
    }

    private fun writeMbr(
        device: RawBlockDevice,
        plan: VentoyInstallPlan,
        preservedVentoyUuid: ByteArray? = null,
        preservedDiskSignature: ByteArray? = null,
        preservedExtraPartitionEntries: ByteArray? = null,
    ) {
        val mbr = VentoyMbr.build(
            bootImage = payload.bootImage(),
            plan = plan,
            random = random,
            preservedVentoyUuid = preservedVentoyUuid,
            preservedDiskSignature = preservedDiskSignature,
            preservedExtraPartitionEntries = preservedExtraPartitionEntries,
        )
        device.write(0, mbr)
    }

    private fun writeGptUpgradeBootRecord(
        device: RawBlockDevice,
        preservedVentoyUuid: ByteArray,
    ) {
        val bootCode = payload.bootImage().copyOfRange(0, VentoyDiskLayout.DISK_SIGNATURE_OFFSET)
        bootCode[92] = 0x22
        device.write(0, bootCode)
        device.write(VentoyDiskLayout.VENTOY_UUID_OFFSET.toLong(), preservedVentoyUuid)
    }

    private fun writeCoreImage(
        device: RawBlockDevice,
        plan: VentoyInstallPlan,
        onProgress: (VentoyInstallProgress) -> Unit,
    ) {
        val startSector: Long
        val sectorCount: Long
        val requireEndOfStream: Boolean
        when (plan.partitionStyle) {
            VentoyPartitionStyle.Mbr -> {
                startSector = 1
                sectorCount = 2047
                requireEndOfStream = true
            }
            VentoyPartitionStyle.Gpt -> {
                startSector = VentoyDiskLayout.GPT_FIRST_USABLE_SECTOR
                sectorCount = 2014
                requireEndOfStream = false
            }
        }
        val expectedBytes = sectorCount * VentoyDiskLayout.SECTOR_SIZE
        payload.openCoreImage().use { compressed ->
            XZInputStream(compressed).use { input ->
                copyExactToDevice(
                    input = input,
                    device = device,
                    offset = startSector * VentoyDiskLayout.SECTOR_SIZE,
                    expectedBytes = expectedBytes,
                    stage = VentoyInstallStage.WritingBootloader,
                    onProgress = onProgress,
                    requireEndOfStream = requireEndOfStream,
                )
            }
        }
        if (plan.partitionStyle == VentoyPartitionStyle.Gpt) {
            device.write(GPT_CORE_PATCH_OFFSET, byteArrayOf(0x23))
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

    private fun copyExactToDevice(
        input: java.io.InputStream,
        device: RawBlockDevice,
        offset: Long,
        expectedBytes: Long,
        stage: VentoyInstallStage,
        onProgress: (VentoyInstallProgress) -> Unit,
        requireEndOfStream: Boolean,
    ) {
        val buffer = ByteArray(1024 * 1024)
        var written = 0L
        while (written < expectedBytes) {
            val requested = minOf(buffer.size.toLong(), expectedBytes - written).toInt()
            val read = input.read(buffer, 0, requested)
            require(read > 0) {
                "Ventoy payload decompressed to $written bytes; expected at least $expectedBytes bytes."
            }
            device.write(offset + written, buffer, 0, read)
            written += read
            onProgress(VentoyInstallProgress(stage, written, expectedBytes))
        }
        if (requireEndOfStream) {
            require(input.read() < 0) {
                "Ventoy payload exceeds its reserved disk area."
            }
        }
    }

    private fun verifyPartitionLayout(device: RawBlockDevice, plan: VentoyInstallPlan) {
        when (plan.partitionStyle) {
            VentoyPartitionStyle.Mbr -> verifyMbr(device, plan)
            VentoyPartitionStyle.Gpt -> verifyGpt(device, plan)
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

    private fun verifyGpt(device: RawBlockDevice, plan: VentoyInstallPlan) {
        val mbr = device.readBytes(0, VentoyDiskLayout.SECTOR_SIZE)
        require(VentoyMbr.hasBootSignature(mbr)) { "Missing protective MBR boot signature." }
        val protectiveEntry = VentoyMbr.parse(mbr).first()
        require(protectiveEntry.type == 0xEE && protectiveEntry.startSector == 1L) {
            "Missing protective MBR partition entry."
        }

        val gpt = VentoyGpt.read(device)
        require(gpt.partition1.startSector == plan.partition1StartSector) {
            "Ventoy data partition start mismatch."
        }
        require(gpt.partition1.sectorCount == plan.partition1SectorCount) {
            "Ventoy data partition size mismatch."
        }
        require(gpt.partition2.startSector == plan.partition2StartSector) {
            "VTOYEFI partition start mismatch."
        }
        require(gpt.partition2.sectorCount == plan.partition2SectorCount) {
            "VTOYEFI partition size mismatch."
        }
        require(gpt.partition2.name == "VTOYEFI" && gpt.partition2.attributes == Long.MIN_VALUE) {
            "VTOYEFI GPT metadata mismatch."
        }
    }

    private companion object {
        const val GPT_CORE_PATCH_OFFSET = 17908L
        const val CORE_TAIL_START_SECTOR = 2040L
        const val CORE_TAIL_SECTOR_COUNT = 8
    }
}
