package io.github.ioannes78.voica.playback

import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackAudioSource
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import io.github.ioannes78.voica.audio.SeekableAudioHandle
import io.github.ioannes78.voica.audio.sampleIndexToPcmByteOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LongRecordingPlaybackTest {
    @Test
    fun longDurationsKeepSeekAddressingExactAndReadsBounded() = runTest {
        for (durationMinutes in listOf(30L, 60L, 120L)) {
            val totalSamples =
                durationMinutes * 60L * CanonicalPcmProfile.SAMPLE_RATE_HZ
            for (percent in listOf(10L, 50L, 90L)) {
                val targetSample = totalSamples * percent / 100L
                val handle = ProbeHandle(
                    totalSamples = totalSamples,
                    dataOffset = DATA_OFFSET,
                )
                val controller = StreamingPlaybackController(
                    sourceResolver = ProbeResolver(
                        recordingId = "long-$durationMinutes",
                        totalSamples = totalSamples,
                        handle = handle,
                    ),
                    sinkFactory = PlaybackAudioSinkFactory { ProbeSink() },
                    scope = this,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

                controller.load("long-$durationMinutes")
                controller.seekToSample(targetSample)
                controller.play()
                runCurrent()

                assertEquals(
                    sampleIndexToPcmByteOffset(
                        sampleIndex = targetSample,
                        totalSampleCount = totalSamples,
                        pcmDataOffsetBytes = DATA_OFFSET,
                        bytesPerFrame = BYTES_PER_FRAME,
                    ),
                    handle.firstReadOffset,
                )
                assertTrue(
                    "read request must remain bounded for $durationMinutes min",
                    handle.maxRequestedLength <= READ_BUFFER_BYTES,
                )

                controller.release()
                runCurrent()
            }
        }
    }

    @Test
    fun twoHourRapidSeekUsesOnlyFinalTargetWhenPlaybackStarts() = runTest {
        val totalSamples =
            120L * 60L * CanonicalPcmProfile.SAMPLE_RATE_HZ
        val handle = ProbeHandle(
            totalSamples = totalSamples,
            dataOffset = DATA_OFFSET,
        )
        val controller = StreamingPlaybackController(
            sourceResolver = ProbeResolver(
                recordingId = "two-hour",
                totalSamples = totalSamples,
                handle = handle,
            ),
            sinkFactory = PlaybackAudioSinkFactory { ProbeSink() },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.load("two-hour")

        val percents =
            listOf(
                80L, 20L, 60L, 10L, 90L,
                5L, 95L, 25L, 75L, 35L,
                65L, 15L, 85L, 45L, 55L,
                30L, 70L, 40L, 50L, 92L,
            )
        percents.forEach { percent ->
            controller.seekToSample(totalSamples * percent / 100L)
        }

        val finalTarget = totalSamples * percents.last() / 100L
        assertEquals(
            finalTarget,
            controller.snapshot.value.positionSampleIndex,
        )

        controller.play()
        runCurrent()

        assertEquals(
            sampleIndexToPcmByteOffset(
                sampleIndex = finalTarget,
                totalSampleCount = totalSamples,
                pcmDataOffsetBytes = DATA_OFFSET,
                bytesPerFrame = BYTES_PER_FRAME,
            ),
            handle.firstReadOffset,
        )
        assertEquals(1, handle.readCallCount)
        assertTrue(handle.maxRequestedLength <= READ_BUFFER_BYTES)

        controller.release()
        runCurrent()
    }

    private class ProbeResolver(
        private val recordingId: String,
        private val totalSamples: Long,
        private val handle: ProbeHandle,
    ) : AudioSourceResolver {
        override suspend fun resolvePlaybackSource(
            recordingId: String,
        ): PlaybackAudioSource {
            require(recordingId == this.recordingId)
            val pcmBytes = totalSamples * BYTES_PER_FRAME
            return PlaybackAudioSource(
                descriptor = PlaybackAudioSourceDescriptor(
                    recordingId = recordingId,
                    assetId = "probe-asset",
                    container = AudioContainerKind.WAV,
                    durationUs =
                        totalSamples * 1_000_000L /
                            CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                    seekable = true,
                    lengthBytes = DATA_OFFSET + pcmBytes,
                    bitsPerSample = CanonicalPcmProfile.BITS_PER_SAMPLE,
                    pcmDataOffsetBytes = DATA_OFFSET,
                    pcmDataSizeBytes = pcmBytes,
                    bytesPerFrame = BYTES_PER_FRAME,
                    totalSampleCount = totalSamples,
                ),
                handle = handle,
            )
        }
    }

    private class ProbeHandle(
        totalSamples: Long,
        private val dataOffset: Long,
    ) : SeekableAudioHandle {
        override val lengthBytes =
            dataOffset + totalSamples * BYTES_PER_FRAME

        var firstReadOffset: Long? = null
            private set
        var maxRequestedLength = 0
            private set
        var readCallCount = 0
            private set

        override fun readAt(
            offset: Long,
            target: ByteArray,
            targetOffset: Int,
            length: Int,
        ): Int {
            readCallCount += 1
            if (firstReadOffset == null) firstReadOffset = offset
            maxRequestedLength = maxOf(maxRequestedLength, length)
            return -1
        }

        override fun close() = Unit
    }

    private class ProbeSink : PlaybackAudioSink {
        override val underrunCount = 0

        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = length

        override fun setSpeed(speed: Float): Boolean = true

        override fun position(): PlaybackSinkPosition =
            PlaybackSinkPosition(framePosition = 0L)

        override fun close() = Unit
    }

    private companion object {
        const val DATA_OFFSET = 98L
        const val BYTES_PER_FRAME = 2
        const val READ_BUFFER_BYTES = 32 * 1024
    }
}
