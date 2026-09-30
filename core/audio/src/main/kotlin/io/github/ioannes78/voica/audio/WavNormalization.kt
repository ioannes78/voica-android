package io.github.ioannes78.voica.audio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

data class PcmWavConversionResult(
    val source: WavPcmInfo,
    val sourceSha256: String,
    val wav: CanonicalWavCommit,
)

class PcmWavToCanonicalWavConverter {
    fun convert(
        sourceFile: File,
        targetFile: File,
        expectedSourceSha256: String? = null,
        isCancelled: () -> Boolean = { false },
        onStage: (CanonicalAudioStage) -> Unit = {},
    ): PcmWavConversionResult {
        val parsed = WavPcmParser.parse(sourceFile)
        val info = (parsed as? WavParseResult.Valid)?.info
            ?: throw AudioPipelineException(
                code = "WAV_HEADER_FAILED",
                recoverable = false,
                message = (parsed as? WavParseResult.Invalid)?.reason ?: "invalid WAV",
            )
        if (!info.isPcm || info.bitsPerSample != 16) {
            throw AudioPipelineException(
                code = "UNSUPPORTED_WAV_PCM",
                recoverable = false,
                message = "WAV must be PCM16; format=${info.audioFormat} bits=${info.bitsPerSample}",
            )
        }
        if (info.channelCount !in 1..8) {
            throw AudioPipelineException(
                code = "UNSUPPORTED_WAV_CHANNELS",
                recoverable = false,
                message = "unsupported WAV channels=${info.channelCount}",
            )
        }

        val sourceSha = sha256(sourceFile)
        if (
            expectedSourceSha256 != null &&
            !sourceSha.equals(expectedSourceSha256, ignoreCase = true)
        ) {
            throw AudioPipelineException(
                code = "RAW_SHA_MISMATCH",
                recoverable = false,
                message = "source SHA-256 changed before WAV normalization",
            )
        }

        val normalizer = StreamingPcm16Normalizer(
            sourceSampleRateHz = info.sampleRateHz,
            sourceChannelCount = info.channelCount,
        )
        onStage(CanonicalAudioStage.DECODING)

        RandomAccessFile(sourceFile, "r").use { input ->
            input.seek(info.dataOffset)
            var remainingBytes = info.dataSizeBytes
            val frameBytes = info.blockAlign
            val framesPerChunk = MAX_CHUNK_BYTES / frameBytes
            if (framesPerChunk <= 0) {
                throw AudioPipelineException(
                    code = "UNSUPPORTED_WAV_FRAME",
                    recoverable = false,
                    message = "WAV blockAlign=${info.blockAlign} exceeds buffer",
                )
            }

            CanonicalWavWriter(targetFile).use { writer ->
                var normalizationReported = false
                var writingReported = false
                while (remainingBytes > 0L) {
                    if (isCancelled()) {
                        throw AudioPipelineException(
                            code = "USER_CANCELLED",
                            recoverable = true,
                            message = "conversion cancelled",
                        )
                    }

                    val wantedFrames = minOf(
                        framesPerChunk.toLong(),
                        remainingBytes / frameBytes,
                    ).toInt()
                    if (wantedFrames <= 0) break
                    val wantedBytes = wantedFrames * frameBytes
                    val bytes = ByteArray(wantedBytes)
                    input.readFully(bytes)

                    val samples = ShortArray(wantedFrames * info.channelCount)
                    var byteCursor = 0
                    for (index in samples.indices) {
                        val lo = bytes[byteCursor++].toInt() and 0xFF
                        val hi = bytes[byteCursor++].toInt()
                        samples[index] = ((hi shl 8) or lo).toShort()
                    }

                    if (!normalizationReported) {
                        normalizationReported = true
                        onStage(CanonicalAudioStage.NORMALIZING)
                    }
                    val mono = normalizer.processInterleaved(
                        input = samples,
                        frameCount = wantedFrames,
                    )
                    if (!writingReported) {
                        writingReported = true
                        onStage(CanonicalAudioStage.WRITING)
                    }
                    writer.writePcm16(mono)
                    remainingBytes -= wantedBytes.toLong()
                }

                if (remainingBytes != 0L) {
                    throw AudioPipelineException(
                        code = "WAV_DATA_TRUNCATED",
                        recoverable = false,
                        message = "WAV data ended with remainingBytes=$remainingBytes",
                    )
                }

                onStage(CanonicalAudioStage.VERIFYING)
                return PcmWavConversionResult(
                    source = info,
                    sourceSha256 = sourceSha,
                    wav = writer.commit(),
                )
            }
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
        const val MAX_CHUNK_BYTES = 16 * 1024
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
