package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity
import io.github.ioannes78.voica.database.RealtimeMediaSegmentStateValue
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.transcript.RealtimeProcessedCanonicalBoundary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTimelineAnnotationCaptureTest {
    @Test
    fun recorderReconnectUsesCurrentSegmentLocalSampleWithoutConcatenatingPreviousSegment() {
        val boundary =
            recorderRealtimeAnnotationBoundary(
                runId = RUN_ID,
                boundary =
                    RealtimeProcessedCanonicalBoundary(
                        sessionId = 22L,
                        epochId = 5L,
                        endSampleIndexExclusive = 6_000L,
                    ),
            )
        requireNotNull(boundary)

        val target =
            buildRealtimeAnnotationCaptureTarget(
                boundary = boundary,
                segments =
                    listOf(
                        segment(
                            id = "part-01",
                            index = 0,
                            sessionId = 11L,
                            sampleCount = 10_000L,
                        ),
                        segment(
                            id = "part-02",
                            index = 1,
                            sessionId = 22L,
                            sampleCount = 6_000L,
                        ),
                    ),
            )

        requireNotNull(target)
        val owner = target.owner
        assertEquals(TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN, owner.kind)
        assertEquals(RUN_ID, owner.ownerId)
        assertEquals(TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT, target.anchor.domain)
        assertEquals(6_000L, target.anchor.sampleIndex)
        assertEquals("part-02", target.anchor.realtimeSegmentIdSnapshot)
        assertEquals(1, target.anchor.realtimeSegmentIndexSnapshot)
        assertEquals(22L, target.anchor.canonicalSessionIdSnapshot)
        assertEquals(5L, target.anchor.canonicalEpochIdSnapshot)
        assertTrue(
            "A reconnect annotation must never synthesize part01 + gap + part02 sample coordinates",
            target.anchor.sampleIndex != 16_000L,
        )
    }

    @Test
    fun phoneUsesFormalWavWrittenSamplesAndPhoneCapturePendingOwner() {
        val boundary =
            phoneRealtimeAnnotationBoundary(
                runId = RUN_ID,
                recording =
                    PhoneMicrophoneRecordingSnapshot(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        recordingId = RECORDING_ID,
                        canonicalSessionId = 33L,
                        activeEpochId = 4L,
                        writtenSamples = 48_000L,
                    ),
            )
        requireNotNull(boundary)
        assertEquals(48_000L, boundary.sampleIndex)

        val target =
            buildRealtimeAnnotationCaptureTarget(
                boundary = boundary,
                segments = listOf(segment("phone-segment", 0, 33L, 0L)),
            )

        requireNotNull(target)
        assertEquals(TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE, target.owner.kind)
        assertEquals(RECORDING_ID, target.owner.ownerId)
        assertEquals(48_000L, target.anchor.sampleIndex)
        assertEquals("phone-segment", target.anchor.realtimeSegmentIdSnapshot)
        assertEquals(33L, target.anchor.canonicalSessionIdSnapshot)
        assertEquals(4L, target.anchor.canonicalEpochIdSnapshot)
    }

    @Test
    fun targetRejectsMissingExactSessionInsteadOfGuessingAnotherSegment() {
        val boundary =
            RealtimeAnnotationRuntimeBoundary(
                source = RealtimeAnnotationCaptureSource.RECORDER_CARD,
                runId = RUN_ID,
                pendingRecordingId = null,
                canonicalSessionId = 99L,
                canonicalEpochId = 7L,
                sampleIndex = 1_000L,
            )

        assertNull(
            buildRealtimeAnnotationCaptureTarget(
                boundary = boundary,
                segments = listOf(segment("different-session", 0, 98L, 20_000L)),
            ),
        )
    }

    @Test
    fun phoneBoundaryRejectsMissingIdentityOrZeroAudio() {
        assertNull(
            phoneRealtimeAnnotationBoundary(
                runId = RUN_ID,
                recording =
                    PhoneMicrophoneRecordingSnapshot(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        recordingId = null,
                        canonicalSessionId = 1L,
                        activeEpochId = 1L,
                        writtenSamples = 1_000L,
                    ),
            ),
        )
        assertNull(
            phoneRealtimeAnnotationBoundary(
                runId = RUN_ID,
                recording =
                    PhoneMicrophoneRecordingSnapshot(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        recordingId = RECORDING_ID,
                        canonicalSessionId = 1L,
                        activeEpochId = 1L,
                        writtenSamples = 0L,
                    ),
            ),
        )
    }

    @Test
    fun runtimePromotionWiringPreservesPendingUntilDurableRecordingAttach() {
        val phone = appProductionSource("PhoneMicrophoneRealtimeRuntime.kt")
        val recorder = appProductionSource("Stage16ARecordingLifecycleCoordinator.kt")
        val container = appProductionSource("VoicaApplication.kt")

        assertTrue(phone.contains("run.persistence.attachRecording(saved.recordingId)"))
        assertTrue(phone.contains("TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE"))
        assertTrue(phone.contains("timelineAnnotationRepository?.attachPendingOwner("))
        assertTrue(recorder.contains("realtimeRepository.attachRecording(runId, recordingId, clockMs())"))
        assertTrue(recorder.contains("TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN"))
        assertTrue(recorder.contains("timelineAnnotationRepository?.attachPendingOwner("))
        assertTrue(container.contains("RealtimeTimelineAnnotationCaptureResolver("))
        assertTrue(container.contains("timelineAnnotationRepository.reconcilePending()"))
    }

    private fun segment(
        id: String,
        index: Int,
        sessionId: Long,
        sampleCount: Long,
    ) =
        RealtimeMediaSegmentEntity(
            id = id,
            runId = RUN_ID,
            segmentIndex = index,
            transportGeneration = index.toLong(),
            canonicalSessionId = sessionId,
            state = RealtimeMediaSegmentStateValue.FINALIZED,
            startedAtMs = 1_000L + index,
            endedAtMs = 2_000L + index,
            endReason = "TEST",
            canonicalSampleCount = sampleCount,
            localRelativePath = null,
            localFilename = null,
            deviceFilename = null,
            sizeBytes = null,
            sha256 = null,
            isCompleteDeviceRecording = false,
        )

    private fun appProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app production source not found: $relative")
    }

    private companion object {
        const val RUN_ID = "run-annotation"
        const val RECORDING_ID = "phone-recording"
    }
}
