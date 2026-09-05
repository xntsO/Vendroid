/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.github.xntso.vendroid.massstorage

import android.util.Log
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.*
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper.Direction
import me.jahnen.libaums.core.driver.scsi.commands.sense.*
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Adapted from libaums 0.10.0 ScsiBlockDevice, Apache-2.0.
 * Uses true block counts, READ CAPACITY(16), and 64-bit read/write commands.
 * Upstream source: https://github.com/magnusja/libaums/tree/af89120aa434ccd97b985a6d66420c1b7e30a1ad
 *
 * @author mjahnen, Derpalus
 * @see com.github.mjdev.libaums.driver.scsi.commands
 */
internal class VendroidScsiBlockDevice(private val usbCommunication: UsbCommunication, private val lun: Byte) : BlockDeviceDriver {
    private val outBuffer: ByteBuffer = ByteBuffer.allocate(31)
    private val cswBuffer: ByteBuffer = ByteBuffer.allocate(CommandStatusWrapper.SIZE)

    override var blockSize: Int = 0
        private set
    override var blocks: Long = 0
        private set
    private val csw = CommandStatusWrapper()

    private var cbwTagCounter = 1


    @Throws(IOException::class)
    override fun init() {
        blocks = 0
        blockSize = 0
        var lastException: Exception? = null
        for (i in 0..MAX_RECOVERY_ATTEMPTS) {
            try {
                initAttempt()
                return
            } catch (e: InitRequired) {
                Log.i(TAG, e.message ?: "Reinitializing device")
                lastException = e
            } catch (e: UnitAttention) {
                Log.i(TAG, e.message ?: "Device reported unit attention during initialization")
                lastException = e
            } catch (e: NotReadyTryAgain) {
                Log.i(TAG, e.message ?: "Reinitializing device")
                lastException = e
            }
            Thread.sleep(100)
        }

        throw IOException(
            "MAX_RECOVERY_ATTEMPTS Exceeded while trying to init communication with USB device, please reattach device and try again",
            lastException
        )
    }

    @Throws(IOException::class)
    private fun initAttempt() {
        val inBuffer = ByteBuffer.allocate(36)
        val inquiry = ScsiInquiry(inBuffer.array().size.toByte(), lun = lun)
        transferCommand(inquiry, inBuffer)
        inBuffer.clear()
        val inquiryResponse = ScsiInquiryResponse.read(inBuffer)
        Log.d(TAG, "inquiry response: $inquiryResponse")

        if (inquiryResponse.peripheralQualifier.toInt() != 0 || inquiryResponse.peripheralDeviceType.toInt() != 0) {
            throw IOException("unsupported PeripheralQualifier or PeripheralDeviceType")
        }

        val testUnit = ScsiTestUnitReady(lun = lun)
        transferCommandWithoutDataPhase(testUnit)

        val readCapacity = ScsiReadCapacity(lun = lun)
        inBuffer.clear()
        transferCommand(readCapacity, inBuffer)
        inBuffer.clear()
        inBuffer.order(ByteOrder.BIG_ENDIAN)
        var lastBlockAddress = inBuffer.int.toLong() and 0xffffffffL
        var logicalBlockSize = inBuffer.int.toLong() and 0xffffffffL
        if (lastBlockAddress == 0xffffffffL) {
            val capacity16 = ByteBuffer.allocate(32)
            transferCommand(ScsiCapacity16(lun), capacity16)
            capacity16.flip()
            lastBlockAddress = capacity16.long
            logicalBlockSize = capacity16.int.toLong() and 0xffffffffL
        }
        // Vendroid offsets and ByteBuffer lengths must fit signed JVM sizes.
        if (lastBlockAddress < 0 || lastBlockAddress == Long.MAX_VALUE ||
            logicalBlockSize <= 0 || logicalBlockSize > Int.MAX_VALUE ||
            lastBlockAddress + 1 > Long.MAX_VALUE / logicalBlockSize
        ) {
            throw IOException("Unsupported SCSI capacity or logical block size")
        }
        blockSize = logicalBlockSize.toInt()
        blocks = lastBlockAddress + 1

        Log.i(TAG, "Block size: $blockSize")
        Log.i(TAG, "Last block address: $lastBlockAddress")
    }

    @Throws(IOException::class)
    private fun transferCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer) {
        var lastException: Exception? = null
        val start = inBuffer.position()
        val limit = inBuffer.limit()
        for (i in 0..MAX_RECOVERY_ATTEMPTS) {
            inBuffer.limit(limit)
            inBuffer.position(start)
            try {
                val result = transferOneCommand(command, inBuffer)
                val senseWasNotIssued = handleCommandResult(result)
                if (senseWasNotIssued) {
                    return
                }

            } catch (e: SenseException) {
                Log.w(TAG, (e.message ?: "SenseException"))
                when (e) {
                    // Initialization handles unit attention at its outer retry boundary.
                    // During I/O, stop rather than replaying writes onto changed media.
                    is InitRequired -> throw e
                    is NotReadyTryAgain -> {} // try again
                    else -> throw e
                }
                lastException = e
            } catch (e: PipeException) {
                Log.w(TAG, (e.message ?: "PipeException") + ", try bulk storage reset and retry")
                bulkOnlyMassStorageReset()
                lastException = e
            } catch (e: IOException) {
                // An incomplete BOT transaction must be reset before another CBW.
                bulkOnlyMassStorageReset()
                Log.w(TAG, (e.message ?: "IOException") + ", retrying...")
                lastException = e
            }

            Thread.sleep(100)
        }

        inBuffer.limit(limit)
        inBuffer.position(start)
        throw IOException(
            "MAX_RECOVERY_ATTEMPTS Exceeded while trying to transfer command to device, please reattach device and try again",
            lastException
        )
    }

    @Throws(IOException::class)
    private fun transferCommandWithoutDataPhase(command: CommandBlockWrapper) {
        require(command.direction == Direction.NONE) { "Command has a data phase" }
        transferCommand(command, ByteBuffer.allocate(0))
    }

    @Throws(IOException::class)
    private fun handleCommandResult(status: Int): Boolean {
        return when (status) {
            CommandStatusWrapper.COMMAND_PASSED -> true
            CommandStatusWrapper.COMMAND_FAILED -> {
                requestSense()
                false
            }
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> throw IllegalStateException("CommandStatus wrapper illegal status $status")
        }
    }

    @Throws(IOException::class)
    private fun requestSense() {
        val inBuffer = ByteBuffer.allocate(18)
        val sense = ScsiRequestSense(inBuffer.array().size.toByte(), lun = lun)
        when (val status = transferOneCommand(sense, inBuffer)) {
            CommandStatusWrapper.COMMAND_PASSED -> {
                inBuffer.clear()
                val response = ScsiRequestSenseResponse.read(inBuffer)
                response.checkResponseForError()
            }
            CommandStatusWrapper.COMMAND_FAILED -> throw IOException("requesting sense failed")
            CommandStatusWrapper.PHASE_ERROR -> {
                bulkOnlyMassStorageReset()
                throw IOException("phase error, please reattach device and try again")
            }
            else -> throw IllegalStateException("CommandStatus wrapper illegal status $status")
        }
    }

    @Throws(IOException::class)
    private fun bulkOnlyMassStorageReset() {
        Log.w(TAG, "sending bulk only mass storage request")
        val bArr = ByteArray(2)
        // REQUEST_BULK_ONLY_MASS_STORAGE_RESET = 255
        // REQUEST_TYPE_BULK_ONLY_MASS_STORAGE_RESET = 33
        val transferred: Int = usbCommunication.controlTransfer(33, 255, 0, usbCommunication.usbInterface.id, bArr, 0)
        if (transferred == -1) {
            throw IOException("bulk only mass storage reset failed!")
        }
        Log.d(TAG, "Trying to clear halt on both endpoints")
        usbCommunication.clearFeatureHalt(usbCommunication.inEndpoint)
        usbCommunication.clearFeatureHalt(usbCommunication.outEndpoint)
    }

    @Throws(IOException::class)
    private fun transferOneCommand(command: CommandBlockWrapper, inBuffer: ByteBuffer): Int {
        val outArray = outBuffer.array()
        Arrays.fill(outArray, 0.toByte())

        command.dCbwTag = cbwTagCounter
        cbwTagCounter++

        outBuffer.clear()
        command.serialize(outBuffer)
        outBuffer.clear()

        var written = usbCommunication.bulkOutTransfer(outBuffer)
        if (written != outArray.size) {
            throw IOException("Writing all bytes on command $command failed!")
        }

        val dataStart = inBuffer.position()
        var transferLength = command.dCbwDataTransferLength
        inBuffer.limit(inBuffer.position() + transferLength)

        var read = 0
        if (transferLength > 0) {

            if (command.direction == Direction.IN) {
                do {
                    val count = usbCommunication.bulkInTransfer(inBuffer)
                    if (count <= 0) throw IOException("USB read made no progress")
                    read += count
                    if (command.bCbwDynamicSize && read >= 8) {
                        // REQUEST SENSE can advertise more bytes than our allocation,
                        // or return padding. Byte 7 is an unsigned additional length.
                        val advertisedLength = 8 + (inBuffer.get(dataStart + 7).toInt() and 0xff)
                        transferLength = minOf(command.dCbwDataTransferLength, maxOf(read, advertisedLength))
                        inBuffer.limit(dataStart + transferLength)
                    }
                } while (read < transferLength)

                if (read != transferLength) {
                    throw IOException("Unexpected command size (" + read + ") on response to "
                            + command)
                }
            } else {
                written = 0
                do {
                    val count = usbCommunication.bulkOutTransfer(inBuffer)
                    if (count <= 0) throw IOException("USB write made no progress")
                    written += count
                } while (written < transferLength)

                if (written != transferLength) {
                    throw IOException("Could not write all bytes: $command")
                }
            }
        }


        // expecting csw now
        cswBuffer.clear()

        read = usbCommunication.bulkInTransfer(cswBuffer)
        if (read != CommandStatusWrapper.SIZE) {
            throw IOException("Unexpected command size while expecting csw")
        }
        cswBuffer.clear()

        if (cswBuffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0) != 0x53425355) {
            throw IOException("Invalid CSW signature")
        }
        csw.read(cswBuffer)
        if (csw.dCswTag != command.dCbwTag) {
            throw IOException("wrong csw tag!")
        }
        val expectedResidue = command.dCbwDataTransferLength - transferLength
        if (csw.bCswStatus.toInt() == CommandStatusWrapper.COMMAND_PASSED &&
            csw.dCswDataResidue != expectedResidue
        ) {
            throw IOException("SCSI command completed with unprocessed bytes")
        }

        return csw.bCswStatus.toInt()
    }

    @Synchronized
    @Throws(IOException::class)
    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        transferBlocks(deviceOffset, buffer, Direction.IN)
    }

    @Synchronized
    @Throws(IOException::class)
    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        transferBlocks(deviceOffset, buffer, Direction.OUT)
    }

    private fun transferBlocks(deviceOffset: Long, buffer: ByteBuffer, direction: Direction) {
        check(blockSize > 0 && blocks > 0) { "SCSI device is not initialized" }
        require(buffer.remaining() % blockSize == 0) { "Buffer must contain whole logical blocks" }
        val count = buffer.remaining() / blockSize
        require(deviceOffset >= 0 && deviceOffset <= blocks && count.toLong() <= blocks - deviceOffset) {
            "I/O exceeds SCSI device capacity"
        }
        var lba = deviceOffset
        val maximumChunkBlocks = minOf(65535, maxOf(1, 1024 * 1024 / blockSize))
        // libusbcommunication 0.3.0 ignores arrayOffset(). Give JNI a zero-offset
        // buffer even when callers supply slices or positioned/direct buffers.
        val chunk = ByteBuffer.allocate(minOf(count, maximumChunkBlocks) * blockSize)
        while (buffer.hasRemaining()) {
            // Bound each USB command and avoid READ(10)'s 16-bit count overflow.
            val chunkBlocks = minOf(buffer.remaining() / blockSize, maximumChunkBlocks)
            val chunkBytes = chunkBlocks * blockSize
            chunk.clear()
            chunk.limit(chunkBytes)
            if (direction == Direction.OUT) {
                chunk.put(buffer.duplicate().apply { limit(position() + chunkBytes) })
                chunk.flip()
            }
            transferCommand(ScsiReadWrite(lun, direction, lba, chunkBlocks, chunkBytes), chunk)
            if (direction == Direction.IN) {
                chunk.flip()
                buffer.put(chunk)
            } else {
                buffer.position(buffer.position() + chunkBytes)
            }
            lba += chunkBlocks
        }
    }

    companion object {
        private const val MAX_RECOVERY_ATTEMPTS = 5
        private val TAG = VendroidScsiBlockDevice::class.java.simpleName
    }
}

private class ScsiCapacity16(lun: Byte) : CommandBlockWrapper(32, Direction.IN, lun, 16) {
    override fun serialize(buffer: ByteBuffer) {
        super.serialize(buffer)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x9e.toByte()).put(0x10).putLong(0).putInt(32).put(0).put(0)
    }
}

private class ScsiReadWrite(
    lun: Byte,
    direction: Direction,
    private val lba: Long,
    private val count: Int,
    bytes: Int,
) : CommandBlockWrapper(bytes, direction, lun, if (lba + count - 1 > 0xffffffffL) 16 else 10) {
    override fun serialize(buffer: ByteBuffer) {
        super.serialize(buffer)
        buffer.order(ByteOrder.BIG_ENDIAN)
        if (bCbwcbLength.toInt() == 16) {
            buffer.put((if (direction == Direction.IN) 0x88 else 0x8a).toByte())
            buffer.put(0).putLong(lba).putInt(count).put(0).put(0)
        } else {
            buffer.put((if (direction == Direction.IN) 0x28 else 0x2a).toByte())
            buffer.put(0).putInt(lba.toInt()).put(0).putShort(count.toShort()).put(0)
        }
    }
}
