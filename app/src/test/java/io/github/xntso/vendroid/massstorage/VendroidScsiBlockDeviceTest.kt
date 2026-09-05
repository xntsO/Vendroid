package io.github.xntso.vendroid.massstorage

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.driver.scsi.commands.sense.MediumError
import me.jahnen.libaums.core.driver.scsi.commands.sense.UnitAttention
import me.jahnen.libaums.core.usb.UsbCommunication
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [35])
class VendroidScsiBlockDeviceTest {
    @Test
    fun `capacity10 adds one without sign extending the unsigned last LBA`() {
        for (lastLba in listOf(0x7fffffffL, 0x80000000L, 0xfffffffeL)) {
            val usb = FakeUsbCommunication(lastLba)
            val device = initialized(usb)

            assertEquals(lastLba + 1, device.blocks)
            assertEquals(512, device.blockSize)
            assertEquals((lastLba + 1) * 512L, device.blocks * device.blockSize)
            assertEquals(listOf(0x12, 0x00, 0x25), usb.commands.map { it.opcode })
            val destination = ByteBuffer.allocate(512)
            device.read(lastLba, destination)
            assertEquals(lastLba, usb.ioCommands.single().lba)
            assertEquals(0x28, usb.ioCommands.single().opcode)
        }
    }

    @Test
    fun `capacity10 sentinel requests capacity16 and reports all 3 TiB blocks`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)

        assertEquals(6_442_450_944L, device.blocks)
        assertEquals(512, device.blockSize)
        assertEquals(3L * 1024 * 1024 * 1024 * 1024, device.blocks * device.blockSize)
        assertEquals(listOf(0x12, 0x00, 0x25, 0x9e), usb.commands.map { it.opcode })
        assertEquals(TEST_LUN.toInt(), usb.commands.last().lun)
    }

    @Test
    fun `high LBA read and write preserve data at the wrapped low LBA`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)
        val lowLba = 7L
        val highLba = 0x100000007L
        val lowSentinel = pattern(512, 19)
        val highSentinel = pattern(512, 93)
        usb.seed(lowLba, lowSentinel)
        usb.seed(highLba, highSentinel)

        val read = ByteBuffer.allocate(512)
        device.read(highLba, read)
        assertArrayEquals(highSentinel, read.array())

        val replacement = pattern(512, 147)
        device.write(highLba, ByteBuffer.wrap(replacement))
        assertArrayEquals(replacement, usb.block(highLba))
        assertArrayEquals(lowSentinel, usb.block(lowLba))
        assertEquals(listOf(0x88, 0x8a), usb.ioCommands.map { it.opcode })
        assertEquals(listOf(highLba, highLba), usb.ioCommands.map { it.lba })

        // Read both addresses through the driver as well as inspecting sparse storage.
        read.clear()
        device.read(lowLba, read)
        assertArrayEquals(lowSentinel, read.array())
        read.clear()
        device.read(highLba, read)
        assertArrayEquals(replacement, read.array())
    }

    @Test
    fun `transfer crossing ffffffff uses 16 byte commands and never wraps to block zero`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)
        val lowSentinel = pattern(512, 23)
        usb.seed(0, lowSentinel)
        val data = pattern(1024, 71)

        device.write(0xffffffffL, ByteBuffer.wrap(data))
        val read = ByteBuffer.allocate(1024)
        device.read(0xffffffffL, read)

        assertArrayEquals(data, read.array())
        assertArrayEquals(data.copyOfRange(0, 512), usb.block(0xffffffffL))
        assertArrayEquals(data.copyOfRange(512, 1024), usb.block(0x100000000L))
        assertArrayEquals(lowSentinel, usb.block(0))
        assertEquals(listOf(0x8a, 0x88), usb.ioCommands.map { it.opcode })
        assertTrue(usb.ioCommands.all { it.lba == 0xffffffffL && it.count == 2L })
    }

    @Test
    fun `ordinary transfers retain READ10 and WRITE10 with the requested LUN`() {
        val usb = FakeUsbCommunication(4095)
        val device = initialized(usb)
        val data = pattern(3 * 512, 41)
        val source = ByteBuffer.wrap(data)
        val destination = ByteBuffer.allocate(data.size)

        device.write(123, source)
        device.read(123, destination)

        assertArrayEquals(data, destination.array())
        assertEquals(data.size, source.position())
        assertEquals(data.size, destination.position())
        assertEquals(listOf(0x2a, 0x28), usb.ioCommands.map { it.opcode })
        assertTrue(usb.ioCommands.all { it.lba == 123L && it.count == 3L })
        assertTrue(usb.commands.all { it.lun == TEST_LUN.toInt() })
    }

    @Test
    fun `more than 65535 blocks and more than one MiB split into bounded contiguous commands`() {
        // Tiny logical blocks isolate the 16-bit count limit without a 32 MiB allocation.
        for ((blockSize, count) in listOf(8 to 65_537, 512 to 4097)) {
            val usb = FakeUsbCommunication(100_000, blockSize)
            val device = initialized(usb)
            val data = pattern(count * blockSize, 61)
            device.write(11, ByteBuffer.wrap(data))
            val destination = ByteBuffer.allocate(data.size)
            device.read(11, destination)

            assertArrayEquals(data, destination.array())
            for (opcode in listOf(0x2a, 0x28)) {
                val commands = usb.ioCommands.filter { it.opcode == opcode }
                assertTrue(commands.size > 1)
                var nextLba = 11L
                for (command in commands) {
                    assertEquals(nextLba, command.lba)
                    assertTrue(command.count in 1L..65_535L)
                    assertTrue(command.transferBytes in 1..1024 * 1024)
                    nextLba += command.count
                }
                assertEquals(11L + count, nextLba)
            }
        }
    }

    @Test
    fun `position limit and slice offset survive partial USB reads and writes`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)
        usb.maxPacketBytes = 127
        val data = pattern(1024, 83)
        val sourceBacking = ByteArray(1200) { 0x55 }
        data.copyInto(sourceBacking, 53)
        val sourceParent = ByteBuffer.wrap(sourceBacking).apply { position(40); limit(1100) }
        val source = sourceParent.slice().apply { position(13); limit(1037) }
        val destinationBacking = ByteArray(1200) { 0x66 }
        val destinationParent = ByteBuffer.wrap(destinationBacking).apply { position(40); limit(1100) }
        val destination = destinationParent.slice().apply { position(13); limit(1037) }

        device.write(0x100000020L, source)
        device.read(0x100000020L, destination)

        assertEquals(1037, source.position())
        assertEquals(1037, source.limit())
        assertEquals(1037, destination.position())
        assertEquals(1037, destination.limit())
        assertEquals(40, sourceParent.position())
        assertEquals(40, destinationParent.position())
        val expected = ByteArray(1200) { 0x66 }.also { data.copyInto(it, 53) }
        assertArrayEquals(expected, destinationBacking)
        val expectedSource = ByteArray(1200) { 0x55 }.also { data.copyInto(it, 53) }
        assertArrayEquals(expectedSource, sourceBacking)
    }

    @Test
    fun `direct buffers with positions transfer through the array based USB transport`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)
        val data = pattern(1024 * 1024 + 512, 109)
        val source = ByteBuffer.allocateDirect(data.size + 31).apply {
            position(13)
            put(data)
            limit(position())
            position(13)
        }
        val destination = ByteBuffer.allocateDirect(data.size + 31).apply {
            put(ByteArray(capacity()) { 0x77 })
            position(13)
            limit(13 + data.size)
        }

        device.write(0x100000020L, source)
        device.read(0x100000020L, destination)

        assertEquals(13 + data.size, source.position())
        assertEquals(13 + data.size, source.limit())
        assertEquals(13 + data.size, destination.position())
        assertEquals(13 + data.size, destination.limit())
        val actual = ByteArray(destination.capacity())
        destination.clear()
        destination.get(actual)
        val expected = ByteArray(actual.size) { 0x77 }.also { data.copyInto(it, 13) }
        assertArrayEquals(expected, actual)
        assertTrue(usb.ioCommands.count { it.isRead } > 1)
        assertTrue(usb.ioCommands.count { it.isWrite } > 1)
    }

    @Test
    fun `malformed capacity and byte capacity overflow fail before block IO`() {
        val capacities = listOf(
            1023L to 0,
            1023L to Int.MIN_VALUE,
            (THREE_TIB_BLOCKS - 1) to 0,
            (THREE_TIB_BLOCKS - 1) to -1,
            -1L to 512, // Unsigned 64-bit last LBA cannot fit a signed Long.
            Long.MAX_VALUE to 512, // Adding one would overflow.
            (Long.MAX_VALUE / 512) to 512, // Count fits; byte capacity does not.
        )
        for ((lastLba, blockSize) in capacities) {
            val usb = FakeUsbCommunication(lastLba, blockSize)
            val device = VendroidScsiBlockDevice(usb, TEST_LUN)
            assertThrows<IOException> { device.init() }
            val commandsAfterInit = usb.commands.size
            assertThrows<IllegalStateException> { device.read(0, ByteBuffer.allocate(512)) }
            assertThrows<IllegalStateException> { device.write(0, ByteBuffer.allocate(512)) }
            assertEquals(commandsAfterInit, usb.commands.size)
            assertTrue(usb.ioCommands.isEmpty())
        }
    }

    @Test
    fun `invalid ranges and unaligned buffers are rejected before any USB command`() {
        val usb = FakeUsbCommunication(THREE_TIB_BLOCKS - 1)
        val device = initialized(usb)
        val commandsAfterInit = usb.commands.size
        for ((lba, bytes) in listOf(-1L to 512, device.blocks to 512,
            (device.blocks - 1) to 1024, Long.MAX_VALUE to 512, 0L to 513)) {
            val buffer = ByteBuffer.allocate(bytes + 7).apply { position(7) }
            assertThrows<IllegalArgumentException> { device.read(lba, buffer) }
            assertThrows<IllegalArgumentException> { device.write(lba, buffer) }
            assertEquals(7, buffer.position())
            assertEquals(bytes + 7, buffer.limit())
        }
        device.read(device.blocks, ByteBuffer.allocate(0))
        device.write(device.blocks, ByteBuffer.allocate(0))
        assertEquals(commandsAfterInit, usb.commands.size)
    }

    @Test
    fun `persistent zero progress after a partial packet fails read and write without hanging`() {
        for (write in listOf(false, true)) {
            val usb = FakeUsbCommunication(4095)
            val device = initialized(usb)
            usb.maxPacketBytes = 127
            usb.fault = Fault.ZERO_PROGRESS
            val buffer = ByteBuffer.allocate(540).apply { position(9); limit(521) }

            assertThrows<IOException> {
                if (write) device.write(17, buffer) else device.read(17, buffer)
            }

            assertEquals(9, buffer.position())
            assertEquals(521, buffer.limit())
            assertTrue(usb.resetCount > 0)
            assertTrue(usb.ioCommands.size in 1..16)
            assertTrue(usb.ioCommands.all { it.lba == 17L && it.count == 1L })
        }
    }

    @Test
    fun `bad CSW signature tag residue and phase error never report success`() {
        for (fault in listOf(Fault.BAD_SIGNATURE, Fault.BAD_TAG, Fault.RESIDUE, Fault.PHASE_ERROR)) {
            val usb = FakeUsbCommunication(4095)
            val device = initialized(usb)
            usb.fault = fault
            val destination = ByteBuffer.allocate(530).apply { position(5); limit(517) }

            assertThrows<IOException> { device.read(17, destination) }

            assertEquals(5, destination.position())
            assertEquals(517, destination.limit())
            assertTrue(usb.resetCount > 0)
            assertTrue(usb.ioCommands.size in 1..16)
        }
    }

    @Test
    fun `failed command status requests sense and propagates a medium error`() {
        val usb = FakeUsbCommunication(4095)
        val device = initialized(usb)
        usb.fault = Fault.COMMAND_FAILED
        val destination = ByteBuffer.allocate(512)

        assertThrows<IOException> { device.read(17, destination) }

        assertEquals(listOf(0x28, 0x03), usb.commands.takeLast(2).map { it.opcode })
        assertEquals(0, destination.position())
        assertEquals(1, usb.ioCommands.size)
    }

    @Test
    fun `unsigned sense additional length is capped to the 18 byte allocation without a reset`() {
        val usb = FakeUsbCommunication(4095)
        val device = initialized(usb)
        usb.fault = Fault.COMMAND_FAILED
        usb.senseAdditionalLength = 0xf0.toByte()
        usb.maxPacketBytes = 8 // Read the length field before receiving the rest of the response.
        val destination = ByteBuffer.allocate(512)

        val error = assertThrows<MediumError> { device.read(17, destination) }

        assertEquals(3, error.senseKey.toInt())
        assertEquals(0x11, error.additionalSenseCode.toInt())
        assertEquals(listOf(0x28, 0x03), usb.commands.takeLast(2).map { it.opcode })
        assertEquals(18, usb.commands.last().transferBytes)
        assertEquals(1, usb.ioCommands.size)
        assertEquals(0, destination.position())
        assertEquals(0, usb.resetCount)
    }

    @Test
    fun `unit attention retries initialization but aborts an ordinary write without replay`() {
        val usb = FakeUsbCommunication(4095).apply {
            fault = Fault.COMMAND_FAILED
            faultOpcode = 0x12
            faultsRemaining = 1
            senseKey = 6
            senseAsc = 0x29 // Power on or reset occurred.
        }
        val device = initialized(usb)

        assertEquals(listOf(0x12, 0x03, 0x12, 0x00, 0x25), usb.commands.map { it.opcode })
        assertEquals(4096L, device.blocks)
        assertEquals(512, device.blockSize)
        assertEquals(0, usb.resetCount)

        usb.faultOpcode = 0x2a
        usb.faultsRemaining = 1 // A replay would succeed, so assert that the first failure escapes.
        val commandsBeforeWrite = usb.commands.size
        val source = ByteBuffer.wrap(pattern(1024 * 1024 + 512, 131))

        val error = assertThrows<UnitAttention> { device.write(17, source) }

        assertEquals(6, error.senseKey.toInt())
        assertEquals(0x29, error.additionalSenseCode.toInt())
        assertEquals(listOf(0x2a, 0x03), usb.commands.drop(commandsBeforeWrite).map { it.opcode })
        assertEquals(1, usb.ioCommands.size)
        assertEquals(0, source.position())
        assertEquals(0, usb.resetCount)
    }

    @Test
    fun `retry after a bad CSW resets the transaction and replays into the original buffer range`() {
        val usb = FakeUsbCommunication(4095)
        val device = initialized(usb)
        val data = pattern(512, 101)
        usb.seed(17, data)
        usb.fault = Fault.BAD_TAG
        usb.faultsRemaining = 1
        val destination = ByteBuffer.wrap(ByteArray(540) { 0x44 }).apply { position(9); limit(521) }

        device.read(17, destination)

        val expected = ByteArray(540) { 0x44 }.also { data.copyInto(it, 9) }
        assertArrayEquals(expected, destination.array())
        assertEquals(521, destination.position())
        assertEquals(521, destination.limit())
        assertEquals(2, usb.ioCommands.size)
        assertTrue(usb.resetCount > 0)
    }

    private fun initialized(usb: FakeUsbCommunication) =
        VendroidScsiBlockDevice(usb, TEST_LUN).apply { init() }

    private fun pattern(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + it / 251 + seed) % 256).toByte() }

    private enum class Fault { ZERO_PROGRESS, BAD_SIGNATURE, BAD_TAG, RESIDUE, PHASE_ERROR, COMMAND_FAILED }

    private data class WireCommand(
        val tag: Int,
        val opcode: Int,
        val lun: Int,
        val transferBytes: Int,
        val lba: Long = 0,
        val count: Long = 0,
    ) {
        val isRead: Boolean get() = opcode == 0x28 || opcode == 0x88
        val isWrite: Boolean get() = opcode == 0x2a || opcode == 0x8a
        val isBlockIo: Boolean get() = isRead || isWrite
    }

    /** A sparse BOT target. All addressing comes from the CBW's serialized CDB. */
    private class FakeUsbCommunication(
        private val lastLba: Long,
        private val logicalBlockSize: Int = 512,
    ) : UsbCommunication {
        override val inEndpoint: UsbEndpoint = Mockito.mock(UsbEndpoint::class.java)
        override val outEndpoint: UsbEndpoint = Mockito.mock(UsbEndpoint::class.java)
        override val usbInterface: UsbInterface = Mockito.mock(UsbInterface::class.java)
        val commands = mutableListOf<WireCommand>()
        val ioCommands: List<WireCommand> get() = commands.filter { it.isBlockIo }
        var maxPacketBytes = Int.MAX_VALUE
        var fault: Fault? = null
        var faultOpcode: Int? = null
        var faultsRemaining = Int.MAX_VALUE
        var senseAdditionalLength: Byte = 10
        var senseKey: Byte = 3
        var senseAsc: Byte = 0x11
        var resetCount = 0
            private set
        private val storage = mutableMapOf<Long, ByteArray>()
        private var pending: WireCommand? = null
        private var transferred = 0
        private var stalledCalls = 0

        fun seed(lba: Long, data: ByteArray) {
            assertEquals(logicalBlockSize, data.size)
            storage[lba] = data.copyOf()
        }

        fun block(lba: Long): ByteArray = storage[lba]?.copyOf() ?: ByteArray(logicalBlockSize)

        override fun bulkOutTransfer(src: ByteBuffer): Int {
            val command = pending
            if (command == null) {
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                val decoded = decode(bytes)
                commands += decoded
                // A broken retry loop should fail CI promptly, including zero-progress regressions.
                assertTrue(commands.size < 1000, "Unbounded command retry loop")
                pending = decoded
                transferred = 0
                return bytes.size
            }
            assertTrue(command.isWrite, "Unexpected OUT data for opcode ${command.opcode}")
            if (stalls(command)) return 0
            return transferBlocks(command, src, write = true)
        }

        override fun bulkInTransfer(dest: ByteBuffer): Int {
            val command = checkNotNull(pending) { "IN transfer without a CBW" }
            if (transferred < command.transferBytes) {
                assertTrue(!command.isWrite, "CSW requested before all OUT data")
                if (stalls(command)) return 0
                if (command.isRead) return transferBlocks(command, dest, write = false)
                val response = response(command)
                val length = minOf(dest.remaining(), response.size - transferred, maxPacketBytes)
                dest.put(response, transferred, length)
                transferred += length
                return length
            }
            val matchesFault = faultOpcode?.let { command.opcode == it } ?: command.isBlockIo
            val injected = if (matchesFault && faultsRemaining > 0) fault else null
            if (injected != null) faultsRemaining--
            val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(if (injected == Fault.BAD_SIGNATURE) 0 else 0x53425355)
                .putInt(if (injected == Fault.BAD_TAG) command.tag + 1 else command.tag)
                .putInt(if (injected == Fault.RESIDUE) 1 else 0)
                .put(when (injected) {
                    Fault.COMMAND_FAILED -> 1.toByte()
                    Fault.PHASE_ERROR -> 2.toByte()
                    else -> 0.toByte()
                }).array()
            assertEquals(13, dest.remaining())
            dest.put(csw)
            pending = null
            return csw.size
        }

        private fun stalls(command: WireCommand): Boolean {
            if (command.isBlockIo && fault == Fault.ZERO_PROGRESS && transferred > 0 && faultsRemaining > 0) {
                assertTrue(++stalledCalls <= 16, "Driver kept retrying a zero-byte USB transfer")
                faultsRemaining--
                return true
            }
            return false
        }

        private fun transferBlocks(command: WireCommand, buffer: ByteBuffer, write: Boolean): Int {
            val length = minOf(buffer.remaining(), command.transferBytes - transferred, maxPacketBytes)
            assertTrue(length > 0)
            var done = 0
            while (done < length) {
                val offset = transferred + done
                val lba = command.lba + offset / logicalBlockSize
                val withinBlock = offset % logicalBlockSize
                val bytes = minOf(length - done, logicalBlockSize - withinBlock)
                if (write) {
                    val block = storage.getOrPut(lba) { ByteArray(logicalBlockSize) }
                    // libusb 0.3 addresses array() at position(), ignoring arrayOffset().
                    // Using ByteBuffer.get/put here would hide sliced-buffer corruption.
                    buffer.array().copyInto(block, withinBlock, buffer.position(), buffer.position() + bytes)
                } else {
                    val block = storage[lba] ?: ByteArray(logicalBlockSize)
                    block.copyInto(buffer.array(), buffer.position(), withinBlock, withinBlock + bytes)
                }
                buffer.position(buffer.position() + bytes)
                done += bytes
            }
            transferred += length
            return length
        }

        private fun decode(bytes: ByteArray): WireCommand {
            assertEquals(31, bytes.size, "CBW size")
            val cbw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x43425355, cbw.int, "CBW signature")
            val tag = cbw.int
            val transferBytes = cbw.int
            val flags = cbw.get().toInt() and 0xff
            val lun = cbw.get().toInt() and 0xff
            val cdbLength = cbw.get().toInt() and 0xff
            val cdb = ByteBuffer.wrap(bytes, 15, 16).slice().order(ByteOrder.BIG_ENDIAN)
            val opcode = cdb.get(0).toInt() and 0xff
            val expectedLength = when (opcode) {
                0x00, 0x03, 0x12 -> 6
                0x25, 0x28, 0x2a -> 10
                0x88, 0x8a, 0x9e -> 16
                else -> throw AssertionError("Unexpected SCSI opcode $opcode")
            }
            assertEquals(expectedLength, cdbLength, "CDB length")
            assertEquals(TEST_LUN.toInt(), lun, "CBW LUN")
            assertTrue(transferBytes >= 0)
            val isRead = opcode in listOf(0x03, 0x12, 0x25, 0x28, 0x88, 0x9e)
            assertEquals(if (isRead) 0x80 else 0, flags, "CBW direction")
            val lba: Long
            val count: Long
            when (opcode) {
                0x28, 0x2a -> {
                    lba = cdb.getInt(2).toLong() and 0xffffffffL
                    count = (cdb.getShort(7).toInt() and 0xffff).toLong()
                }
                0x88, 0x8a -> {
                    lba = cdb.getLong(2)
                    count = cdb.getInt(10).toLong() and 0xffffffffL
                }
                else -> {
                    lba = 0
                    count = 0
                    val expectedBytes = when (opcode) {
                        0x00 -> 0
                        0x03 -> 18
                        0x12 -> 36
                        0x25 -> 8
                        0x9e -> 32
                        else -> throw AssertionError("Unexpected opcode $opcode")
                    }
                    assertEquals(expectedBytes, transferBytes)
                    if (opcode == 0x03) {
                        assertEquals(18, cdb.get(4).toInt() and 0xff, "REQUEST SENSE allocation length")
                    }
                    if (opcode == 0x9e) {
                        assertEquals(0x10, cdb.get(1).toInt() and 0xff, "READ CAPACITY16 service action")
                        assertEquals(0L, cdb.getLong(2))
                        assertEquals(32, cdb.getInt(10), "READ CAPACITY16 allocation length")
                    }
                }
            }
            if (count != 0L || opcode in listOf(0x28, 0x2a, 0x88, 0x8a)) {
                assertTrue(count > 0, "A wrapped transfer count must not be accepted")
                assertEquals(count * logicalBlockSize, transferBytes.toLong(), "CDB and CBW byte counts")
                assertTrue(lba >= 0 && lba <= lastLba && count - 1 <= lastLba - lba)
            }
            return WireCommand(tag, opcode, lun, transferBytes, lba, count)
        }

        private fun response(command: WireCommand): ByteArray = when (command.opcode) {
            0x12 -> ByteArray(36).apply {
                this[2] = 6 // SPC version; direct-access peripheral in byte zero.
                this[3] = 2
                this[4] = 31
            }
            0x25 -> ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(if (lastLba < 0 || lastLba >= 0xffffffffL) -1 else lastLba.toInt())
                .putInt(logicalBlockSize).array()
            0x9e -> ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                .putLong(lastLba).putInt(logicalBlockSize).array()
            0x03 -> ByteArray(18).apply {
                this[0] = 0x70
                this[2] = senseKey
                this[7] = senseAdditionalLength
                this[12] = senseAsc
            }
            else -> throw AssertionError("No response for opcode ${command.opcode}")
        }

        override fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int,
        ): Int {
            assertEquals(0x21, requestType)
            assertEquals(0xff, request)
            assertEquals(0, value)
            assertEquals(usbInterface.id, index)
            assertEquals(0, length)
            resetCount++
            pending = null
            transferred = 0
            return 0
        }

        override fun clearFeatureHalt(endpoint: UsbEndpoint) {
            assertTrue(endpoint === inEndpoint || endpoint === outEndpoint)
        }

        override fun resetDevice() = throw AssertionError("Unexpected USB device reset")
        override fun close() = Unit
    }

    companion object {
        private const val TEST_LUN: Byte = 3
        private const val THREE_TIB_BLOCKS = 6_442_450_944L
    }
}
