package io.github.ioannes78.voica.ui.playback

import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationEntity
import io.github.ioannes78.voica.database.TimelineAnnotationOwnershipStateValue
import io.github.ioannes78.voica.database.TimelineAnnotationSeekResolution
import io.github.ioannes78.voica.database.TimelineAnnotationSeekUnavailableReason
import io.github.ioannes78.voica.database.TimelinePlaybackMediaIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineAnnotationPlaybackCoordinatorTest {
    @Test
    fun playingRecordingSeeksWithoutExplicitTransportChange() = runBlocking {
        val controller = FakeController("rec", PlaybackState.PLAYING)
        val coordinator = exactCoordinator(controller, resolvedSample = 4_000L)

        val result = coordinator.seek(annotation("rec", 4_000L))

        assertEquals(listOf("seek:4000"), controller.events)
        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)
        assertTrue(result is TimelineAnnotationSeekResolution.Exact)
    }

    @Test
    fun pausedRecordingSeeksWithoutCallingPlayOrPause() = runBlocking {
        val controller = FakeController("rec", PlaybackState.PAUSED)
        val coordinator = exactCoordinator(controller, resolvedSample = 8_000L)

        coordinator.seek(annotation("rec", 8_000L))

        assertEquals(listOf("seek:8000"), controller.events)
        assertEquals(PlaybackState.PAUSED, controller.snapshot.value.state)
    }

    @Test
    fun readyRecordingSeeksWithoutAutoPlay() = runBlocking {
        val controller = FakeController("rec", PlaybackState.READY)
        val coordinator = exactCoordinator(controller, resolvedSample = 12_000L)

        coordinator.seek(annotation("rec", 12_000L))

        assertEquals(listOf("seek:12000"), controller.events)
        assertEquals(PlaybackState.READY, controller.snapshot.value.state)
    }

    @Test
    fun differentRecordingLoadsThenSeeksButDoesNotPlay() = runBlocking {
        val controller = FakeController("other", PlaybackState.PLAYING)
        val coordinator = exactCoordinator(controller, resolvedSample = 2_000L)

        coordinator.seek(annotation("rec", 2_000L))

        assertEquals(listOf("load:rec", "seek:2000"), controller.events)
        assertEquals("rec", controller.snapshot.value.recordingId)
        assertEquals(PlaybackState.READY, controller.snapshot.value.state)
    }

    @Test
    fun unavailableResolutionNeverSeeks() = runBlocking {
        val controller = FakeController("rec", PlaybackState.PAUSED)
        val coordinator =
            TimelineAnnotationPlaybackCoordinator(controller) { _, _ ->
                TimelineAnnotationSeekResolution.Unavailable(
                    TimelineAnnotationSeekUnavailableReason.CANONICAL_SHA_MISMATCH,
                )
            }

        val result = coordinator.seek(annotation("rec", 5_000L))

        assertEquals(emptyList<String>(), controller.events)
        assertEquals(
            TimelineAnnotationSeekUnavailableReason.CANONICAL_SHA_MISMATCH,
            (result as TimelineAnnotationSeekResolution.Unavailable).reason,
        )
    }

    @Test
    fun pendingAnnotationNeverLoadsOrSeeks() = runBlocking {
        val controller = FakeController(null, PlaybackState.IDLE)
        val coordinator = exactCoordinator(controller, resolvedSample = 1_000L)
        val pending = annotation(recordingId = null, sampleIndex = 1_000L)

        val result = coordinator.seek(pending)

        assertEquals(emptyList<String>(), controller.events)
        assertEquals(
            TimelineAnnotationSeekUnavailableReason.PENDING_SESSION,
            (result as TimelineAnnotationSeekResolution.Unavailable).reason,
        )
    }

    private fun exactCoordinator(
        controller: FakeController,
        resolvedSample: Long,
    ): TimelineAnnotationPlaybackCoordinator =
        TimelineAnnotationPlaybackCoordinator(controller) { _, identity ->
            assertEquals("asset-rec", identity.sourceAssetId)
            assertEquals(32_000L, identity.durationSampleCount)
            TimelineAnnotationSeekResolution.Exact(resolvedSample)
        }

    private fun annotation(
        recordingId: String?,
        sampleIndex: Long,
    ): TimelineAnnotationEntity =
        TimelineAnnotationEntity(
            id = "annotation-1",
            recordingId = recordingId,
            ownershipState =
                if (recordingId == null) {
                    "PENDING_SESSION"
                } else {
                    TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING
                },
            pendingOwnerKind = null,
            pendingOwnerId = null,
            anchorDomain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
            anchorSampleIndex = sampleIndex,
            anchorCanonicalAssetIdSnapshot = "asset-rec",
            anchorCanonicalSha256Snapshot = "a".repeat(64),
            anchorCanonicalProfileIdSnapshot = "CANONICAL_PCM16_MONO_16K_V1",
            anchorRealtimeRunIdSnapshot = null,
            anchorRealtimeSegmentIdSnapshot = null,
            anchorRealtimeSegmentIndexSnapshot = null,
            anchorCanonicalSessionIdSnapshot = null,
            anchorCanonicalEpochIdSnapshot = null,
            noteText = null,
            sourceTextSnapshot = null,
            sourceTranscriptionIdSnapshot = null,
            sourceRevisionIdSnapshot = null,
            sourceTranscriptSegmentIdSnapshot = null,
            sourceTimingQualitySnapshot = null,
            speakerLabelSnapshot = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

    private class FakeController(
        recordingId: String?,
        state: PlaybackState,
    ) : PlaybackController {
        private val mutable =
            MutableStateFlow(
                PlaybackSnapshot(
                    recordingId = recordingId,
                    sourceAssetId = recordingId?.let { "asset-$it" },
                    state = state,
                    durationSampleCount = recordingId?.let { 32_000L } ?: 0L,
                ),
            )
        val events = mutableListOf<String>()

        override val snapshot: StateFlow<PlaybackSnapshot> = mutable
        override val stateFlow: StateFlow<PlaybackState> = MutableStateFlow(state)
        override val positionFlow: StateFlow<Long> = MutableStateFlow(0L)

        override suspend fun load(recordingId: String) {
            events += "load:$recordingId"
            mutable.value =
                PlaybackSnapshot(
                    recordingId = recordingId,
                    sourceAssetId = "asset-$recordingId",
                    state = PlaybackState.READY,
                    durationSampleCount = 32_000L,
                )
        }

        override suspend fun play() {
            events += "play"
            mutable.value = mutable.value.copy(state = PlaybackState.PLAYING)
        }

        override suspend fun pause() {
            events += "pause"
            mutable.value = mutable.value.copy(state = PlaybackState.PAUSED)
        }

        override suspend fun seekToSample(sampleIndex: Long) {
            events += "seek:$sampleIndex"
            mutable.value = mutable.value.copy(positionSampleIndex = sampleIndex)
        }

        override suspend fun setSpeed(speed: Float) = Unit
        override suspend fun unload() = Unit
        override suspend fun release() = Unit
    }
}
