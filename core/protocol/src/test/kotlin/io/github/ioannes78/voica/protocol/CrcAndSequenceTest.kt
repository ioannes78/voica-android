package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CrcAndSequenceTest {
    @Test
    fun crc16XmodemStandardVector() {
        assertEquals(
            0x31C3,
            Crc16Xmodem.compute("123456789".encodeToByteArray()),
        )
    }

    @Test
    fun sequenceWrapsFrom255To0() {
        val generator = SequenceGenerator()
        assertEquals(0, generator.next())
        repeat(254) { generator.next() }
        assertEquals(255, generator.next())
        assertEquals(0, generator.next())
    }
}
