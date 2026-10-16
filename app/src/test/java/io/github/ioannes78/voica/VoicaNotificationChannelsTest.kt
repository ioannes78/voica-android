package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicaNotificationChannelsTest {
    @Test
    fun `stage16a1_5 preserves all physical channel ids`() {
        assertEquals(
            setOf(
                "voica-device-session",
                "voica-phone-microphone",
                "voica-playback",
                "voica-media-processing",
                "voica-ai-summary",
                "model-install",
                "voica-task-result",
                "voica-task-attention",
                "voica-diarization-attention",
            ),
            VoicaNotificationChannels.all.map { it.id }.toSet(),
        )
    }

    @Test
    fun `physical channel ids stay unique while logical categories may be shared`() {
        val ids = VoicaNotificationChannels.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(
            VoicaNotificationChannels.all.count {
                it.category == VoicaNotificationCategory.RECORDING_AND_REALTIME
            } >= 2,
        )
        assertTrue(
            VoicaNotificationChannels.all.count {
                it.category == VoicaNotificationCategory.NEEDS_ATTENTION
            } >= 2,
        )
    }

    @Test
    fun `logical category mapping remains explicit without changing physical ids`() {
        assertEquals(
            mapOf(
                "voica-device-session" to VoicaNotificationCategory.RECORDING_AND_REALTIME,
                "voica-phone-microphone" to VoicaNotificationCategory.RECORDING_AND_REALTIME,
                "voica-playback" to VoicaNotificationCategory.PLAYBACK,
                "voica-media-processing" to VoicaNotificationCategory.BACKGROUND_PROCESSING,
                "voica-ai-summary" to VoicaNotificationCategory.BACKGROUND_PROCESSING,
                "model-install" to VoicaNotificationCategory.BACKGROUND_PROCESSING,
                "voica-task-result" to VoicaNotificationCategory.COMPLETION,
                "voica-task-attention" to VoicaNotificationCategory.NEEDS_ATTENTION,
                "voica-diarization-attention" to VoicaNotificationCategory.NEEDS_ATTENTION,
            ),
            VoicaNotificationChannels.all.associate { it.id to it.category },
        )
    }

    @Test
    fun `qa1 visual revision keeps notification channel topology unchanged`() {
        assertEquals(9, VoicaNotificationChannels.all.size)
        assertTrue(VoicaNotificationChannels.all.all { it.id.isNotBlank() })
    }
}
