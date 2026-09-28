package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParserTest {
    @Test
    fun parsesSplitAndCoalescedNotifications() {
        val frameA = ProtocolCodec.buildGetBattery(1)
        val frameB = ProtocolCodec.buildGetVersion(2)
        val parser = FrameParser("AE22")

        assertTrue(parser.feed(frameA.copyOfRange(0, 3)).isEmpty())

        val frames = parser.feed(frameA.copyOfRange(3, frameA.size) + frameB)

        assertEquals(2, frames.size)
        assertEquals(1, frames[0].sequence)
        assertEquals(2, frames[1].sequence)
    }

    @Test
    fun discardsNoiseBeforeMagic() {
        val frame = ProtocolCodec.buildGetCapacity(4)
        val frames = FrameParser("AE22").feed(
            byteArrayOf(0x00, 0x11, 0x7F) + frame
        )

        assertEquals(1, frames.size)
        assertEquals(4, frames.single().sequence)
    }

    @Test
    fun crcErrorDoesNotPreventFollowingFrame() {
        val broken = ProtocolCodec.buildGetBattery(3).copyOf()
        broken[broken.lastIndex] = (broken.last().toInt() xor 0x01).toByte()
        val valid = ProtocolCodec.buildGetVersion(4)
        val parser = FrameParser("AE22")

        val frames = parser.feed(broken + valid)

        assertEquals(1, parser.crcErrorCount)
        assertEquals(1, frames.size)
        assertEquals(4, frames.single().sequence)
    }

    @Test
    fun invalidLengthResynchronizesAtNextMagic() {
        val invalid = byteArrayOf(
            0x5A,
            0x00,
            0x00,
            0x00,
            0x01,
            0x20,
        )
        val valid = ProtocolCodec.buildGetBattery(5)
        val parser = FrameParser("AE22")

        val frames = parser.feed(invalid + valid)

        assertEquals(1, parser.invalidLengthCount)
        assertEquals(1, frames.size)
        assertEquals(5, frames.single().sequence)
    }

    @Test
    fun ae22AndAe23KeepIndependentBuffers() {
        val parsers = NotificationParsers()
        val ae22Frame = ProtocolCodec.buildGetBattery(1)
        val ae23Frame = ProtocolCodec.buildGetRecordState(2)

        assertTrue(parsers.ae22.feed(ae22Frame.copyOfRange(0, 4)).isEmpty())
        assertEquals(1, parsers.ae23.feed(ae23Frame).size)
        assertEquals(
            1,
            parsers.ae22.feed(ae22Frame.copyOfRange(4, ae22Frame.size)).size,
        )
    }
}
