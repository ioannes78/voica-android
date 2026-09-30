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
    fun stereoDownmixAnd48kTo16kRemainBoundedAndDeterministic() {
        val normalizer = StreamingPcm16Normalizer(
            sourceSampleRateHz = 48_000,
            sourceChannelCount = 2,
        )
        val interleaved = ShortArray(48 * 2) { index ->
            if (index % 2 == 0) 1_000 else 3_000
        }

        val output = normalizer.processInterleaved(interleaved)

        assertEquals(16, output.size)
        assertTrue(output.all { it.toInt() == 2_000 })
        assertEquals(16L, normalizer.outputSampleCount)
    }

    @Test
    fun canonicalPcmSourceReturnsAbsoluteSampleIndexes() {
        val root = Files.createTempDirectory("voica-pcm-source").toFile()
        try {
            val file = root.resolve("source.wav")
            CanonicalWavWriter(file).use { writer ->
                writer.writePcm16(shortArrayOf(1, 2, 3, 4, 5))
                writer.commit()
            }

            CanonicalWavPcmSource(file).use { source ->
                val first = ShortArray(3)
                val firstRead = kotlinx.coroutines.runBlocking { source.read(first) }!!
                assertEquals(0L, firstRead.startSampleIndex)
                assertEquals(3, firstRead.sampleCount)
                assertTrue(first.contentEquals(shortArrayOf(1, 2, 3)))

                val second = ShortArray(3)
                val secondRead = kotlinx.coroutines.runBlocking { source.read(second) }!!
                assertEquals(3L, secondRead.startSampleIndex)
                assertEquals(2, secondRead.sampleCount)
                assertEquals(4, second[0].toInt())
                assertEquals(5, second[1].toInt())
            }
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
