package io.github.ioannes78.voica

/**
 * QA5 product selection helpers. They intentionally resolve through the existing internal
 * choice enums so Room/transcription contracts do not need a naming migration in this stage.
 */
internal fun LocalSpeechSettings.selectedOfflineModelId(): String =
    offlineAsrQuality.preferredModelIds().first()

internal fun LocalSpeechSettings.selectedRealtimeModelId(): String =
    realtimeAsrModel.preferredModelIds().first()
