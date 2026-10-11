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

    /**
     * Consumes one synchronous PCM16 block.
     *
     * [samples] is valid only for the duration of this callback. Decoders may reuse the same backing
     * array for later blocks, so sinks must copy any data they need to retain after returning.
     */
    fun onSamples(
        samples: ShortArray,
        frameCount: Int,
    )

    /**
     * Timestamp-aware variant for decoders that expose a source media timeline.
     *
     * [presentationTimeUs] is the source-media timestamp for the first frame in this block. The
     * default preserves compatibility with decoders/tests that only provide frame-count progress.
     */
    fun onSamplesAt(
        samples: ShortArray,
        frameCount: Int,
        presentationTimeUs: Long?,
    ) {
        onSamples(samples, frameCount)
    }
}

data class CompressedAudioDecodeResult(
    val input: CompressedAudioDescriptor,
    val output: DecodedPcmFormat,
    val decodedFrameCount: Long,
    val presentationEndTimeUs: Long? = null,
)

interface CompressedAudioDecoder {
    suspend fun decode(
        file: File,
        isCancelled: () -> Boolean = { false },
        sink: Pcm16DecodeSink,
    ): CompressedAudioDecodeResult
}
