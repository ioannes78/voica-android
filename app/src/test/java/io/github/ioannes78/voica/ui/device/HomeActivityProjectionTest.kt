package io.github.ioannes78.voica.ui.device

import io.github.ioannes78.voica.PhoneMicrophoneCapturePhase
import io.github.ioannes78.voica.PhoneMicrophoneCaptureSnapshot
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
import org.junit.Assert.assertFalse
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
    fun `phone recording projects media truth without duplicating elapsed in subtitle`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording = RecordingDeviceState(status = RecordingStatus.Idle),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording =
                    phoneRecording(
                        phase = PhoneMicrophoneRecordingPhase.RECORDING,
                        durationMs = 12_000L,
                        displayName = "phone-qa.wav",
                        levels = listOf(0.1f, 0.4f, 0.2f),
                        capturePhase = PhoneMicrophoneCapturePhase.RECORDING,
                    ),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        val primary = checkNotNull(projection.primaryActivity)
        assertEquals("手机麦克风", primary.subtitle)
        assertFalse(primary.subtitle.orEmpty().contains("00:12"))
        assertEquals(12_000L, primary.elapsedMs)
        assertEquals("phone-qa.wav", primary.mediaDisplayName)
        assertEquals(WAV_HEADER_BYTES + 12_000L * TEST_SAMPLES_PER_MILLISECOND * 2L, primary.mediaSizeBytes)
        assertEquals(listOf(0.1f, 0.4f, 0.2f), primary.audioActivityLevels)
        assertTrue(primary.waveformActive)
    }

    @Test
    fun `device recording projects filename size and elapsed separately`() {
        val projection =
            buildHomeActivityProjection(
                deviceRecording =
                    RecordingDeviceState(
                        status = RecordingStatus.Recording,
                        durationSeconds = 90,
                        filename = "note-qa.wav",
                        currentSizeBytes = 123_456L,
                    ),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording = PhoneMicrophoneRecordingUiState(),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.RECORDER_CARD,
            )

        val primary = checkNotNull(projection.primaryActivity)
        assertEquals("录音卡", primary.subtitle)
        assertFalse(primary.subtitle.orEmpty().contains("01:30"))
        assertEquals(90_000L, primary.elapsedMs)
        assertEquals("note-qa.wav", primary.mediaDisplayName)
        assertEquals(123_456L, primary.mediaSizeBytes)
        assertTrue(primary.audioActivityLevels.isEmpty())
        assertFalse(primary.waveformActive)
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
                phoneRealtime =
                    Stage16ARealtimeUiState(
                        phase = Stage16ARealtimeUiPhase.ACTIVE,
                        audioActivityLevels = listOf(0.2f, 0.6f),
                    ),
                sourcePreference = HomeActivitySourcePreference.PHONE_MICROPHONE,
            )

        assertEquals(AppActivityKind.REALTIME_TRANSCRIPTION, projection.primaryActivity?.kind)
        assertEquals(AppActivitySource.PHONE_MICROPHONE, projection.primaryActivity?.source)
        assertEquals(listOf(0.2f, 0.6f), projection.primaryActivity?.audioActivityLevels)
        assertTrue(projection.primaryActivity?.waveformActive == true)
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
                deviceRealtime =
                    Stage16ARealtimeUiState(
                        phase = Stage16ARealtimeUiPhase.ACTIVE,
                        audioActivityLevels = listOf(0.15f, 0.5f),
                    ),
                phoneRecording = PhoneMicrophoneRecordingUiState(),
                phoneRealtime = Stage16ARealtimeUiState(),
                sourcePreference = HomeActivitySourcePreference.RECORDER_CARD,
            )

        assertEquals(AppActivityKind.REALTIME_TRANSCRIPTION, projection.primaryActivity?.kind)
        assertEquals(AppActivitySource.RECORDER_CARD, projection.primaryActivity?.source)
        assertEquals(listOf(0.15f, 0.5f), projection.primaryActivity?.audioActivityLevels)
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
        assertFalse(projection.primaryActivity?.waveformActive == true)
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
        displayName: String? = null,
        levels: List<Float> = emptyList(),
        capturePhase: PhoneMicrophoneCapturePhase = PhoneMicrophoneCapturePhase.IDLE,
    ): PhoneMicrophoneRecordingUiState =
        PhoneMicrophoneRecordingUiState(
            recording =
                PhoneMicrophoneRecordingSnapshot(
                    phase = phase,
                    displayName = displayName,
                    writtenSamples = durationMs * TEST_SAMPLES_PER_MILLISECOND,
                ),
            capture = PhoneMicrophoneCaptureSnapshot(phase = capturePhase),
            audioActivityLevels = levels,
            ownedByRealtime = ownedByRealtime,
        )

    private companion object {
        const val TEST_SAMPLES_PER_MILLISECOND = 16L
        const val WAV_HEADER_BYTES = 44L
    }
}
