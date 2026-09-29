package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDecoderTest {
    @Test
    fun decodesKnownAndUnknownRecordingStates() {
        assertEquals(
            ProtocolDecodeResult.Success(RecordingStatus.Recording),
            RecordingDecoders.decodeStatus(byteArrayOf(1)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingStatus.Idle),
            RecordingDecoders.decodeStatus(byteArrayOf(2)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingStatus.Paused),
            RecordingDecoders.decodeStatus(byteArrayOf(3)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingStatus.UnknownRaw(9)),
            RecordingDecoders.decodeStatus(byteArrayOf(9)),
        )
        assertTrue(RecordingDecoders.decodeStatus(byteArrayOf()) is ProtocolDecodeResult.Malformed)
    }

    @Test
    fun decodesTimeAsU16LeAndU32LeAndRejectsTruncation() {
        assertEquals(
            ProtocolDecodeResult.Success(
                RecordingTimeInfo(
                    durationSeconds = 0x1234,
                    currentSizeBytes = 0x7856_3412L,
                ),
            ),
            RecordingDecoders.decodeTime(
                byteArrayOf(
                    0x34,
                    0x12,
                    0x12,
                    0x34,
                    0x56,
                    0x78,
                ),
            ),
        )

        for (size in 0..5) {
            assertTrue(
                RecordingDecoders.decodeTime(ByteArray(size)) is ProtocolDecodeResult.Malformed,
            )
        }
    }

    @Test
    fun legacyRecordTimeDecoderNoLongerInventsZeroForMalformedPayload() {
        try {
            DeviceDecoders.decodeRecordTime(ByteArray(5))
            throw AssertionError("短包必须失败")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun filenameSupportsUtf8NulAndEmptyBody() {
        assertEquals(
            ProtocolDecodeResult.Success("会议01.opus"),
            RecordingDecoders.decodeFilename("会议01.opus\u0000ignored".toByteArray()),
        )
        assertEquals(
            ProtocolDecodeResult.Success(""),
            RecordingDecoders.decodeFilename(byteArrayOf()),
        )
    }

    @Test
    fun gainPreservesUnknownRawValues() {
        assertEquals(
            ProtocolDecodeResult.Success(RecordingGain.Low),
            RecordingDecoders.decodeGain(byteArrayOf(1)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingGain.Medium),
            RecordingDecoders.decodeGain(byteArrayOf(2)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingGain.High),
            RecordingDecoders.decodeGain(byteArrayOf(3)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingGain.UnknownRaw(8)),
            RecordingDecoders.decodeGain(byteArrayOf(8)),
        )
        assertTrue(RecordingDecoders.decodeGain(byteArrayOf()) is ProtocolDecodeResult.Malformed)
    }

    @Test
    fun commandResultRetainsRawCode() {
        assertEquals(
            ProtocolDecodeResult.Success(RecordingCommandResult(0)),
            RecordingDecoders.decodeCommandResult(byteArrayOf(0)),
        )
        assertEquals(
            ProtocolDecodeResult.Success(RecordingCommandResult(1)),
            RecordingDecoders.decodeCommandResult(byteArrayOf(1)),
        )
        assertTrue(
            RecordingDecoders.decodeCommandResult(byteArrayOf()) is ProtocolDecodeResult.Malformed,
        )
    }
}
