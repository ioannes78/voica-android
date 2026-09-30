package io.github.ioannes78.voica.audio

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class RawOpusFraming {
    FIXED_40_BYTES,
    LENGTH_U8,
    LENGTH_U16_LE,
    LENGTH_U16_BE,
}

data class RawOpusPacketSample(
    val packetIndex: Long,
    val packetLength: Int,
    val toc: Int?,
    val channels: Int?,
    val totalSamples48k: Int?,
    val bandwidth: Int?,
)

data class RawOpusValidation(
    val framing: RawOpusFraming,
    val packetCount: Long,
    val minPacketBytes: Int,
    val maxPacketBytes: Int,
    val channelCount: Int,
    val totalSamples48k: Long,
    val decodedDurationUs: Long,
    val packetDurationHistogramUs: Map<Long, Long>,
    val bandwidthHistogram: Map<Int, Long>,
    val samples: List<RawOpusPacketSample>,
)

sealed interface RawOpusValidationResult {
    data class Valid(val value: RawOpusValidation) : RawOpusValidationResult

    data class UnsupportedFraming(
        val detail: String,
        val attempts: Map<RawOpusFraming, String>,
    ) : RawOpusValidationResult
}

class RawOpusValidator(
    private val inspector: OpusPacketInspector,
) {
    fun validate(file: File): RawOpusValidationResult {
        if (!file.isFile || file.length() <= 0L) {
            return RawOpusValidationResult.UnsupportedFraming(
                detail = "raw Opus candidate is missing or empty",
                attempts = emptyMap(),
            )
        }

        val valid = linkedMapOf<RawOpusFraming, RawOpusValidation>()
        val failures = linkedMapOf<RawOpusFraming, String>()

        RawOpusFraming.entries.forEach { framing ->
            val attempt = runCatching { validateCandidate(file, framing) }
            attempt.onSuccess { valid[framing] = it }
            attempt.onFailure { failures[framing] = it.message ?: it::class.java.simpleName }
        }

        return when (valid.size) {
            1 -> RawOpusValidationResult.Valid(valid.values.single())
            0 -> RawOpusValidationResult.UnsupportedFraming(
                detail = "no supported framing consumed the file as valid Opus packets",
                attempts = failures,
            )
            else -> RawOpusValidationResult.UnsupportedFraming(
                detail = "ambiguous framing candidates: " +
                    valid.keys.joinToString { it.name },
                attempts = failures + valid.keys.associateWith { "valid but ambiguous" },
            )
        }
    }

    private fun validateCandidate(
        file: File,
        framing: RawOpusFraming,
    ): RawOpusValidation {
        var packetCount = 0L
        var minPacketBytes = Int.MAX_VALUE
        var maxPacketBytes = 0
        var channelCount: Int? = null
        var totalSamples48k = 0L
        val durationHistogram = linkedMapOf<Long, Long>()
        val bandwidthHistogram = linkedMapOf<Int, Long>()
        val samples = ArrayList<RawOpusPacketSample>(MAX_DIAGNOSTIC_SAMPLES)

        forEachPacket(file, framing) { packetIndex, packet ->
            val info = inspector.inspect(packet)
            require(info.valid) {
                "packet $packetIndex rejected by Opus parser code=${info.errorCode}"
            }
            val packetChannels = requireNotNull(info.channels) {
                "packet $packetIndex has no channel metadata"
            }
            require(packetChannels == 1 || packetChannels == 2) {
                "packet $packetIndex has invalid channels=$packetChannels"
            }
            val expectedChannels = channelCount
            if (expectedChannels == null) {
                channelCount = packetChannels
            } else {
                require(packetChannels == expectedChannels) {
                    "packet $packetIndex changes channels $expectedChannels->$packetChannels"
                }
            }

            val packetSamples = requireNotNull(info.totalSamples48k) {
                "packet $packetIndex has no duration metadata"
            }
            require(packetSamples in MIN_PACKET_SAMPLES_48K..MAX_PACKET_SAMPLES_48K) {
                "packet $packetIndex has invalid 48k sample count=$packetSamples"
            }

            packetCount += 1
            minPacketBytes = minOf(minPacketBytes, packet.size)
            maxPacketBytes = maxOf(maxPacketBytes, packet.size)
            totalSamples48k += packetSamples.toLong()

            val durationUs = packetSamples.toLong() * 1_000_000L / 48_000L
            durationHistogram[durationUs] = (durationHistogram[durationUs] ?: 0L) + 1L
            info.bandwidth?.let { bandwidth ->
                bandwidthHistogram[bandwidth] =
                    (bandwidthHistogram[bandwidth] ?: 0L) + 1L
            }

            if (samples.size < MAX_DIAGNOSTIC_SAMPLES) {
                samples += RawOpusPacketSample(
                    packetIndex = packetIndex,
                    packetLength = packet.size,
                    toc = info.toc,
                    channels = info.channels,
                    totalSamples48k = info.totalSamples48k,
                    bandwidth = info.bandwidth,
                )
            }
        }

        require(packetCount > 0L) { "no packets" }
        val channels = requireNotNull(channelCount) { "missing channel metadata" }
        return RawOpusValidation(
            framing = framing,
            packetCount = packetCount,
            minPacketBytes = minPacketBytes,
            maxPacketBytes = maxPacketBytes,
            channelCount = channels,
            totalSamples48k = totalSamples48k,
            decodedDurationUs = totalSamples48k * 1_000_000L / 48_000L,
            packetDurationHistogramUs = durationHistogram.toMap(),
            bandwidthHistogram = bandwidthHistogram.toMap(),
            samples = samples.toList(),
        )
    }

    companion object {
        const val MAX_OPUS_PACKET_BYTES = 1275
        const val FIXED_DEVICE_PACKET_BYTES = 40
        private const val MIN_PACKET_SAMPLES_48K = 120
        private const val MAX_PACKET_SAMPLES_48K = 5760
        private const val MAX_DIAGNOSTIC_SAMPLES = 8
    }
}

data class RawOpusConversionResult(
    val validation: RawOpusValidation,
    val sourceSha256: String,
    val decoderVersion: String,
    val wav: CanonicalWavCommit,
)

class AudioPipelineException(
    val code: String,
    val recoverable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class RawOpusToCanonicalWavConverter(
    private val inspector: OpusPacketInspector,
    private val decoderFactory: OpusDecoderFactory,
) {
    fun convert(
        sourceFile: File,
        targetFile: File,
        expectedSourceSha256: String? = null,
        isCancelled: () -> Boolean = { false },
        onStage: (CanonicalAudioStage) -> Unit = {},
    ): RawOpusConversionResult {
        val validation = when (val result = RawOpusValidator(inspector).validate(sourceFile)) {
            is RawOpusValidationResult.Valid -> result.value
            is RawOpusValidationResult.UnsupportedFraming -> {
                throw AudioPipelineException(
                    code = "UNSUPPORTED_FRAMING",
                    recoverable = false,
                    message = result.detail,
                )
            }
        }

        val sourceSha = sha256(sourceFile)
        if (
            expectedSourceSha256 != null &&
            !sourceSha.equals(expectedSourceSha256, ignoreCase = true)
        ) {
            throw AudioPipelineException(
                code = "RAW_SHA_MISMATCH",
                recoverable = false,
                message = "source SHA-256 changed before decode",
            )
        }
        val normalizer = StreamingPcm16Normalizer(
            sourceSampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
            sourceChannelCount = validation.channelCount,
        )

        try {
            onStage(CanonicalAudioStage.DECODING)
            var normalizationReported = false
            var writingReported = false
            decoderFactory.create(
                sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                channelCount = validation.channelCount,
            ).use { decoder ->
                CanonicalWavWriter(targetFile).use { writer ->
                    forEachPacket(sourceFile, validation.framing) { packetIndex, packet ->
                        if (isCancelled()) {
                            throw AudioPipelineException(
                                code = "USER_CANCELLED",
                                recoverable = true,
                                message = "conversion cancelled",
                            )
                        }

                        val info = inspector.inspect(packet)
                        if (!info.valid) {
                            throw AudioPipelineException(
                                code = "INVALID_OPUS_PACKET",
                                recoverable = false,
                                message = "packet $packetIndex failed re-validation",
                            )
                        }

                        val totalSamples48k = info.totalSamples48k
                            ?: throw AudioPipelineException(
                                code = "INVALID_OPUS_PACKET",
                                recoverable = false,
                                message = "packet $packetIndex has no sample count",
                            )
                        require(totalSamples48k % 3 == 0) {
                            "Opus packet duration cannot map exactly to 16 kHz"
                        }
                        val expectedFrames16k = totalSamples48k / 3
                        val decoded = decoder.decode(packet)
                        if (decoded.size != expectedFrames16k * validation.channelCount) {
                            throw AudioPipelineException(
                                code = "OPUS_DECODE_FAILED",
                                recoverable = false,
                                message = "packet $packetIndex decoded ${decoded.size} shorts, " +
                                    "expected ${expectedFrames16k * validation.channelCount}",
                            )
                        }

                        if (!normalizationReported) {
                            normalizationReported = true
                            onStage(CanonicalAudioStage.NORMALIZING)
                        }
                        val mono = normalizer.processInterleaved(
                            input = decoded,
                            frameCount = expectedFrames16k,
                        )
                        if (!writingReported) {
                            writingReported = true
                            onStage(CanonicalAudioStage.WRITING)
                        }
                        writer.writePcm16(mono)
                    }

                    onStage(CanonicalAudioStage.VERIFYING)
                    val commit = writer.commit()
                    return RawOpusConversionResult(
                        validation = validation,
                        sourceSha256 = sourceSha,
                        decoderVersion = decoderFactory.version,
                        wav = commit,
                    )
                }
            }
        } catch (error: AudioPipelineException) {
            throw error
        } catch (error: IOException) {
            throw AudioPipelineException(
                code = "LOCAL_WRITE_FAILED",
                recoverable = true,
                message = error.message ?: error::class.java.simpleName,
                cause = error,
            )
        } catch (error: Throwable) {
            throw AudioPipelineException(
                code = "OPUS_DECODE_FAILED",
                recoverable = false,
                message = error.message ?: error::class.java.simpleName,
                cause = error,
            )
        }
    }
}

private fun forEachPacket(
    file: File,
    framing: RawOpusFraming,
    consumer: (packetIndex: Long, packet: ByteArray) -> Unit,
) {
    RandomAccessFile(file, "r").use { raf ->
        if (framing == RawOpusFraming.FIXED_40_BYTES) {
            require(raf.length() > 0L && raf.length() % RawOpusValidator.FIXED_DEVICE_PACKET_BYTES == 0L) {
                "file size is not aligned to 40-byte candidate framing"
            }
        }

        var packetIndex = 0L
        while (raf.filePointer < raf.length()) {
            val packetLength = when (framing) {
                RawOpusFraming.FIXED_40_BYTES ->
                    RawOpusValidator.FIXED_DEVICE_PACKET_BYTES

                RawOpusFraming.LENGTH_U8 -> {
                    require(raf.length() - raf.filePointer >= 1L) {
                        "truncated U8 length prefix at packet $packetIndex"
                    }
                    raf.readUnsignedByte()
                }

                RawOpusFraming.LENGTH_U16_LE -> {
                    require(raf.length() - raf.filePointer >= 2L) {
                        "truncated U16LE length prefix at packet $packetIndex"
                    }
                    val lo = raf.readUnsignedByte()
                    val hi = raf.readUnsignedByte()
                    lo or (hi shl 8)
                }

                RawOpusFraming.LENGTH_U16_BE -> {
                    require(raf.length() - raf.filePointer >= 2L) {
                        "truncated U16BE length prefix at packet $packetIndex"
                    }
                    val hi = raf.readUnsignedByte()
                    val lo = raf.readUnsignedByte()
                    (hi shl 8) or lo
                }
            }

            require(packetLength in 1..RawOpusValidator.MAX_OPUS_PACKET_BYTES) {
                "invalid packet length=$packetLength at packet $packetIndex"
            }
            require(raf.length() - raf.filePointer >= packetLength.toLong()) {
                "truncated packet $packetIndex length=$packetLength"
            }

            val packet = ByteArray(packetLength)
            try {
                raf.readFully(packet)
            } catch (error: EOFException) {
                throw IllegalArgumentException(
                    "truncated packet $packetIndex length=$packetLength",
                    error,
                )
            }
            consumer(packetIndex, packet)
            packetIndex += 1
        }
        require(packetIndex > 0L) { "no packets" }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(64 * 1024).use { input ->
        val buffer = ByteArray(64 * 1024)
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
