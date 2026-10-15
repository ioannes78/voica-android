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
}
