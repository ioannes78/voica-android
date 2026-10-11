package io.github.ioannes78.voica.audio

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedAudioNormalizationTest {
    @Test
    fun decoderPcmIsDownmixedResampledAndCommittedAsCanonicalWav() = runBlocking {
        val root = Files.createTempDirectory("voica-compressed-canonical").toFile()
        try {
            val source =
                File(root, "source.bin").apply {
                    writeBytes(byteArrayOf(1, 2, 3, 4))
                }
            val target = File(root, "canonical.wav")
            val frames = 48
            val stereo =
                ShortArray(frames * 2) { index ->
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
                        val firstFrames = 17
                        sink.onSamples(
                            stereo.copyOfRange(0, firstFrames * format.channelCount),
                            firstFrames,
                        )
                        val remainingFrames = frames - firstFrames
                        sink.onSamples(
                            stereo.copyOfRange(firstFrames * format.channelCount, stereo.size),
                            remainingFrames,
                        )
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

            val pcm =
                CanonicalWavPcmSource(target).use { pcmSource ->
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

    @Test
    fun progressUsesPresentationTimelineAndOnlyFinalizesAfterDecoderEos() = runBlocking {
        val root = Files.createTempDirectory("voica-compressed-progress").toFile()
        try {
            val source =
                File(root, "source.bin").apply {
                    writeBytes(byteArrayOf(5, 6, 7, 8))
                }
            val target = File(root, "canonical.wav")
            val format = DecodedPcmFormat(48_000, 1)
            val blockFrames = 4_800
            val block = ShortArray(blockFrames) { 1_000 }
            val progress = mutableListOf<CanonicalAudioWorkProgress>()
            val decoder =
                object : CompressedAudioDecoder {
                    override suspend fun decode(
                        file: File,
                        isCancelled: () -> Boolean,
                        sink: Pcm16DecodeSink,
                    ): CompressedAudioDecodeResult {
                        sink.onFormat(format)
                        sink.onSamplesAt(
                            samples = block,
                            frameCount = blockFrames,
                            presentationTimeUs = 9_800_000L,
                        )
                        sink.onSamplesAt(
                            samples = block,
                            frameCount = blockFrames,
                            presentationTimeUs = 10_200_000L,
                        )
                        return CompressedAudioDecodeResult(
                            input =
                                CompressedAudioDescriptor(
                                    mimeType = "audio/mpeg",
                                    sampleRateHz = 48_000,
                                    channelCount = 1,
                                    durationUs = 10_000_000L,
                                    decoderName = "fake-pts",
                                ),
                            output = format,
                            decodedFrameCount = (blockFrames * 2L),
                            presentationEndTimeUs = 10_300_000L,
                        )
                    }
                }

            val result =
                CompressedAudioToCanonicalWavConverter(decoder)
                    .convert(
                        sourceFile = source,
                        targetFile = target,
                        knownDurationUs = 10_000_000L,
                        onProgress = progress::add,
                    )

            assertTrue(progress.size >= 3)
            assertFalse(progress.dropLast(1).any { it.isFinal })
            val final = progress.last()
            assertTrue(final.isFinal)
            assertEquals(10_300_000L, final.processedDurationUs)
            assertEquals(10_000_000L, final.totalDurationUs)
            assertEquals(10_000_000L, result.timing.metadataDurationUs)
            assertEquals(200_000L, result.timing.decodedFrameDurationUs)
            assertEquals(10_300_000L, result.timing.mediaTimelineDurationUs)
            assertEquals(result.wav.durationUs, result.timing.canonicalDurationUs)
        } finally {
            root.deleteRecursively()
        }
    }
}
