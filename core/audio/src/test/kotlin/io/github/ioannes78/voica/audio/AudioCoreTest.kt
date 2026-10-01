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
                assertEquals(5L, source.totalSampleCount)
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
    fun pcm16WavNormalizationProducesCanonicalOutput() {
        val root = Files.createTempDirectory("voica-wav-normalize").toFile()
        try {
            val source = root.resolve("source-48k-stereo.wav")
            val output = root.resolve("canonical.wav")
            val frames = 48_000
            val interleaved = ShortArray(frames * 2) { index ->
                if (index % 2 == 0) 1_000 else 3_000
            }
            writePcm16Wav(
                file = source,
                sampleRateHz = 48_000,
                channels = 2,
                interleaved = interleaved,
            )

            val stages = mutableListOf<CanonicalAudioStage>()
            val result = PcmWavToCanonicalWavConverter().convert(
                sourceFile = source,
                targetFile = output,
                onStage = stages::add,
            )

            val parsed = WavPcmParser.parse(output)
            assertTrue(parsed is WavParseResult.Valid)
            val info = (parsed as WavParseResult.Valid).info
            assertTrue(info.isCanonical)
            assertEquals(16_000L, info.frameCount)
            assertEquals(1_000_000L, result.wav.durationUs)
            assertEquals(
                listOf(
                    CanonicalAudioStage.DECODING,
                    CanonicalAudioStage.NORMALIZING,
                    CanonicalAudioStage.WRITING,
                    CanonicalAudioStage.VERIFYING,
                ),
                stages,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sampleClockUsesIntegerTimeline() {
        assertEquals(1_000_000L, sampleIndexToTimeUs(16_000L, 16_000))
        assertEquals(500_000L, sampleIndexToTimeUs(8_000L, 16_000))
    }

    private fun writePcm16Wav(
        file: java.io.File,
        sampleRateHz: Int,
        channels: Int,
        interleaved: ShortArray,
    ) {
        require(interleaved.size % channels == 0)
        val dataBytes = interleaved.size * 2
        java.io.RandomAccessFile(file, "rw").use { raf ->
            fun writeU16(value: Int) {
                raf.write(value and 0xFF)
                raf.write((value ushr 8) and 0xFF)
            }
            fun writeU32(value: Long) {
                repeat(4) { shift ->
                    raf.write(((value ushr (shift * 8)) and 0xFF).toInt())
                }
            }

            raf.write("RIFF".encodeToByteArray())
            writeU32(36L + dataBytes)
            raf.write("WAVE".encodeToByteArray())
            raf.write("fmt ".encodeToByteArray())
            writeU32(16L)
            writeU16(1)
            writeU16(channels)
            writeU32(sampleRateHz.toLong())
            writeU32(sampleRateHz.toLong() * channels * 2L)
            writeU16(channels * 2)
            writeU16(16)
            raf.write("data".encodeToByteArray())
            writeU32(dataBytes.toLong())
            interleaved.forEach { sample ->
                val value = sample.toInt()
                raf.write(value and 0xFF)
                raf.write((value ushr 8) and 0xFF)
            }
        }
    }
}
