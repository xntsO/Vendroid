package io.github.xntso.vendroid.ventoy

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

class BlockDeviceRawBlockDevice(
    private val driver: BlockDeviceDriver,
) : RawBlockDevice {
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
                copyLength = remaining - (remaining % blockSize)
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
                copyLength = remaining - (remaining % blockSize)
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

fun RawBlockDevice.writeZeros(offset: Long, length: Long) {
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
    }
}
