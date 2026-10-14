package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.transcript.StreamingAudioSideSink
import io.github.ioannes78.voica.transcript.StreamingAudioSideSinkOfferResult
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal fun interface Stage16ARealtimeAudioActivityConsumer {
    fun onRealtimeAudioActivity(level: Float)
}

/** Lightweight, media-derived activity value used only for the realtime product waveform. */
internal object Stage16ARealtimePcmActivityMeter {
    fun normalizedMeanAbsolute(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var absoluteSum = 0L
        samples.forEach { sample ->
            absoluteSum += abs(sample.toInt()).toLong()
        }
        return (absoluteSum.toDouble() / samples.size.toDouble() / PCM16_FULL_SCALE)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private const val PCM16_FULL_SCALE = 32_768.0
}

/**
 * Non-blocking canonical side branch for the product waveform.
 *
 * The coordinator thread only copies the newest PCM frame into a conflated mailbox. Activity
 * calculation and StateFlow publication run on the supplied application scope, so waveform rendering
 * can never backpressure Stage 15 canonical ingest. Updates are paced by canonical sample indexes,
 * never wall clock.
 */
internal class Stage16ARealtimeAudioActivitySink(
    scope: CoroutineScope,
    private val consumers: List<Stage16ARealtimeAudioActivityConsumer>,
    private val minUpdateSamples: Int = 1_280,
) : StreamingAudioSideSink {
    init {
        require(minUpdateSamples > 0)
    }

    private val mailbox = Channel<PcmWork>(Channel.CONFLATED)
    private val worker: Job =
        scope.launch {
            var sessionId = Long.MIN_VALUE
            var epochId = Long.MIN_VALUE
            var nextUpdateSampleIndex = Long.MIN_VALUE

            for (work in mailbox) {
                if (work.sessionId != sessionId || work.epochId != epochId) {
                    sessionId = work.sessionId
                    epochId = work.epochId
                    nextUpdateSampleIndex = work.startSampleIndex + minUpdateSamples
                }

                val endSampleIndexExclusive = work.startSampleIndex + work.samples.size.toLong()
                if (endSampleIndexExclusive < nextUpdateSampleIndex) continue

                val level = Stage16ARealtimePcmActivityMeter.normalizedMeanAbsolute(work.samples)
                consumers.forEach { consumer ->
                    runCatching { consumer.onRealtimeAudioActivity(level) }
                }
                nextUpdateSampleIndex = endSampleIndexExclusive + minUpdateSamples
            }
        }.also { job ->
            job.invokeOnCompletion { mailbox.close() }
        }

    override fun offer(event: StreamingAudioEvent): StreamingAudioSideSinkOfferResult {
        val pcm = event as? StreamingAudioEvent.PcmFrame
            ?: return StreamingAudioSideSinkOfferResult.ACCEPTED
        val result =
            mailbox.trySend(
                PcmWork(
                    sessionId = pcm.frame.sessionId,
                    epochId = pcm.frame.epochId,
                    startSampleIndex = pcm.frame.startSampleIndex,
                    samples = pcm.frame.samples.copyOf(),
                ),
            )
        return if (result.isSuccess) {
            StreamingAudioSideSinkOfferResult.ACCEPTED
        } else {
            StreamingAudioSideSinkOfferResult.REJECTED
        }
    }

    fun close() {
        mailbox.close()
    }

    suspend fun join() {
        worker.join()
    }

    private data class PcmWork(
        val sessionId: Long,
        val epochId: Long,
        val startSampleIndex: Long,
        val samples: ShortArray,
    )
}
