package io.github.ioannes78.voica.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.ioannes78.voica.audio.CompressedAudioDecodeResult
import io.github.ioannes78.voica.audio.CompressedAudioDecoder
import io.github.ioannes78.voica.audio.CompressedAudioDescriptor
import io.github.ioannes78.voica.audio.DecodedPcmFormat
import io.github.ioannes78.voica.audio.Pcm16DecodeSink
import java.io.File
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMediaDecoder : CompressedAudioDecoder {
    override suspend fun decode(
        file: File,
        isCancelled: () -> Boolean,
        sink: Pcm16DecodeSink,
    ): CompressedAudioDecodeResult =
        withContext(Dispatchers.IO) {
            require(file.isFile && file.length() > 0L) { "source file missing or empty" }

            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = findAudioTrackIndex(extractor)
                    ?: error("no audio track")
                extractor.selectTrack(trackIndex)

                val sourceFormat = extractor.getTrackFormat(trackIndex)
                val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                    ?: error("audio track has no MIME")
                val decoderName =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .findDecoderForFormat(sourceFormat)
                        ?: error("no platform decoder for $mime")

                val descriptor =
                    CompressedAudioDescriptor(
                        mimeType = mime,
                        sampleRateHz = sourceFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = sourceFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                        durationUs = sourceFormat.longOrNull(MediaFormat.KEY_DURATION),
                        decoderName = decoderName,
                    )

                val decodeFormat = MediaFormat(sourceFormat)
                runCatching {
                    decodeFormat.setInteger(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                }

                codec = MediaCodec.createByCodecName(decoderName)
                codec.configure(decodeFormat, null, null, 0)
                codec.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var outputFormat: DecodedPcmFormat? = null
                var totalFrames = 0L

                while (!outputDone) {
                    if (isCancelled()) throw CancellationException("media decode cancelled")

                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("decoder input buffer missing")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormat = resolveOutputFormat(codec.outputFormat)
                            sink.onFormat(outputFormat)
                        }
                        else -> {
                            if (outputIndex >= 0) {
                                val perBufferFormat = runCatching {
                                    codec.getOutputFormat(outputIndex)
                                }.getOrNull()
                                val format =
                                    perBufferFormat?.let(::resolveOutputFormat)
                                        ?: outputFormat
                                        ?: resolveOutputFormat(codec.outputFormat)
                                if (outputFormat != format) {
                                    outputFormat = format
                                    sink.onFormat(format)
                                }

                                if (bufferInfo.size > 0) {
                                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                                        ?: error("decoder output buffer missing")
                                    val view = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    view.position(bufferInfo.offset)
                                    view.limit(bufferInfo.offset + bufferInfo.size)
                                    val sampleCount = view.remaining() / 2
                                    require(sampleCount % format.channelCount == 0) {
                                        "decoder output is not frame aligned"
                                    }
                                    val samples = ShortArray(sampleCount)
                                    view.asShortBuffer().get(samples)
                                    val frameCount = sampleCount / format.channelCount
                                    sink.onSamples(samples, frameCount)
                                    totalFrames += frameCount
                                }

                                outputDone =
                                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                }

                val finalFormat = outputFormat
                    ?: error("decoder produced no PCM format")

                CompressedAudioDecodeResult(
                    input = descriptor,
                    output = finalFormat,
                    decodedFrameCount = totalFrames,
                )
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        }

    private fun resolveOutputFormat(format: MediaFormat): DecodedPcmFormat {
        val encoding =
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
        require(encoding == AudioFormat.ENCODING_PCM_16BIT) {
            "unsupported decoded PCM encoding=$encoding"
        }
        return DecodedPcmFormat(
            sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        )
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        repeat(extractor.trackCount) { index ->
            val mime =
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    .orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
