package io.github.xntso.vendroid.massstorage

import io.github.xntso.vendroid.MemoryBufferBlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScsiBlockCountAdapterTest {
    @Test
    fun `converts inclusive last block address to block count`() {
        val driver = MemoryBufferBlockDeviceDriver(16L * 512, 512)
        val scsiDriver = object : BlockDeviceDriver by driver {
            override val blocks = 15L
        }

        val adapted = ScsiBlockCountAdapter(scsiDriver)

        assertEquals(16L, adapted.blocks)
        assertEquals(16L * 512, adapted.blocks * adapted.blockSize)
    }
}
