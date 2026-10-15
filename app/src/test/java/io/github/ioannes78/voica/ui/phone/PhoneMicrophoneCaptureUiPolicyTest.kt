package io.github.ioannes78.voica.ui.phone

import io.github.ioannes78.voica.PhoneMicrophoneCapturePhase
import io.github.ioannes78.voica.PhoneMicrophoneCaptureSnapshot
import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneCaptureUiPolicyTest {
    @Test
    fun `active failed capture shows recovery guidance and detail`() {
        val message =
            phoneMicrophoneCaptureInterruptionMessage(
                recording = PhoneMicrophoneRecordingSnapshot(phase = PhoneMicrophoneRecordingPhase.RECORDING),
                capture =
                    PhoneMicrophoneCaptureSnapshot(
                        phase = PhoneMicrophoneCapturePhase.FAILED,
                        lastFailure = "AudioRecord.read returned -3",
                    ),
            )

        assertTrue(checkNotNull(message).contains("可先暂停后继续"))
        assertTrue(message.contains("AudioRecord.read returned -3"))
    }

    @Test
    fun `healthy active capture does not show interruption guidance`() {
        assertNull(
            phoneMicrophoneCaptureInterruptionMessage(
                recording = PhoneMicrophoneRecordingSnapshot(phase = PhoneMicrophoneRecordingPhase.RECORDING),
                capture = PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.RECORDING),
            ),
        )
    }

    @Test
    fun `paused recording does not report stale capture stopped state as failure`() {
        assertNull(
            phoneMicrophoneCaptureInterruptionMessage(
                recording = PhoneMicrophoneRecordingSnapshot(phase = PhoneMicrophoneRecordingPhase.PAUSED),
                capture = PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.STOPPED),
            ),
        )
    }
}
