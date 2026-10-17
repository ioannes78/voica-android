package io.github.ioannes78.voica.playback

import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PlaybackAudioSource
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import io.github.ioannes78.voica.audio.SeekableAudioHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRouteRebaseTest {
    @Test
    fun healthyOutputRouteRebaseDoesNotCreateSecondSink() = runTest {
        val sink = ProbeSink()
        var createCount = 0
        val controller = StreamingPlaybackController(
            sourceResolver = ProbeResolver(),
            sinkFactory = PlaybackAudioSinkFactory {
                createCount += 1
                sink
            },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.load("route-rebase")
        controller.play()
        runCurrent()
        assertEquals(1, createCount)

        sink.framePosition = 120L
        controller.rebaseOutputPosition()
        sink.framePosition = 180L
        controller.rebaseOutputPosition()

        assertEquals(1, createCount)
        assertFalse(sink.closed)
        assertEquals(0L, controller.snapshot.value.discontinuityGeneration)

        controller.release()
        runCurrent()
    }

    private class ProbeResolver : AudioSourceResolver {
        private val sampleCount = 4_000L
        private val dataOffset = 44L
        private val bytes = ByteArray(dataOffset.toInt() + sampleCount.toInt() * 2)

        override suspend fun resolvePlaybackSource(recordingId: String): PlaybackAudioSource =
            PlaybackAudioSource(
                descriptor = PlaybackAudioSourceDescriptor(
                    recordingId = recordingId,
                    assetId = "route-asset",
                    container = AudioContainerKind.WAV,
                    durationUs = sampleCount * 1_000_000L / 16_000L,
                    sampleRateHz = 16_000,
                    channelCount = 1,
                    seekable = true,
                    lengthBytes = bytes.size.toLong(),
                    bitsPerSample = 16,
                    pcmDataOffsetBytes = dataOffset,
                    pcmDataSizeBytes = sampleCount * 2L,
                    bytesPerFrame = 2,
                    totalSampleCount = sampleCount,
                ),
                handle = ProbeHandle(bytes),
            )
    }

    private class ProbeHandle(
        private val bytes: ByteArray,
    ) : SeekableAudioHandle {
        override val lengthBytes: Long = bytes.size.toLong()
        private var closed = false

        override fun readAt(
            offset: Long,
            target: ByteArray,
            targetOffset: Int,
            length: Int,
        ): Int {
            check(!closed)
            if (offset >= bytes.size) return -1
            val count = minOf(length, bytes.size - offset.toInt())
            bytes.copyInto(
                destination = target,
                destinationOffset = targetOffset,
                startIndex = offset.toInt(),
                endIndex = offset.toInt() + count,
            )
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private class ProbeSink : PlaybackAudioSink {
        var framePosition = 0L
        var closed = false

        override val underrunCount: Int = 0

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
            PlaybackSinkPosition(framePosition = framePosition)

        override fun close() {
            closed = true
        }
    }
}
