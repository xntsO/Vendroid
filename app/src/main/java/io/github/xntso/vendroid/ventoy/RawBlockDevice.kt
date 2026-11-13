package io.github.xntso.vendroid.ventoy

import io.github.xntso.vendroid.massstorage.CacheSynchronizingBlockDevice
import io.github.xntso.vendroid.utils.exception.UsbCommunicationException
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

interface RawBlockDevice {
    val sizeBytes: Long
    val blockSize: Int

    fun read(offset: Long, destination: ByteArray, destinationOffset: Int = 0, length: Int = destination.size - destinationOffset)
    fun write(offset: Long, source: ByteArray, sourceOffset: Int = 0, length: Int = source.size - sourceOffset)
    fun flush() = Unit
}

/**
 * Linux sends at most 240 sectors per USB storage command because some drives fail on larger
 * ones. Vendroid otherwise sends up to 1 MiB.
 */
const val SMALL_USB_TRANSFER_BYTES = 240 * 512

/**
 * @param maxTransferBytes the most bytes one driver call may transfer, rounded down to whole
 * blocks, or null for no limit beyond the driver's own.
 */
class BlockDeviceRawBlockDevice(
    private val driver: BlockDeviceDriver,
    private val maxTransferBytes: Int? = null,
) : RawBlockDevice {
    init {
        require(maxTransferBytes == null || maxTransferBytes > 0) { "Transfer limit must be positive" }
    }

    private val maxAlignedTransferBytes: Int
        get() = maxTransferBytes?.let { maxOf(blockSize, it - it % blockSize) } ?: Int.MAX_VALUE

    override val sizeBytes: Long
        get() = driver.blocks * driver.blockSize.toLong()

    override val blockSize: Int
        get() = driver.blockSize

    override fun read(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
        checkRange(offset, length)
        require(destinationOffset >= 0 && length >= 0 && destinationOffset + length <= destination.size) {
            "Invalid destination range"
        }

        var remaining = length
        var currentOffset = offset
        var outputOffset = destinationOffset
        val block = ByteArray(blockSize)

        while (remaining > 0) {
            val blockNumber = currentOffset / blockSize
            val withinBlock = (currentOffset % blockSize).toInt()
            val copyLength: Int

            if (withinBlock == 0 && remaining >= blockSize) {
                copyLength = minOf(remaining - (remaining % blockSize), maxAlignedTransferBytes)
                readBlocks(blockNumber, ByteBuffer.wrap(destination, outputOffset, copyLength).slice())
            } else {
                copyLength = minOf(remaining, blockSize - withinBlock)
                readBlocks(blockNumber, ByteBuffer.wrap(block))
                System.arraycopy(block, withinBlock, destination, outputOffset, copyLength)
            }

            currentOffset += copyLength
            outputOffset += copyLength
            remaining -= copyLength
        }
    }

    override fun write(offset: Long, source: ByteArray, sourceOffset: Int, length: Int) {
        checkRange(offset, length)
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= source.size) {
            "Invalid source range"
        }

        var remaining = length
        var currentOffset = offset
        var inputOffset = sourceOffset
        val block = ByteArray(blockSize)

        while (remaining > 0) {
            val blockNumber = currentOffset / blockSize
            val withinBlock = (currentOffset % blockSize).toInt()
            val copyLength: Int

            val writeBuffer = if (withinBlock == 0 && remaining >= blockSize) {
                copyLength = minOf(remaining - (remaining % blockSize), maxAlignedTransferBytes)
                ByteBuffer.wrap(source, inputOffset, copyLength).slice()
            } else {
                copyLength = minOf(remaining, blockSize - withinBlock)
                readBlocks(blockNumber, ByteBuffer.wrap(block))
                System.arraycopy(source, inputOffset, block, withinBlock, copyLength)
                ByteBuffer.wrap(block)
            }
            writeBlocks(blockNumber, writeBuffer)

            currentOffset += copyLength
            inputOffset += copyLength
            remaining -= copyLength
        }
    }

    override fun flush() {
        val cachingDriver = driver as? CacheSynchronizingBlockDevice ?: return
        try {
            cachingDriver.synchronizeCache()
        } catch (exception: IOException) {
            throw UsbCommunicationException(exception)
        }
    }

    private fun readBlocks(blockNumber: Long, buffer: ByteBuffer) {
        try {
            driver.read(blockNumber, buffer)
        } catch (exception: IOException) {
            throw UsbCommunicationException(exception)
        }
    }

    private fun writeBlocks(blockNumber: Long, buffer: ByteBuffer) {
        try {
            driver.write(blockNumber, buffer)
        } catch (exception: IOException) {
            throw UsbCommunicationException(exception)
        }
    }

    private fun checkRange(offset: Long, length: Int) {
        require(offset >= 0) { "Offset must be non-negative" }
        require(length >= 0) { "Length must be non-negative" }
        if (offset + length > sizeBytes) {
            throw EOFException("I/O range $offset..${offset + length} exceeds device size $sizeBytes")
        }
    }
}

fun RawBlockDevice.readBytes(offset: Long, length: Int): ByteArray =
    ByteArray(length).also { read(offset, it) }

fun RawBlockDevice.writeZeros(
    offset: Long,
    length: Long,
    onProgress: (processedBytes: Long) -> Unit = {},
) {
    require(offset >= 0) { "Offset must be non-negative" }
    require(length >= 0) { "Length must be non-negative" }

    val zeroBuffer = ByteArray(1024 * 1024)
    var remaining = length
    var currentOffset = offset
    while (remaining > 0) {
        val chunk = minOf(remaining, zeroBuffer.size.toLong()).toInt()
        write(currentOffset, zeroBuffer, 0, chunk)
        currentOffset += chunk
        remaining -= chunk
        onProgress(length - remaining)
    }
}

fun RawBlockDevice.writeInChunks(
    offset: Long,
    bytes: ByteArray,
    onProgress: (processedBytes: Long) -> Unit = {},
) {
    var written = 0
    while (written < bytes.size) {
        val chunk = minOf(bytes.size - written, 1024 * 1024)
        write(offset + written, bytes, written, chunk)
        written += chunk
        onProgress(written.toLong())
    }
}
