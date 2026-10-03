package io.github.ioannes78.voica.audio

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

data class CompressedAudioConversionResult(
    val sourceSha256: String,
    val decoded: CompressedAudioDecodeResult,
    val wav: CanonicalWavCommit,
)

class CompressedAudioToCanonicalWavConverter(
    private val decoder: CompressedAudioDecoder,
) {
    suspend fun convert(
        sourceFile: File,
        targetFile: File,
        expectedSourceSha256: String? = null,
        isCancelled: () -> Boolean = { false },
        onStage: (CanonicalAudioStage) -> Unit = {},
    ): CompressedAudioConversionResult {
        val sourceSha = sha256(sourceFile)
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

        onStage(CanonicalAudioStage.DECODING)
        CanonicalWavWriter(targetFile).use { writer ->
            var sourceFormat: DecodedPcmFormat? = null
            var normalizer: StreamingPcm16Normalizer? = null
            var normalizationReported = false
            var writingReported = false

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
                        if (isCancelled()) {
                            throw CancellationException("media conversion cancelled")
                        }
                        val format = sourceFormat
                            ?: error("decoder emitted PCM before output format")
                        require(frameCount >= 0)
                        require(samples.size >= frameCount * format.channelCount)

                        if (!normalizationReported) {
                            normalizationReported = true
                            onStage(CanonicalAudioStage.NORMALIZING)
                        }
                        val mono =
                            requireNotNull(normalizer).processInterleaved(
                                input = samples,
                                frameCount = frameCount,
                            )
                        if (mono.isNotEmpty()) {
                            if (!writingReported) {
                                writingReported = true
                                onStage(CanonicalAudioStage.WRITING)
                            }
                            writer.writePcm16(mono)
                        }
                    }
                }

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

            if (decoded.decodedFrameCount <= 0L) {
                throw AudioPipelineException(
                    code = "MEDIA_DECODE_EMPTY",
                    recoverable = false,
                    message = "decoder produced no audio frames",
                )
            }

            onStage(CanonicalAudioStage.VERIFYING)
            return CompressedAudioConversionResult(
                sourceSha256 = sourceSha,
                decoded = decoded,
                wav = writer.commit(),
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
