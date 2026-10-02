package io.github.ioannes78.voica.ui.transcript

import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.transcript.TimelineTranscriptSegmentInput
import io.github.ioannes78.voica.transcript.TokenSource
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptToken
import io.github.ioannes78.voica.transcript.buildTranscriptTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPlaybackSyncStateMachineTest {
    @Test
    fun mapsOnlyCompatiblePlaybackSource() {
        val machine = TranscriptPlaybackSyncStateMachine()
        val timeline = timeline("t1", "s1", "a1")

        val bound =
            machine.bind(
                timeline = timeline,
                compatiblePlaybackAssetId = "a1",
                playback = snapshot("r1", "a1", 4_000L),
            )

        assertTrue(bound.syncAvailable)
        assertTrue(bound.playbackCompatible)
        assertEquals("segment:s1", bound.activeRowId)
        assertEquals("segment:s1:FIRST_PASS:0", bound.activeCueId)

        val mismatched = machine.update(snapshot("r1", "different", 4_000L))
        assertFalse(mismatched.playbackCompatible)
        assertNull(mismatched.activeRowId)
        assertNull(mismatched.activeCueId)
    }

    @Test
    fun versionBindingRemapsCurrentSampleWithoutRestartingPlayback() {
        val machine = TranscriptPlaybackSyncStateMachine()
        val playback = snapshot("r1", "a1", 5_000L)

        val first =
            machine.bind(
                timeline = timeline("t1", "first", "a1"),
                compatiblePlaybackAssetId = "a1",
                playback = playback,
            )
        val second =
            machine.bind(
                timeline = timeline("t2", "second", "a1"),
                compatiblePlaybackAssetId = "a1",
                playback = playback,
            )

        assertEquals("segment:first", first.activeRowId)
        assertEquals("segment:second", second.activeRowId)
        assertTrue(second.timelineGeneration > first.timelineGeneration)
    }

    @Test
    fun pauseAndSpeedChangesKeepMappingAtPresentedSample() {
        val machine = TranscriptPlaybackSyncStateMachine()
        val timeline = timeline("t1", "s1", "a1")
        machine.bind(
            timeline = timeline,
            compatiblePlaybackAssetId = "a1",
            playback = snapshot("r1", "a1", 6_000L),
        )

        val paused =
            machine.update(
                snapshot(
                    recordingId = "r1",
                    assetId = "a1",
                    position = 6_000L,
                    state = PlaybackState.PAUSED,
                    speed = 2.0f,
                ),
            )

        assertEquals("segment:s1", paused.activeRowId)
        assertEquals("segment:s1:FIRST_PASS:0", paused.activeCueId)
        assertTrue(paused.playbackCompatible)
    }

    @Test
    fun discontinuityRemapsImmediatelyAndInvalidatesOldRow() {
        val machine = TranscriptPlaybackSyncStateMachine()
        val timeline = twoRowTimeline()
        machine.bind(
            timeline = timeline,
            compatiblePlaybackAssetId = "a1",
            playback = snapshot("r1", "a1", 2_000L),
        )

        val afterSeek =
            machine.update(
                snapshot(
                    recordingId = "r1",
                    assetId = "a1",
                    position = 20_000L,
                    discontinuityGeneration = 3L,
                    durationSampleCount = 32_000L,
                ),
            )

        assertEquals("segment:second", afterSeek.activeRowId)
        assertEquals(3L, afterSeek.discontinuityGeneration)
    }

    @Test
    fun completedPositionAtTotalSampleCountHasNoActiveText() {
        val machine = TranscriptPlaybackSyncStateMachine()
        val timeline = timeline("t1", "s1", "a1")
        machine.bind(
            timeline = timeline,
            compatiblePlaybackAssetId = "a1",
            playback = snapshot("r1", "a1", 15_999L),
        )

        val completed =
            machine.update(
                snapshot(
                    recordingId = "r1",
                    assetId = "a1",
                    position = 16_000L,
                    state = PlaybackState.COMPLETED,
                ),
            )

        assertNull(completed.activeRowId)
        assertNull(completed.activeCueId)
        assertTrue(completed.playbackCompatible)
    }

    @Test
    fun manualScrollSuspendsFollowUntilExplicitResume() {
        val machine = TranscriptPlaybackSyncStateMachine()

        assertEquals(
            TranscriptFollowMode.USER_SUSPENDED,
            machine.suspendFollowing().followMode,
        )
        assertEquals(
            TranscriptFollowMode.USER_SUSPENDED,
            machine.update(PlaybackSnapshot()).followMode,
        )
        assertEquals(
            TranscriptFollowMode.FOLLOWING,
            machine.resumeFollowing().followMode,
        )
    }

    private fun timeline(
        transcriptionId: String,
        segmentId: String,
        assetId: String,
    ) =
        buildTranscriptTimeline(
            recordingId = "r1",
            transcriptionId = transcriptionId,
            alignmentId = null,
            sourceCanonicalAssetId = assetId,
            sourceCanonicalSha256 = "sha",
            canonicalProfileId = "canonical",
            totalSampleCount = 16_000L,
            segments =
                listOf(
                    input(
                        id = segmentId,
                        index = 0,
                        start = 0L,
                        end = 16_000L,
                    ),
                ),
        )

    private fun twoRowTimeline() =
        buildTranscriptTimeline(
            recordingId = "r1",
            transcriptionId = "t1",
            alignmentId = null,
            sourceCanonicalAssetId = "a1",
            sourceCanonicalSha256 = "sha",
            canonicalProfileId = "canonical",
            totalSampleCount = 32_000L,
            segments =
                listOf(
                    input("first", 0, 0L, 16_000L),
                    input("second", 1, 16_000L, 32_000L),
                ),
        )

    private fun input(
        id: String,
        index: Int,
        start: Long,
        end: Long,
    ) =
        TimelineTranscriptSegmentInput(
            id = id,
            segment =
                TranscriptSegment(
                    segmentIndex = index,
                    startSampleIndex = start,
                    endSampleIndexExclusive = end,
                    firstPassRawText = "你好",
                    finalText = "你好。",
                    tokens =
                        listOf(
                            TranscriptToken(
                                text = "你好",
                                startSampleIndex = start,
                                endSampleIndexExclusive = end,
                                source = TokenSource.FIRST_PASS,
                            ),
                        ),
                ),
        )

    private fun snapshot(
        recordingId: String,
        assetId: String,
        position: Long,
        state: PlaybackState = PlaybackState.PLAYING,
        speed: Float = 1.0f,
        discontinuityGeneration: Long = 0L,
        durationSampleCount: Long = 16_000L,
    ) =
        PlaybackSnapshot(
            recordingId = recordingId,
            sourceAssetId = assetId,
            state = state,
            positionSampleIndex = position,
            durationSampleCount = durationSampleCount,
            speed = speed,
            discontinuityGeneration = discontinuityGeneration,
        )
}
