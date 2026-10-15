package io.github.ioannes78.voica.ui.device

import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingUiState
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityLifecycleClass
import io.github.ioannes78.voica.ui.status.AppActivitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActivityProjectionTest {
    @Test
    fun `phone recording is primary when it is the only live activity`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording = RecordingDeviceState(status = RecordingStatus.Idle),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording = phoneRecording(PhoneMicrophoneRecordingPhase.RECORDING, durationMs = 12_000L),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        assertEquals(AppActivityKind.RECORDING, projection.primaryActivity?.kind)
        assertEquals(AppActivitySource.PHONE_MICROPHONE, projection.primaryActivity?.source)
        assertEquals(setOf(AppActivityAction.PAUSE, AppActivityAction.STOP), projection.primaryActivity?.availableActions)
        assertTrue(projection.secondaryActivities.isEmpty())
    }

    @Test
    fun `phone realtime collapses underlying phone recording into one live activity`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording = RecordingDeviceState(status = RecordingStatus.Idle),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording =
                    phoneRecording(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        durationMs = 34_000L,
                        ownedByRealtime = true,
                    ),
                phoneRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.ACTIVE),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        assertEquals(AppActivityKind.REALTIME_TRANSCRIPTION, projection.primaryActivity?.kind)
        assertEquals(AppActivitySource.PHONE_MICROPHONE, projection.primaryActivity?.source)
        assertTrue(projection.secondaryActivities.isEmpty())
    }

    @Test
    fun `simultaneous device and phone activity has one primary and one secondary`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording =
                    RecordingDeviceState(
                        status = RecordingStatus.Recording,
                        durationSeconds = 90,
                    ),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording = phoneRecording(PhoneMicrophoneRecordingPhase.RECORDING, durationMs = 45_000L),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        assertEquals(AppActivitySource.PHONE_MICROPHONE, projection.primaryActivity?.source)
        assertEquals(1, projection.secondaryActivities.size)
        assertEquals(AppActivitySource.RECORDER_CARD, projection.secondaryActivities.single().source)
    }

    @Test
    fun `device realtime collapses physical recording and remains the primary product`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording =
                    RecordingDeviceState(
                        status = RecordingStatus.Recording,
                        durationSeconds = 123,
                    ),
                deviceRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.ACTIVE),
                phoneRecording = PhoneMicrophoneRecordingUiState(),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.RECORDER_CARD,
            )

        assertEquals(AppActivityKind.REALTIME_TRANSCRIPTION, projection.primaryActivity?.kind)
        assertEquals(AppActivitySource.RECORDER_CARD, projection.primaryActivity?.source)
        assertTrue(projection.secondaryActivities.isEmpty())
    }

    @Test
    fun `asr stopped while phone formal recording continues is still an active primary`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording = RecordingDeviceState(status = RecordingStatus.Idle),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording =
                    phoneRecording(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        durationMs = 61_000L,
                        ownedByRealtime = true,
                    ),
                phoneRealtime =
                    Stage16ARealtimeUiState(
                        phase = Stage16ARealtimeUiPhase.ACTIVE,
                        recordingOnlyMode = true,
                    ),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        assertEquals(AppActivityLifecycleClass.ACTIVE_CONTROLLABLE, projection.primaryActivity?.lifecycleClass)
        assertEquals(AppActivityKind.REALTIME_TRANSCRIPTION, projection.primaryActivity?.kind)
        assertEquals("录音继续中", projection.primaryActivity?.title)
        assertTrue(projection.attentionItems.isEmpty())
    }

    @Test
    fun `device realtime interruption does not hide an independently continuing physical recording`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording =
                    RecordingDeviceState(
                        status = RecordingStatus.Recording,
                        durationSeconds = 200,
                    ),
                deviceRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.INTERRUPTED),
                phoneRecording = PhoneMicrophoneRecordingUiState(),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.RECORDER_CARD,
            )

        assertEquals(AppActivityLifecycleClass.ACTIVE_CONTROLLABLE, projection.primaryActivity?.lifecycleClass)
        assertEquals("录音继续中", projection.primaryActivity?.title)
        assertTrue(projection.attentionItems.isEmpty())
    }

    @Test
    fun `realtime interruption without a live recording becomes attention instead of fake activity`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording = RecordingDeviceState(status = RecordingStatus.Idle),
                deviceRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.INTERRUPTED),
                phoneRecording = PhoneMicrophoneRecordingUiState(),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.RECORDER_CARD,
            )

        assertEquals(null, projection.primaryActivity)
        assertEquals(1, projection.attentionItems.size)
        assertEquals(AppActivityLifecycleClass.INTERRUPTED_ATTENTION, projection.attentionItems.single().lifecycleClass)
    }

    private fun phoneRecording(
        phase: PhoneMicrophoneRecordingPhase,
        durationMs: Long,
        ownedByRealtime: Boolean = false,
    ): PhoneMicrophoneRecordingUiState =
        PhoneMicrophoneRecordingUiState(
            recording =
                PhoneMicrophoneRecordingSnapshot(
                    phase = phase,
                    writtenSamples = durationMs * TEST_SAMPLES_PER_MILLISECOND,
                ),
            ownedByRealtime = ownedByRealtime,
        )

    private companion object {
        const val TEST_SAMPLES_PER_MILLISECOND = 16L
    }
}
