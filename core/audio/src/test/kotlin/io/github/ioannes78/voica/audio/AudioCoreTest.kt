package io.github.ioannes78.voica.audio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCoreTest {
    @Test
    fun detectorDistinguishesWaveOggOpusAndRawCandidate() {
        assertEquals(
            AudioContainerKind.WAV,
            AudioContainerDetector.detect("RIFF1234WAVEfmt ".encodeToByteArray()),
        )
        assertEquals(
            AudioContainerKind.OGG_OPUS,
            AudioContainerDetector.detect(
                "OggS".encodeToByteArray() + ByteArray(16) + "OpusHead".encodeToByteArray(),
            ),
        )
        assertEquals(
            AudioContainerKind.DEVICE_RAW_CANDIDATE,
            AudioContainerDetector.detect(byteArrayOf(1, 2, 3, 4)),
        )
    }

    @Test
    fun canonicalWriterProducesValid16kMonoPcm16Wave() {
        val root = Files.createTempDirectory("voica-wav").toFile()
        try {
            val file = root.resolve("canonical.wav")
            val samples = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
            val commit = CanonicalWavWriter(file).use { writer ->
                writer.writePcm16(samples)
                writer.commit()
            }

            val parsed = WavPcmParser.parse(file)
            assertTrue(parsed is WavParseResult.Valid)
            val info = (parsed as WavParseResult.Valid).info
            assertTrue(info.isCanonical)
            assertEquals(samples.size.toLong(), info.frameCount)
            assertEquals(44L + samples.size * 2L, commit.sizeBytes)
            assertEquals(64, commit.sha256.length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun wavParserRejectsTruncatedRiff() {
        val root = Files.createTempDirectory("voica-wav-bad").toFile()
        try {
            val file = root.resolve("bad.wav")
            file.writeBytes("RIFF".encodeToByteArray())
            val result = WavPcmParser.parse(file)
            assertTrue(result is WavParseResult.Invalid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sampleClockUsesIntegerTimeline() {
        assertEquals(1_000_000L, sampleIndexToTimeUs(16_000L, 16_000))
        assertEquals(500_000L, sampleIndexToTimeUs(8_000L, 16_000))
    }
}
