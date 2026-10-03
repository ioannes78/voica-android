package io.github.ioannes78.voica.audio

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedAudioNormalizationTest {
    @Test
    fun decoderPcmIsDownmixedResampledAndCommittedAsCanonicalWav() = runBlocking {
        val root = Files.createTempDirectory("voica-compressed-canonical").toFile()
        try {
            val source = File(root, "source.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val target = File(root, "canonical.wav")
            val frames = 48
            val stereo = ShortArray(frames * 2) { index ->
                if (index % 2 == 0) 1_000 else 3_000
            }
            val decoder =
                object : CompressedAudioDecoder {
                    override suspend fun decode(
                        file: File,
                        isCancelled: () -> Boolean,
                        sink: Pcm16DecodeSink,
                    ): CompressedAudioDecodeResult {
                        val format = DecodedPcmFormat(48_000, 2)
                        sink.onFormat(format)
                        sink.onSamples(stereo, frames)
                        return CompressedAudioDecodeResult(
                            input =
                                CompressedAudioDescriptor(
                                    mimeType = "audio/mpeg",
                                    sampleRateHz = 48_000,
                                    channelCount = 2,
                                    durationUs = 1_000,
                                    decoderName = "fake",
                                ),
                            output = format,
                            decodedFrameCount = frames.toLong(),
                        )
                    }
                }

            val result =
                CompressedAudioToCanonicalWavConverter(decoder)
                    .convert(
                        sourceFile = source,
                        targetFile = target,
                    )

            assertEquals(16L, result.wav.pcmSampleCount)
            val parsed = WavPcmParser.parse(target)
            assertTrue(parsed is WavParseResult.Valid)
            assertTrue((parsed as WavParseResult.Valid).info.isCanonical)

            val pcm = CanonicalWavPcmSource(target).use { pcmSource ->
                val values = ShortArray(32)
                val read = runBlocking { pcmSource.read(values) }
                values.copyOf(read?.sampleCount ?: 0)
            }
            assertEquals(16, pcm.size)
            assertTrue(pcm.all { it.toInt() == 2_000 })
        } finally {
            root.deleteRecursively()
        }
    }
}
