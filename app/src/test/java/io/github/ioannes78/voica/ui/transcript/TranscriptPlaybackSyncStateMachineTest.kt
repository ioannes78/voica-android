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
                    TimelineTranscriptSegmentInput(
                        id = segmentId,
                        segment =
                            TranscriptSegment(
                                segmentIndex = 0,
                                startSampleIndex = 0L,
                                endSampleIndexExclusive = 16_000L,
                                firstPassRawText = "你好",
                                finalText = "你好。",
                                tokens =
                                    listOf(
                                        TranscriptToken(
                                            text = "你好",
                                            startSampleIndex = 0L,
                                            endSampleIndexExclusive = 16_000L,
                                            source = TokenSource.FIRST_PASS,
                                        ),
                                    ),
                            ),
                    ),
                ),
        )

    private fun snapshot(
        recordingId: String,
        assetId: String,
        position: Long,
    ) =
        PlaybackSnapshot(
            recordingId = recordingId,
            sourceAssetId = assetId,
            state = PlaybackState.PLAYING,
            positionSampleIndex = position,
            durationSampleCount = 16_000L,
        )
}
