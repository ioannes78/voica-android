package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

sealed interface WaveformOverviewResult {
    data class Ready(
        val amplitudes: List<Float>,
        val totalSampleCount: Long,
    ) : WaveformOverviewResult

    data object Unavailable : WaveformOverviewResult
}

/**
 * Lightweight product waveform. It samples short PCM windows at evenly spaced positions instead
 * of rescanning the whole recording. Playback never depends on this repository: failures simply
 * produce [WaveformOverviewResult.Unavailable].
 */
class WaveformOverviewRepository(
    private val sourceResolver: AudioSourceResolver,
    private val cacheRoot: File,
) {
    suspend fun load(
        recordingId: String,
        bucketCount: Int = DEFAULT_BUCKET_COUNT,
    ): WaveformOverviewResult =
        withContext(Dispatchers.IO) {
            runCatching {
                require(recordingId.isNotBlank())
                require(bucketCount in 32..256)
                val source = sourceResolver.resolvePlaybackSource(recordingId)
                    ?: return@runCatching WaveformOverviewResult.Unavailable
                source.handle.use { handle ->
                    val descriptor = source.descriptor
                    val totalSamples = descriptor.totalSampleCount
                        ?: return@runCatching WaveformOverviewResult.Unavailable
                    val dataOffset = descriptor.pcmDataOffsetBytes
                        ?: return@runCatching WaveformOverviewResult.Unavailable
                    val bytesPerFrame = descriptor.bytesPerFrame
                        ?: return@runCatching WaveformOverviewResult.Unavailable
                    if (
                        totalSamples <= 0L ||
                        descriptor.sampleRateHz != CanonicalPcmProfile.SAMPLE_RATE_HZ ||
                        descriptor.channelCount != CanonicalPcmProfile.CHANNEL_COUNT ||
                        descriptor.bitsPerSample != CanonicalPcmProfile.BITS_PER_SAMPLE ||
                        bytesPerFrame != CanonicalPcmProfile.BYTES_PER_SAMPLE
                    ) {
                        return@runCatching WaveformOverviewResult.Unavailable
                    }

                    readCache(recordingId, bucketCount, totalSamples)?.let {
                        return@runCatching it
                    }

                    val windowSamples = minOf(WINDOW_SAMPLES.toLong(), totalSamples).toInt()
                    val buffer = ByteArray(windowSamples * bytesPerFrame)
                    val peaks = ArrayList<Float>(bucketCount)
                    repeat(bucketCount) { bucket ->
                        val regionStart = totalSamples * bucket / bucketCount
                        val regionEnd = totalSamples * (bucket + 1L) / bucketCount
                        val regionLength = (regionEnd - regionStart).coerceAtLeast(1L)
                        val sampleCount = minOf(windowSamples.toLong(), regionLength).toInt()
                        val centeredStart =
                            (regionStart + (regionLength - sampleCount) / 2L)
                                .coerceIn(0L, (totalSamples - sampleCount).coerceAtLeast(0L))
                        val byteOffset = dataOffset + centeredStart * bytesPerFrame
                        val wantedBytes = sampleCount * bytesPerFrame
                        val read =
                            handle.readAt(
                                offset = byteOffset,
                                target = buffer,
                                targetOffset = 0,
                                length = wantedBytes,
                            )
                        if (read <= 1) {
                            peaks += 0f
                        } else {
                            var peak = 0
                            var index = 0
                            val evenRead = read - (read % 2)
                            while (index < evenRead) {
                                val low = buffer[index].toInt() and 0xff
                                val high = buffer[index + 1].toInt()
                                val sample = ((high shl 8) or low).toShort().toInt()
                                peak = max(peak, abs(sample).coerceAtMost(32767))
                                index += 2
                            }
                            peaks += peak / 32767f
                        }
                    }
                    val ready =
                        WaveformOverviewResult.Ready(
                            amplitudes = normalize(peaks),
                            totalSampleCount = totalSamples,
                        )
                    writeCache(recordingId, bucketCount, ready)
                    ready
                }
            }.getOrDefault(WaveformOverviewResult.Unavailable)
        }

    private fun normalize(values: List<Float>): List<Float> {
        val maxValue = values.maxOrNull()?.takeIf { it > 0f } ?: return values
        return values.map { (it / maxValue).coerceIn(MIN_VISIBLE_AMPLITUDE, 1f) }
    }

    private fun readCache(
        recordingId: String,
        bucketCount: Int,
        totalSamples: Long,
    ): WaveformOverviewResult.Ready? {
        val file = cacheFile(recordingId, bucketCount)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.size != 2) return@runCatching null
            val header = lines[0].split('|')
            if (
                header.size != 3 ||
                header[0] != CACHE_VERSION ||
                header[1].toLongOrNull() != totalSamples ||
                header[2].toIntOrNull() != bucketCount
            ) {
                return@runCatching null
            }
            val amplitudes =
                lines[1].split(',').map { token ->
                    token.toFloat().coerceIn(0f, 1f)
                }
            if (amplitudes.size != bucketCount) return@runCatching null
            WaveformOverviewResult.Ready(amplitudes, totalSamples)
        }.getOrNull()
    }

    private fun writeCache(
        recordingId: String,
        bucketCount: Int,
        ready: WaveformOverviewResult.Ready,
    ) {
        runCatching {
            cacheRoot.mkdirs()
            cacheFile(recordingId, bucketCount).writeText(
                CACHE_VERSION + "|" + ready.totalSampleCount + "|" + bucketCount + "\n" +
                    ready.amplitudes.joinToString(",") {
                        String.format(Locale.US, "%.4f", it)
                    },
            )
        }
    }

    private fun cacheFile(recordingId: String, bucketCount: Int): File {
        val safeId = recordingId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(cacheRoot, safeId + "_" + bucketCount + ".waveform")
    }

    private companion object {
        const val DEFAULT_BUCKET_COUNT = 96
        const val WINDOW_SAMPLES = 2_048
        const val MIN_VISIBLE_AMPLITUDE = 0.06f
        const val CACHE_VERSION = "v1"
    }
}