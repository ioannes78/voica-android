package io.github.ioannes78.voica.playback

import io.github.ioannes78.voica.audio.EvidencePlaybackExpectation
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.AudioContainerKind
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PlaybackAudioSource
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import io.github.ioannes78.voica.audio.PlaybackErrorCode
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.audio.SeekableAudioHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPlaybackControllerTest {
    @Test
    fun loadPlayPauseAndSeekUseCanonicalSampleCoordinates() = runTest {
        val dataOffset = 98
        val sourceBytes = ByteArray(dataOffset + 200 * 2)
        for (sample in 0 until 200) {
            val value = sample.toShort().toInt()
            val offset = dataOffset + sample * 2
            sourceBytes[offset] = (value and 0xFF).toByte()
            sourceBytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        val sink = FakeSink()
        val controller = StreamingPlaybackController(
            sourceResolver = FakeResolver(
                bytes = sourceBytes,
                dataOffset = dataOffset.toLong(),
            ),
            sinkFactory = PlaybackAudioSinkFactory { sink },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.load("recording-1")
        assertEquals(PlaybackState.READY, controller.snapshot.value.state)
        assertEquals(200L, controller.snapshot.value.durationSampleCount)

        controller.play()
        runCurrent()
        assertTrue(sink.totalWrittenBytes > 0)

        controller.pause()
        assertEquals(PlaybackState.PAUSED, controller.snapshot.value.state)

        sink.clearWritten()
        controller.seekToSample(150L)
        controller.play()
        runCurrent()

        assertTrue(sink.flushCount >= 1)
        assertTrue(sink.writes.isNotEmpty())
        val first = sink.writes.first()
        assertEquals(150 and 0xFF, first[0].toInt() and 0xFF)
        assertEquals((150 ushr 8) and 0xFF, first[1].toInt() and 0xFF)

        controller.release()
        runCurrent()
    }

    @Test
    fun latestRapidSeekWins() = runTest {
        val dataOffset = 98L
        val bytes = ByteArray(dataOffset.toInt() + 1_000 * 2)
        val sink = FakeSink()
        val controller = StreamingPlaybackController(
            sourceResolver = FakeResolver(
                bytes = bytes,
                sampleCount = 1_000L,
                dataOffset = dataOffset,
            ),
            sinkFactory = PlaybackAudioSinkFactory { sink },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.load("recording-1")

        controller.seekToSample(100L)
        controller.seekToSample(300L)
        controller.seekToSample(900L)

        assertEquals(900L, controller.snapshot.value.positionSampleIndex)
        assertEquals(PlaybackState.PAUSED, controller.snapshot.value.state)

        controller.release()
        runCurrent()
    }


    @Test
    fun completedWaitsForPresentedFramesAndReplayRestartsAtZero() = runTest {
        val dataOffset = 98L
        val sampleCount = 200L
        val bytes = ByteArray(dataOffset.toInt() + sampleCount.toInt() * 2)
        val sink = FakeSink()
        val controller = StreamingPlaybackController(
            sourceResolver = FakeResolver(
                bytes = bytes,
                sampleCount = sampleCount,
                dataOffset = dataOffset,
            ),
            sinkFactory = PlaybackAudioSinkFactory { sink },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.load("recording-eof")
        controller.play()
        runCurrent()

        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)

        sink.framePosition = sampleCount
        advanceTimeBy(50L)
        runCurrent()

        assertEquals(PlaybackState.COMPLETED, controller.snapshot.value.state)
        assertEquals(sampleCount, controller.snapshot.value.positionSampleIndex)

        val discontinuity = controller.snapshot.value.discontinuityGeneration
        controller.play()
        runCurrent()

        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)
        assertEquals(0L, controller.snapshot.value.positionSampleIndex)
        assertTrue(
            controller.snapshot.value.discontinuityGeneration > discontinuity,
        )

        controller.release()
        runCurrent()
        assertEquals(PlaybackState.RELEASED, controller.snapshot.value.state)
    }


    @Test
    fun invalidCanonicalDescriptorFailsBeforeOpeningAudioSink() = runTest {
        val bytes = ByteArray(44 + 20)
        var sinkCreated = false
        val controller = StreamingPlaybackController(
            sourceResolver = FakeResolver(
                bytes = bytes,
                sampleCount = 10L,
                bitsPerSample = 24,
            ),
            sinkFactory = PlaybackAudioSinkFactory {
                sinkCreated = true
                FakeSink()
            },
            scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        controller.load("invalid-recording")

        assertEquals(PlaybackState.ERROR, controller.snapshot.value.state)
        assertEquals(
            PlaybackErrorCode.INVALID_CANONICAL_WAV,
            controller.snapshot.value.error?.code,
        )
        assertTrue(!sinkCreated)

        controller.release()
        runCurrent()
    }

    private fun evidenceExpectation(bytes: ByteArray, start: Long = 150L): EvidencePlaybackExpectation =
        EvidencePlaybackExpectation("recording-1", "asset-1",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }, CanonicalPcmProfile.PROFILE_ID, 200L, start, 200L)

    @Test
    fun evidenceTransactionUsesVerifiedHandleAndExactFirstSample() = runTest {
        val bytes = ByteArray(44 + 400)
        for (i in 0 until 200) bytes[44 + i * 2] = i.toByte()
        val sink = FakeSink()
        var opens = 0
        val resolver = FakeResolver(bytes)
        val controller = StreamingPlaybackController(
            sourceResolver = object : AudioSourceResolver {
                override suspend fun resolvePlaybackSource(recordingId: String): PlaybackAudioSource {
                    opens++
                    return resolver.resolvePlaybackSource(recordingId)
                }
            },
            sinkFactory = PlaybackAudioSinkFactory { sink }, scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            assertTrue(controller.playVerifiedEvidence(evidenceExpectation(bytes)) { true })
            assertEquals(1, opens)
            assertEquals("asset-1", controller.snapshot.value.sourceAssetId)
            assertEquals(150L, controller.snapshot.value.positionSampleIndex)
            runCurrent()
            assertEquals(150, sink.writes.first()[0].toInt() and 255)
        } finally { controller.release() }
    }

    @Test
    fun rejectedEvidencePreservesExistingSessionAndClosesCandidate() = runTest {
        val bytes = ByteArray(444)
        val candidate = ByteArrayHandle(bytes)
        var opens = 0
        val resolver = FakeResolver(bytes)
        val controller = StreamingPlaybackController(
            sourceResolver = object : AudioSourceResolver {
                override suspend fun resolvePlaybackSource(recordingId: String): PlaybackAudioSource {
                    opens++
                    val source = resolver.resolvePlaybackSource(recordingId)
                    if (opens == 1) return source
                    source.handle.close()
                    return source.copy(handle = candidate)
                }
            },
            sinkFactory = PlaybackAudioSinkFactory { FakeSink() }, scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            controller.load("previous")
            assertTrue(!controller.playVerifiedEvidence(evidenceExpectation(bytes).copy(sha256 = "f".repeat(64))) { true })
            assertEquals("previous", controller.snapshot.value.recordingId)
            assertTrue(candidate.closed)
        } finally { controller.release() }
    }

    @Test
    fun evidenceTransactionSerializesConcurrentLoad() = runTest {
        val bytes = ByteArray(444)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var checks = 0
        val controller = StreamingPlaybackController(
            sourceResolver = FakeResolver(bytes), sinkFactory = PlaybackAudioSinkFactory { FakeSink() },
            scope = this, ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val qa = async {
                controller.playVerifiedEvidence(evidenceExpectation(bytes)) {
                    if (++checks == 3) { entered.complete(Unit); resume.await() }
                    true
                }
            }
            entered.await()
            val other = launch { controller.load("other") }
            runCurrent()
            assertTrue(!other.isCompleted)
            resume.complete(Unit)
            assertTrue(qa.await())
            other.join()
            assertEquals("other", controller.snapshot.value.recordingId)
        } finally { resume.complete(Unit); controller.release() }
    }

    @Test
    fun cancellationAfterInstallClosesVerifiedSession() = runTest {
        val bytes = ByteArray(444)
        val candidate = ByteArrayHandle(bytes)
        val sink = FakeSink()
        val entered = CompletableDeferred<Unit>()
        var checks = 0
        val controller = StreamingPlaybackController(
            sourceResolver = object : AudioSourceResolver {
                override suspend fun resolvePlaybackSource(recordingId: String): PlaybackAudioSource {
                    val source = FakeResolver(bytes).resolvePlaybackSource(recordingId)
                    source.handle.close()
                    return source.copy(handle = candidate)
                }
            },
            sinkFactory = PlaybackAudioSinkFactory { sink }, scope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val job = launch {
                controller.playVerifiedEvidence(evidenceExpectation(bytes)) {
                    if (++checks == 3) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                    true
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(candidate.closed)
            assertTrue(sink.closed)
            assertEquals(PlaybackState.IDLE, controller.snapshot.value.state)
        } finally { controller.release() }
    }

    private class FakeResolver(
        private val bytes: ByteArray,
        private val sampleCount: Long = 200L,
        private val dataOffset: Long = 44L,
        private val bitsPerSample: Int = 16,
    ) : AudioSourceResolver {
        override suspend fun resolvePlaybackSource(
            recordingId: String,
        ): PlaybackAudioSource =
            PlaybackAudioSource(
                descriptor = PlaybackAudioSourceDescriptor(
                    recordingId = recordingId,
                    assetId = "asset-1",
                    container = AudioContainerKind.WAV,
                    durationUs = sampleCount * 1_000_000L / 16_000L,
                    sampleRateHz = 16_000,
                    channelCount = 1,
                    seekable = true,
                    lengthBytes = bytes.size.toLong(),
                    bitsPerSample = bitsPerSample,
                    pcmDataOffsetBytes = dataOffset,
                    pcmDataSizeBytes = sampleCount * 2L,
                    bytesPerFrame = 2,
                    totalSampleCount = sampleCount,
                ),
                handle = ByteArrayHandle(bytes),
            )
    }

    private class ByteArrayHandle(
        private val bytes: ByteArray,
    ) : SeekableAudioHandle {
        override val lengthBytes: Long = bytes.size.toLong()
        var closed = false
            private set

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

    private class FakeSink : PlaybackAudioSink {
        var framePosition = 0L
        var flushCount = 0
        var totalWrittenBytes = 0
        val writes = mutableListOf<ByteArray>()
        var closed = false
            private set

        override val underrunCount: Int = 0

        override fun play() = Unit
        override fun pause() = Unit

        override fun flush() {
            flushCount += 1
            framePosition = 0L
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            check(!closed)
            val copy = buffer.copyOfRange(offset, offset + length)
            writes += copy
            totalWrittenBytes += length
            return length
        }

        override fun setSpeed(speed: Float): Boolean = true

        override fun position(): PlaybackSinkPosition =
            PlaybackSinkPosition(framePosition = framePosition)

        fun clearWritten() {
            writes.clear()
            totalWrittenBytes = 0
        }

        override fun close() {
            closed = true
        }
    }
}
