package io.github.ioannes78.voica.media

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.ioannes78.voica.audio.CompressedAudioDescriptor
import io.github.ioannes78.voica.audio.CompressedAudioProbe
import io.github.ioannes78.voica.audio.CompressedAudioProbeResult
import java.io.File

class AndroidMediaProbe : CompressedAudioProbe {
    override fun probe(file: File): CompressedAudioProbeResult {
        if (!file.isFile || file.length() <= 0L) {
            return CompressedAudioProbeResult.Invalid("file missing or empty")
        }

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val format = findAudioTrack(extractor)
                ?: return CompressedAudioProbeResult.Unsupported("no audio track")
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return CompressedAudioProbeResult.Unsupported("audio track has no MIME")

            val decoderName =
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .findDecoderForFormat(format)
                    ?: return CompressedAudioProbeResult.Unsupported(
                        reason = "no platform decoder",
                        mimeType = mime,
                    )

            CompressedAudioProbeResult.Supported(
                CompressedAudioDescriptor(
                    mimeType = mime,
                    sampleRateHz = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    durationUs = format.longOrNull(MediaFormat.KEY_DURATION),
                    decoderName = decoderName,
                ),
            )
        } catch (error: Throwable) {
            CompressedAudioProbeResult.Invalid(
                error.message ?: error::class.java.simpleName,
            )
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): MediaFormat? {
        repeat(extractor.trackCount) { index ->
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return format
        }
        return null
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null
}
