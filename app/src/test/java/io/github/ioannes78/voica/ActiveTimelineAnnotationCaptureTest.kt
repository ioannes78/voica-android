package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveTimelineAnnotationCaptureTest {
    @Test
    fun ordinaryPhoneUsesFinalWavGlobalSampleCoordinateAcrossPauseResume() {
        val target =
            phoneRecordingAnnotationTarget(
                PhoneMicrophoneRecordingSnapshot(
                    phase = PhoneMicrophoneRecordingPhase.RECORDING,
                    recordingId = "phone-1",
                    writtenSamples = 48_000L,
                    activeEpochSamples = 16_000L,
                ),
            )
        requireNotNull(target)
        assertEquals(TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE, target.owner.kind)
        assertEquals("phone-1", target.owner.ownerId)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, target.anchor.domain)
        assertEquals(48_000L, target.anchor.sampleIndex)
    }

    @Test
    fun ordinaryRecorderUsesOnlyFreshDeviceMediaProgressAndExactFilenameIdentity() {
        val target =
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note20261018-120000.wav",
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            )
        requireNotNull(target)
        assertEquals(TimelineAnnotationPendingOwnerKindValue.RECORDER_CAPTURE, target.owner.kind)
        assertEquals("AA:BB:CC:DD:EE:FF\u001fnote20261018-120000.wav", target.owner.ownerId)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, target.anchor.domain)
        assertEquals(192_000L, target.anchor.sampleIndex)
    }

    @Test
    fun ordinaryRecorderRejectsStaleOrIdentityLessStateInsteadOfUsingUiElapsedTime() {
        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note.wav",
                    freshness = RecordingFreshness.STALE,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = null,
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertNull(
            recorderRecordingAnnotationTarget(
                recording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                    filename = "note.wav",
                    freshness = RecordingFreshness.FRESH,
                ),
                deviceAddress = null,
            ),
        )
    }
}
