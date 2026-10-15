package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.PhoneMicrophoneProductTarget
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination

/**
 * Presentation-only description of destinations that already exist in Voica.
 *
 * Stage 16A.1.5 does not introduce a second navigation stack. Notification/global UI adapters use
 * these values only to choose one of the existing AppRecordingNavigation, AppRealtimeNavigation or
 * AppPhoneMicrophoneNavigation entry points.
 */
internal sealed interface AppActivityDestination {
    data object DeviceRealtime : AppActivityDestination

    data class PhoneMicrophone(
        val target: PhoneMicrophoneProductTarget,
    ) : AppActivityDestination

    data class RecordingDetail(
        val recordingId: String,
        val destination: RecordingDetailDestination,
    ) : AppActivityDestination {
        init {
            require(recordingId.isNotBlank()) { "recordingId must not be blank" }
        }
    }
}
