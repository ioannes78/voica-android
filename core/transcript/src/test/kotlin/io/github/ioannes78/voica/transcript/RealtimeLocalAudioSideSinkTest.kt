package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeLocalAudioSideSinkTest {
    @Test
    fun `bounded worker finalizes canonical wav without blocking source callback`() = runBlocking {
        val root = Files.createTempDirectory("voica-local-audio-sink").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = CompletableDeferred<RealtimeLocalAudioResult>()
        val sink =
            RealtimeLocalAudioSideSink(
                scope = scope,
                targetFactory = RealtimeLocalAudioTargetFactory { File(root, "session-$it.wav") },
                resultHandler = { result.complete(it) },
                queueCapacity = 8,
            )
        try {
            assertEquals(StreamingAudioSideSinkOfferResult.ACCEPTED, sink.offer(StreamingAudioEvent.SessionStarted(7)))
            assertEquals(
                StreamingAudioSideSinkOfferResult.ACCEPTED,
                sink.offer(StreamingAudioEvent.EpochStarted(7, 1, RealtimeCanonicalEpochStartReason.SESSION_START)),
            )
            assertEquals(StreamingAudioSideSinkOfferResult.ACCEPTED, sink.offer(StreamingAudioEvent.PcmFrame(frame(7, 1, 0))))
            assertEquals(
                StreamingAudioSideSinkOfferResult.ACCEPTED,
                sink.offer(StreamingAudioEvent.EpochStarted(7, 2, RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY)),
            )
            assertEquals(StreamingAudioSideSinkOfferResult.ACCEPTED, sink.offer(StreamingAudioEvent.PcmFrame(frame(7, 2, 0))))
            assertEquals(
                StreamingAudioSideSinkOfferResult.ACCEPTED,
                sink.offer(
                    StreamingAudioEvent.SessionEnded(
                        sessionId = 7,
                        finalEpochId = 2,
                        finalSampleIndexExclusive = 4,
                        reason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = false,
                    ),
                ),
            )

            val finalized = withTimeout(5_000) { result.await() } as RealtimeLocalAudioResult.Finalized
            assertEquals(7L, finalized.canonicalSessionId)
            assertEquals(RealtimeCanonicalSessionEndReason.STOP, finalized.endReason)
            assertEquals(8L, finalized.media.sampleCount)
            assertEquals(60L, finalized.media.sizeBytes)
            assertFalse(finalized.media.hadDiscontinuity)
            assertTrue(finalized.media.file.isFile)
            assertEquals(6L, sink.snapshot().acceptedEvents)
            assertEquals(2L, sink.snapshot().acceptedPcmFrames)
            assertEquals(8L, sink.snapshot().acceptedPcmSamples)
            assertEquals(1L, sink.snapshot().finalizedSegments)
        } finally {
            sink.close()
            sink.join()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `discontinuity marks local media incomplete without inserting samples`() = runBlocking {
        val root = Files.createTempDirectory("voica-local-audio-gap").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = CompletableDeferred<RealtimeLocalAudioResult>()
        val sink =
            RealtimeLocalAudioSideSink(
                scope,
                RealtimeLocalAudioTargetFactory { File(root, "gap-$it.wav") },
                resultHandler = { result.complete(it) },
                queueCapacity = 8,
            )
        try {
            sink.offer(StreamingAudioEvent.SessionStarted(3))
            sink.offer(StreamingAudioEvent.EpochStarted(3, 1, RealtimeCanonicalEpochStartReason.SESSION_START))
            sink.offer(StreamingAudioEvent.PcmFrame(frame(3, 1, 0)))
            sink.offer(
                StreamingAudioEvent.Discontinuity(
                    sessionId = 3,
                    epochId = 1,
                    resumeAtSampleIndex = 100,
                    reason = StreamingAudioDiscontinuityReason.UPSTREAM_BACKPRESSURE,
                    droppedFrames = 2,
                    droppedSamples = 8,
                ),
            )
            sink.offer(StreamingAudioEvent.PcmFrame(frame(3, 1, 100)))
            sink.offer(
                StreamingAudioEvent.SessionEnded(
                    sessionId = 3,
                    finalEpochId = 1,
                    finalSampleIndexExclusive = 104,
                    reason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                    drainTimedOut = false,
                ),
            )

            val finalized = withTimeout(5_000) { result.await() } as RealtimeLocalAudioResult.Finalized
            assertTrue(finalized.media.hadDiscontinuity)
            assertEquals(8L, finalized.media.sampleCount)
            assertEquals(60L, finalized.media.sizeBytes)
        } finally {
            sink.close()
            sink.join()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `queue overflow degrades sink and salvages only accepted prefix`() = runBlocking {
        val root = Files.createTempDirectory("voica-local-audio-overflow").toFile()
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val result = CompletableDeferred<RealtimeLocalAudioResult>()
        val sink =
            RealtimeLocalAudioSideSink(
                scope = scope,
                targetFactory = RealtimeLocalAudioTargetFactory { File(root, "overflow-$it.wav") },
                resultHandler = { result.complete(it) },
                queueCapacity = 1,
                workerDispatcher = dispatcher,
            )
        try {
            assertEquals(StreamingAudioSideSinkOfferResult.ACCEPTED, sink.offer(StreamingAudioEvent.SessionStarted(9)))
            assertEquals(
                StreamingAudioSideSinkOfferResult.REJECTED,
                sink.offer(StreamingAudioEvent.EpochStarted(9, 1, RealtimeCanonicalEpochStartReason.SESSION_START)),
            )
            assertEquals(StreamingAudioSideSinkOfferResult.REJECTED, sink.offer(StreamingAudioEvent.PcmFrame(frame(9, 1, 0))))
            assertTrue(sink.snapshot().degraded)
            assertEquals(RealtimeLocalAudioFailureCode.QUEUE_OVERFLOW, sink.snapshot().lastFailureCode)

            dispatcher.runAll()
            val failed = withTimeout(5_000) { result.await() } as RealtimeLocalAudioResult.Failed
            assertEquals(RealtimeLocalAudioFailureCode.QUEUE_OVERFLOW, failed.code)
            assertEquals(9L, failed.canonicalSessionId)
            assertNotNull(failed.salvagedMedia)
            assertEquals(0L, failed.salvagedMedia?.sampleCount)
            assertTrue(sink.snapshot().salvagedFailedSegments >= 1L)
        } finally {
            sink.close()
            dispatcher.runAll()
            sink.join()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private fun frame(sessionId: Long, epochId: Long, start: Long) =
        RealtimeCanonicalPcmFrame(
            sessionId = sessionId,
            epochId = epochId,
            startSampleIndex = start,
            samples = shortArrayOf(10, 11, 12, 13),
            epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
        )

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            var guard = 0
            while (tasks.isNotEmpty()) {
                check(guard++ < 1_000) { "manual dispatcher did not quiesce" }
                tasks.removeFirst().run()
            }
        }
    }
}
