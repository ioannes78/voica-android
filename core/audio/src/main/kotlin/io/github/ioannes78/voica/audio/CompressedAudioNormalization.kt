package io.github.ioannes78.voica.audio

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

data class CompressedAudioConversionResult(
    val sourceSha256: String,
    val decoded: CompressedAudioDecodeResult,
    val wav: CanonicalWavCommit,
    val timing: CanonicalConversionTiming,
)

class CompressedAudioToCanonicalWavConverter(
    private val decoder: CompressedAudioDecoder,
) {
    suspend fun convert(
        sourceFile: File,
        targetFile: File,
        expectedSourceSha256: String? = null,
        verifiedSourceSha256: String? = null,
        knownDurationUs: Long? = null,
        isCancelled: () -> Boolean = { false },
        onStage: (CanonicalAudioStage) -> Unit = {},
        onProgress: (CanonicalAudioWorkProgress) -> Unit = {},
    ): CompressedAudioConversionResult {
        val sourceSha = verifiedSourceSha256 ?: sha256(sourceFile)
        if (
            expectedSourceSha256 != null &&
            !sourceSha.equals(expectedSourceSha256, ignoreCase = true)
        ) {
            throw AudioPipelineException(
                code = "RAW_SHA_MISMATCH",
                recoverable = false,
                message = "source SHA-256 changed before media decode",
            )
        }

        val pipelineStarted = System.nanoTime()
        onStage(CanonicalAudioStage.DECODING)
        CanonicalWavWriter(targetFile).use { writer ->
            var sourceFormat: DecodedPcmFormat? = null
            var normalizer: StreamingPcm16Normalizer? = null
            var normalizedBuffer = ShortArray(0)
            var normalizationReported = false
            var writingReported = false
            var processedFrames = 0L
            var processedTimelineUs = 0L
            var normalizeNanos = 0L

            val sink =
                object : Pcm16DecodeSink {
                    override fun onFormat(format: DecodedPcmFormat) {
                        require(format.sampleRateHz > 0) { "invalid decoder sample rate" }
                        require(format.channelCount in 1..8) {
                            "unsupported decoder channels=" + format.channelCount
                        }
                        val previous = sourceFormat
                        if (previous != null && previous != format) {
                            throw AudioPipelineException(
                                code = "MEDIA_FORMAT_CHANGED",
                                recoverable = false,
                                message = "decoded PCM format changed " + previous + " -> " + format,
                            )
                        }
                        if (previous == null) {
                            sourceFormat = format
                            normalizer =
                                StreamingPcm16Normalizer(
                                    sourceSampleRateHz = format.sampleRateHz,
                                    sourceChannelCount = format.channelCount,
                                )
                        }
                    }

                    override fun onSamples(
                        samples: ShortArray,
                        frameCount: Int,
                    ) {
                        consume(samples, frameCount, presentationTimeUs = null)
                    }

                    override fun onSamplesAt(
                        samples: ShortArray,
                        frameCount: Int,
                        presentationTimeUs: Long?,
                    ) {
                        consume(samples, frameCount, presentationTimeUs)
                    }

                    private fun consume(
                        samples: ShortArray,
                        frameCount: Int,
                        presentationTimeUs: Long?,
                    ) {
                        if (isCancelled()) {
                            throw CancellationException("media conversion cancelled")
                        }
                        val format =
                            sourceFormat
                                ?: error("decoder emitted PCM before output format")
                        require(frameCount >= 0)
                        require(samples.size >= frameCount * format.channelCount)

                        if (!normalizationReported) {
                            normalizationReported = true
                            onStage(CanonicalAudioStage.NORMALIZING)
                        }
                        val activeNormalizer = requireNotNull(normalizer)
                        val required = activeNormalizer.requiredOutputCapacity(frameCount)
                        if (normalizedBuffer.size < required) {
                            normalizedBuffer = ShortArray(required)
                        }
                        val normalizeStarted = System.nanoTime()
                        val monoCount =
                            activeNormalizer.processInterleavedInto(
                                input = samples,
                                output = normalizedBuffer,
                                frameCount = frameCount,
                            )
                        normalizeNanos += System.nanoTime() - normalizeStarted
                        if (monoCount > 0) {
                            if (!writingReported) {
                                writingReported = true
                                onStage(CanonicalAudioStage.WRITING)
                            }
                            writer.writePcm16(normalizedBuffer, length = monoCount)
                        }

                        processedFrames += frameCount.toLong()
                        val frameCountTimelineUs =
                            sampleIndexToTimeUs(processedFrames, format.sampleRateHz)
                        val ptsEndUs =
                            presentationTimeUs
                                ?.takeIf { it >= 0L }
                                ?.let { startUs ->
                                    startUs +
                                        sampleIndexToTimeUs(
                                            frameCount.toLong(),
                                            format.sampleRateHz,
                                        )
                                }
                        processedTimelineUs =
                            maxOf(
                                processedTimelineUs,
                                ptsEndUs ?: frameCountTimelineUs,
                            )
                        onProgress(
                            CanonicalAudioWorkProgress(
                                processedDurationUs = processedTimelineUs,
                                totalDurationUs = knownDurationUs?.takeIf { it > 0L },
                                processedUnits = processedFrames,
                                totalUnits = null,
                            ),
                        )
                    }
                }

            val decodeStarted = System.nanoTime()
            val decoded =
                try {
                    decoder.decode(
                        file = sourceFile,
                        isCancelled = isCancelled,
                        sink = sink,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AudioPipelineException) {
                    throw error
                } catch (error: Throwable) {
                    throw AudioPipelineException(
                        code = "MEDIA_DECODE_FAILED",
                        recoverable = false,
                        message = error.message ?: error::class.java.simpleName,
                        cause = error,
                    )
                }
            val decodeWallNanos = System.nanoTime() - decodeStarted

            if (decoded.decodedFrameCount <= 0L) {
                throw AudioPipelineException(
                    code = "MEDIA_DECODE_EMPTY",
                    recoverable = false,
                    message = "decoder produced no audio frames",
                )
            }

            val decodedFrameDurationUs =
                sampleIndexToTimeUs(
                    decoded.decodedFrameCount,
                    decoded.output.sampleRateHz,
                )
            val finalMediaTimelineUs =
                decoded.presentationEndTimeUs?.takeIf { it > 0L }
                    ?: processedTimelineUs.takeIf { it > 0L }
                    ?: decodedFrameDurationUs
            val metadataDurationUs =
                knownDurationUs?.takeIf { it > 0L }
                    ?: decoded.input.durationUs?.takeIf { it > 0L }
            onProgress(
                CanonicalAudioWorkProgress(
                    processedDurationUs = finalMediaTimelineUs,
                    totalDurationUs = metadataDurationUs,
                    processedUnits = decoded.decodedFrameCount,
                    totalUnits = null,
                    isFinal = true,
                ),
            )

            onStage(CanonicalAudioStage.VERIFYING)
            val wav = writer.commit()
            val pipelineNanos = System.nanoTime() - pipelineStarted
            val decodeOrReadNanos =
                (decodeWallNanos - normalizeNanos - wav.timing.writeNanos)
                    .coerceAtLeast(0L)
            return CompressedAudioConversionResult(
                sourceSha256 = sourceSha,
                decoded = decoded,
                wav = wav,
                timing =
                    CanonicalConversionTiming(
                        decodeOrReadNanos = decodeOrReadNanos,
                        normalizeNanos = normalizeNanos,
                        writer = wav.timing,
                        pipelineNanos = pipelineNanos,
                        metadataDurationUs = metadataDurationUs,
                        decodedFrameDurationUs = decodedFrameDurationUs,
                        mediaTimelineDurationUs = finalMediaTimelineUs,
                        canonicalDurationUs = wav.durationUs,
                    ),
            )
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
