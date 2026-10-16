package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.RealtimeAsrModelChoice
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.protocol.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveActivityStripProjectionTest {
    @Test
    fun `recorder realtime uses realtime semantics instead of backing recording`() {
        val item =
            resolveLiveActivityStrip(
                deviceRecording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 18,
                ),
                deviceRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.ACTIVE),
                phoneRecording = PhoneMicrophoneRecordingSnapshot(),
                phoneRealtime = Stage16ARealtimeUiState(),
                visibleRealtimeSource = null,
            )

        requireNotNull(item)
        assertEquals(AppActivitySource.RECORDER_CARD, item.source)
        assertEquals(LiveActivityStripKind.REALTIME, item.kind)
        assertEquals("实时转写中", item.statusLabel)
        assertEquals("QS668 / CB08", item.sourceLabel)
        assertEquals(18_000L, item.elapsedMs)
    }

    @Test
    fun `exact visible recorder realtime is suppressed while concurrent phone recording remains`() {
        val item =
            resolveLiveActivityStrip(
                deviceRecording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 18,
                ),
                deviceRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.ACTIVE),
                phoneRecording = PhoneMicrophoneRecordingSnapshot(
                    phase = PhoneMicrophoneRecordingPhase.RECORDING,
                    writtenSamples = 16_000L * 7L,
                ),
                phoneRealtime = Stage16ARealtimeUiState(),
                visibleRealtimeSource = AppActivitySource.RECORDER_CARD,
            )

        requireNotNull(item)
        assertEquals(AppActivitySource.PHONE_MICROPHONE, item.source)
        assertEquals(LiveActivityStripKind.RECORDING, item.kind)
        assertEquals("正在录音", item.statusLabel)
        assertEquals("手机", item.sourceLabel)
        assertEquals(7_000L, item.elapsedMs)
    }

    @Test
    fun `visible phone realtime with no other activity produces no strip`() {
        val item =
            resolveLiveActivityStrip(
                deviceRecording = RecordingDeviceState(),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording = PhoneMicrophoneRecordingSnapshot(
                    phase = PhoneMicrophoneRecordingPhase.RECORDING,
                    writtenSamples = 16_000L * 5L,
                ),
                phoneRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.ACTIVE),
                visibleRealtimeSource = AppActivitySource.PHONE_MICROPHONE,
            )

        assertNull(item)
    }

    @Test
    fun `paused phone realtime keeps realtime paused wording`() {
        val item =
            resolveLiveActivityStrip(
                deviceRecording = RecordingDeviceState(),
                deviceRealtime = Stage16ARealtimeUiState(),
                phoneRecording = PhoneMicrophoneRecordingSnapshot(
                    phase = PhoneMicrophoneRecordingPhase.PAUSED,
                    writtenSamples = 16_000L * 9L,
                ),
                phoneRealtime = Stage16ARealtimeUiState(phase = Stage16ARealtimeUiPhase.PAUSED),
                visibleRealtimeSource = null,
            )

        requireNotNull(item)
        assertEquals(LiveActivityStripKind.REALTIME, item.kind)
        assertEquals(LiveActivityStripPhase.PAUSED, item.phase)
        assertEquals("实时转写已暂停", item.statusLabel)
        assertEquals(9_000L, item.elapsedMs)
    }

    @Test
    fun `recording only degraded realtime falls back to recording semantics`() {
        val item =
            resolveLiveActivityStrip(
                deviceRecording = RecordingDeviceState(
                    status = RecordingStatus.Recording,
                    durationSeconds = 12,
                ),
                deviceRealtime = Stage16ARealtimeUiState(
                    phase = Stage16ARealtimeUiPhase.ACTIVE,
                    recordingOnlyMode = true,
                ),
                phoneRecording = PhoneMicrophoneRecordingSnapshot(),
                phoneRealtime = Stage16ARealtimeUiState(),
                visibleRealtimeSource = null,
            )

        requireNotNull(item)
        assertEquals(LiveActivityStripKind.RECORDING, item.kind)
        assertEquals("正在录音", item.statusLabel)
    }

    @Test
    fun `configured realtime model names match product choices`() {
        assertEquals("Small Bilingual", RealtimeAsrModelChoice.SMALL_BILINGUAL.productDisplayName())
        assertEquals("Small Bilingual", RealtimeAsrModelChoice.AUTO.productDisplayName())
        assertEquals("Small Bilingual", RealtimeAsrModelChoice.CHINESE_LARGE_TRANSDUCER.productDisplayName())
        assertEquals("Chinese Large CTC", RealtimeAsrModelChoice.CHINESE_LARGE_CTC.productDisplayName())
    }
}
