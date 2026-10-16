package io.github.ioannes78.voica

/**
 * Central compatibility registry for the physical Android notification channels that already ship.
 *
 * Channel ids are user-controlled Android settings once created, so Stage 16A.1.5 must not churn
 * them merely to make the internal taxonomy look cleaner. This registry describes presentation
 * ownership only; it does not own any foreground-service or task lifecycle.
 */
internal enum class VoicaNotificationCategory {
    RECORDING_AND_REALTIME,
    BACKGROUND_PROCESSING,
    COMPLETION,
    NEEDS_ATTENTION,
    PLAYBACK,
}

internal data class VoicaNotificationChannelSpec(
    val id: String,
    val category: VoicaNotificationCategory,
)

internal object VoicaNotificationChannels {
    const val DEVICE_SESSION = "voica-device-session"
    const val PHONE_MICROPHONE = "voica-phone-microphone"
    const val PLAYBACK = "voica-playback"
    const val MEDIA_PROCESSING = "voica-media-processing"
    const val AI_SUMMARY = "voica-ai-summary"
    const val MODEL_INSTALL = "model-install"
    const val TASK_RESULT = "voica-task-result"
    const val TASK_ATTENTION = "voica-task-attention"
    const val DIARIZATION_ATTENTION = "voica-diarization-attention"

    val all: List<VoicaNotificationChannelSpec> =
        listOf(
            VoicaNotificationChannelSpec(
                id = DEVICE_SESSION,
                category = VoicaNotificationCategory.RECORDING_AND_REALTIME,
            ),
            VoicaNotificationChannelSpec(
                id = PHONE_MICROPHONE,
                category = VoicaNotificationCategory.RECORDING_AND_REALTIME,
            ),
            VoicaNotificationChannelSpec(
                id = PLAYBACK,
                category = VoicaNotificationCategory.PLAYBACK,
            ),
            VoicaNotificationChannelSpec(
                id = MEDIA_PROCESSING,
                category = VoicaNotificationCategory.BACKGROUND_PROCESSING,
            ),
            VoicaNotificationChannelSpec(
                id = AI_SUMMARY,
                category = VoicaNotificationCategory.BACKGROUND_PROCESSING,
            ),
            VoicaNotificationChannelSpec(
                id = MODEL_INSTALL,
                category = VoicaNotificationCategory.BACKGROUND_PROCESSING,
            ),
            VoicaNotificationChannelSpec(
                id = TASK_RESULT,
                category = VoicaNotificationCategory.COMPLETION,
            ),
            VoicaNotificationChannelSpec(
                id = TASK_ATTENTION,
                category = VoicaNotificationCategory.NEEDS_ATTENTION,
            ),
            VoicaNotificationChannelSpec(
                id = DIARIZATION_ATTENTION,
                category = VoicaNotificationCategory.NEEDS_ATTENTION,
            ),
        )
}
