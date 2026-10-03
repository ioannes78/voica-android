package io.github.ioannes78.voica.audio

import java.io.File

data class CompressedAudioDescriptor(
    val mimeType: String,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val durationUs: Long?,
    val decoderName: String,
)

sealed interface CompressedAudioProbeResult {
    data class Supported(
        val descriptor: CompressedAudioDescriptor,
    ) : CompressedAudioProbeResult

    data class Unsupported(
        val reason: String,
        val mimeType: String? = null,
    ) : CompressedAudioProbeResult

    data class Invalid(
        val reason: String,
    ) : CompressedAudioProbeResult
}

fun interface CompressedAudioProbe {
    fun probe(file: File): CompressedAudioProbeResult
}

data class DecodedPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
)

interface Pcm16DecodeSink {
    fun onFormat(format: DecodedPcmFormat)

    fun onSamples(
        samples: ShortArray,
        frameCount: Int,
    )
}

data class CompressedAudioDecodeResult(
    val input: CompressedAudioDescriptor,
    val output: DecodedPcmFormat,
    val decodedFrameCount: Long,
)

interface CompressedAudioDecoder {
    suspend fun decode(
        file: File,
        isCancelled: () -> Boolean = { false },
        sink: Pcm16DecodeSink,
    ): CompressedAudioDecodeResult
}
