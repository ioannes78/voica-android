package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeProtocolTest {
    @Test
    fun realtimeCommandBuildersMatchGoldenFrames() {
        val sequence = 0x2A
        val cases = listOf(
            RealtimeProtocol.buildStart(sequence) to "5A 2A 59 DE 02 00 01 00",
            RealtimeProtocol.buildStop(sequence) to "5A 2A 1B FE 02 00 01 02",
            RealtimeProtocol.buildPause(sequence) to "5A 2A 90 9C 03 00 01 03 01",
            RealtimeProtocol.buildResume(sequence) to "5A 2A B1 8C 03 00 01 03 00",
        )

        cases.forEach { (actual, expected) ->
            assertArrayEquals(hex(expected), actual)
        }
    }

    @Test
    fun realtimeCommandsUseTypeOneAndExpectedPayloads() {
        val parser = FrameParser("test")
        val frames = listOf(
            parser.feed(RealtimeProtocol.buildStart(sequence = 1)).single(),
            parser.feed(RealtimeProtocol.buildStop(sequence = 2)).single(),
            parser.feed(RealtimeProtocol.buildPause(sequence = 3)).single(),
            parser.feed(RealtimeProtocol.buildResume(sequence = 4)).single(),
        )

        frames.forEach { frame ->
            assertEquals(ProtocolConstants.Type.REALTIME, frame.type)
        }
        assertEquals(ProtocolConstants.Realtime.START, frames[0].command)
        assertEquals(ProtocolConstants.Realtime.STOP, frames[1].command)
        assertEquals(ProtocolConstants.Realtime.PAUSE_RESUME, frames[2].command)
        assertEquals(ProtocolConstants.Realtime.PAUSE_RESUME, frames[3].command)
        assertArrayEquals(byteArrayOf(RealtimeProtocol.PAUSE_VALUE.toByte()), frames[2].body)
        assertArrayEquals(byteArrayOf(RealtimeProtocol.RESUME_VALUE.toByte()), frames[3].body)
    }

    private fun hex(value: String): ByteArray =
        value.trim()
            .split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
