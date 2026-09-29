package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingProtocolTest {
    @Test
    fun recordingCommandBuildersMatchGoldenFrames() {
        val sequence = 0x2A
        val cases = listOf(
            ProtocolCodec.buildRecordStart(sequence) to "5A 2A 1A A8 02 00 03 01",
            ProtocolCodec.buildRecordSave(sequence) to "5A 2A 58 88 02 00 03 03",
            ProtocolCodec.buildRecordPause(sequence) to "5A 2A 9E E8 02 00 03 05",
            ProtocolCodec.buildRecordResume(sequence) to "5A 2A DC C8 02 00 03 07",
            ProtocolCodec.buildGetRecordState(sequence) to "5A 2A 69 9A 02 00 03 13",
            ProtocolCodec.buildGetRecordTime(sequence) to "5A 2A AF FA 02 00 03 15",
            ProtocolCodec.buildGetRecordFilename(sequence) to "5A 2A ED DA 02 00 03 17",
            ProtocolCodec.buildGetRecordingGain(sequence) to "5A 2A 23 3B 02 00 03 19",
            ProtocolCodec.buildSetRecordingGain(sequence, 1) to "5A 2A 2A 78 03 00 03 1B 01",
            ProtocolCodec.buildSetRecordingGain(sequence, 2) to "5A 2A 49 48 03 00 03 1B 02",
            ProtocolCodec.buildSetRecordingGain(sequence, 3) to "5A 2A 68 58 03 00 03 1B 03",
        )

        cases.forEach { (actual, expected) ->
            assertArrayEquals(hex(expected), actual)
        }
    }

    @Test
    fun setGainRejectsOutOfRangeValues() {
        listOf(0, 4, 255).forEach { invalid ->
            try {
                ProtocolCodec.buildSetRecordingGain(1, invalid)
                throw AssertionError("gain=$invalid 应被拒绝")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun keyCommandsUseTypeThree() {
        val parsed = FrameParser("test")
            .feed(ProtocolCodec.buildGetRecordState(sequence = 7))
            .single()

        assertEquals(ProtocolConstants.Type.KEY, parsed.type)
        assertEquals(ProtocolConstants.Key.GET_STATE, parsed.command)
    }

    private fun hex(value: String): ByteArray =
        value.trim()
            .split(Regex("\\s+"))
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
