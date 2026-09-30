package io.github.ioannes78.voica.audio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawOpusPipelineTest {
    @Test
    fun fixed40CandidateIsAcceptedOnlyWhenEveryPacketParses() {
        val root = Files.createTempDirectory("voica-opus-raw").toFile()
        try {
            val file = root.resolve("sample.opus")
            file.writeBytes(validPacket() + validPacket())

            val result = RawOpusValidator(FakeInspector()).validate(file)

            assertTrue(result is RawOpusValidationResult.Valid)
            val validation = (result as RawOpusValidationResult.Valid).value
            assertEquals(RawOpusFraming.FIXED_40_BYTES, validation.framing)
            assertEquals(2L, validation.packetCount)
            assertEquals(1, validation.channelCount)
            assertEquals(1_920L, validation.totalSamples48k)
            assertEquals(40_000L, validation.decodedDurationUs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalid40BytePayloadIsNotAcceptedFromAlignmentAlone() {
        val root = Files.createTempDirectory("voica-opus-invalid").toFile()
        try {
            val file = root.resolve("sample.opus")
            file.writeBytes(ByteArray(80))

            val result = RawOpusValidator(FakeInspector()).validate(file)

            assertTrue(result is RawOpusValidationResult.UnsupportedFraming)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conversionStreamsPacketsIntoCanonicalWav() {
        val root = Files.createTempDirectory("voica-opus-convert").toFile()
        try {
            val source = root.resolve("sample.opus")
            val target = root.resolve("sample.wav")
            source.writeBytes(validPacket() + validPacket())

            val converter = RawOpusToCanonicalWavConverter(
                inspector = FakeInspector(),
                decoderFactory = FakeDecoderFactory(),
            )
            val result = converter.convert(source, target)

            assertEquals("fake-opus", result.decoderVersion)
            assertEquals(640L, result.wav.pcmSampleCount)
            assertEquals(40_000L, result.wav.durationUs)
            assertEquals(44L + 640L * 2L, result.wav.sizeBytes)

            val parsed = WavPcmParser.parse(target)
            assertTrue(parsed is WavParseResult.Valid)
            assertTrue((parsed as WavParseResult.Valid).info.isCanonical)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validPacket(): ByteArray =
        ByteArray(40).also { bytes ->
            bytes[0] = 0x7F
            bytes[1] = 0x11
        }

    private class FakeInspector : OpusPacketInspector {
        override fun inspect(packet: ByteArray): OpusPacketInfo =
            if (packet.size == 40 && packet[0] == 0x7F.toByte()) {
                OpusPacketInfo(
                    valid = true,
                    toc = 0x7F,
                    channels = 1,
                    frameCount = 1,
                    samplesPerFrame48k = 960,
                    totalSamples48k = 960,
                    bandwidth = 1105,
                    payloadOffsetBytes = 1,
                    minFrameBytes = 39,
                    maxFrameBytes = 39,
                )
            } else {
                OpusPacketInfo(valid = false, errorCode = -4)
            }
    }

    private class FakeDecoderFactory : OpusDecoderFactory {
        override val version: String = "fake-opus"

        override fun create(sampleRateHz: Int, channelCount: Int): OpusDecoder {
            assertEquals(16_000, sampleRateHz)
            assertEquals(1, channelCount)
            return object : OpusDecoder {
                override val sampleRateHz: Int = sampleRateHz
                override val channelCount: Int = channelCount

                override fun decode(packet: ByteArray): ShortArray =
                    ShortArray(320) { index -> index.toShort() }

                override fun reset() = Unit

                override fun close() = Unit
            }
        }
    }
}
