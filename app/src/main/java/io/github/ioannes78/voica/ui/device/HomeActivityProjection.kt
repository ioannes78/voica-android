package io.github.ioannes78.voica.ui.device

import io.github.ioannes78.voica.PhoneMicrophoneCapturePhase
import io.github.ioannes78.voica.PhoneMicrophoneProductTarget
import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import io.github.ioannes78.voica.ble.RecordingCommandState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingUiState
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityDestination
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityLifecycleClass
import io.github.ioannes78.voica.ui.status.AppActivityProjection
import io.github.ioannes78.voica.ui.status.AppActivityProjectionResolver
import io.github.ioannes78.voica.ui.status.AppActivitySource

internal enum class HomeActivitySourcePreference {
    RECORDER_CARD,
    PHONE_MICROPHONE,
}

/** Maps existing recording/realtime truths into the Stage 16A.1.5 presentation model. */
internal fun buildHomeActivityProjection(
    deviceRecording: RecordingDeviceState,
    deviceRealtime: Stage16ARealtimeUiState,
    phoneRecording: PhoneMicrophoneRecordingUiState,
    phoneRealtime: Stage16ARealtimeUiState,
    sourcePreference: HomeActivitySourcePreference,
): AppActivityProjection {
    val facts = buildList {
        deviceRecordingFact(deviceRecording, sourcePreference)?.let(::add)
        deviceRealtimeFact(deviceRecording, deviceRealtime, sourcePreference)?.let(::add)
        phoneRecordingFact(phoneRecording, sourcePreference)?.let(::add)
        phoneRealtimeFact(phoneRecording, phoneRealtime, sourcePreference)?.let(::add)
    }
    return AppActivityProjectionResolver.resolve(facts)
}

private fun deviceRecordingFact(
    state: RecordingDeviceState,
    sourcePreference: HomeActivitySourcePreference,
): AppActivityFact? {
    val status = state.status
    if (status != RecordingStatus.Recording && status != RecordingStatus.Paused) return null
    val paused = status == RecordingStatus.Paused
    val controlsEnabled =
        state.freshness == RecordingFreshness.FRESH &&
            state.commandState == RecordingCommandState.IDLE
    val actions =
        if (!controlsEnabled) emptySet()
        else if (paused) setOf(AppActivityAction.RESUME, AppActivityAction.STOP)
        else setOf(AppActivityAction.PAUSE, AppActivityAction.STOP)
    return AppActivityFact(
        logicalKey = DEVICE_LIVE_KEY,
        kind = AppActivityKind.RECORDING,
        source = AppActivitySource.RECORDER_CARD,
        lifecycleClass = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
        title = if (paused) "录音已暂停" else "录音中",
        subtitle = "录音卡",
        elapsedMs = deviceElapsedMs(state.durationSeconds),
        mediaDisplayName = state.filename,
        mediaSizeBytes = state.currentSizeBytes,
        availableActions = actions,
        priorityHint = RECORDING_PRIORITY,
        explicitlyFocused = sourcePreference == HomeActivitySourcePreference.RECORDER_CARD,
        startedAtMs = state.lastUpdatedTimeMs,
    )
}

private fun deviceRealtimeFact(
    recording: RecordingDeviceState,
    realtime: Stage16ARealtimeUiState,
    sourcePreference: HomeActivitySourcePreference,
): AppActivityFact? {
    if (!realtime.phase.isLiveProjectionPhase()) return null
    val physicalRecordingActive =
        recording.status == RecordingStatus.Recording || recording.status == RecordingStatus.Paused
    val lifecycle =
        if (realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && !physicalRecordingActive) {
            AppActivityLifecycleClass.INTERRUPTED_ATTENTION
        } else {
            AppActivityLifecycleClass.ACTIVE_CONTROLLABLE
        }
    val title =
        when {
            realtime.sourceCaptureInterrupted -> "录音采集中断"
            realtime.recordingOnlyMode -> if (recording.status == RecordingStatus.Paused) "录音已暂停" else "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && physicalRecordingActive -> "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "实时转写已暂停"
            else -> "实时转写"
        }
    val detail = realtimeDetail(realtime)
    return AppActivityFact(
        logicalKey = DEVICE_LIVE_KEY,
        kind = AppActivityKind.REALTIME_TRANSCRIPTION,
        source = AppActivitySource.RECORDER_CARD,
        lifecycleClass = lifecycle,
        title = title,
        subtitle = "录音卡 · $detail",
        elapsedMs = deviceElapsedMs(recording.durationSeconds),
        modelLabel = realtime.modelLabel,
        audioActivityLevels = realtime.audioActivityLevels,
        waveformActive =
            realtime.phase == Stage16ARealtimeUiPhase.ACTIVE &&
                !realtime.recordingOnlyMode &&
                !realtime.sourceCaptureInterrupted,
        destination = AppActivityDestination.DeviceRealtime,
        availableActions = realtimeActions(realtime),
        priorityHint = REALTIME_PRIORITY,
        explicitlyFocused = sourcePreference == HomeActivitySourcePreference.RECORDER_CARD,
        startedAtMs = recording.lastUpdatedTimeMs,
    )
}

private fun phoneRecordingFact(
    state: PhoneMicrophoneRecordingUiState,
    sourcePreference: HomeActivitySourcePreference,
): AppActivityFact? {
    val snapshot = state.recording
    if (!snapshot.phase.isPhoneActiveProjectionPhase()) return null
    val availableActions =
        if (state.ownedByRealtime) emptySet()
        else when (snapshot.phase) {
            PhoneMicrophoneRecordingPhase.RECORDING -> setOf(AppActivityAction.PAUSE, AppActivityAction.STOP)
            PhoneMicrophoneRecordingPhase.PAUSED -> setOf(AppActivityAction.RESUME, AppActivityAction.STOP)
            else -> emptySet()
        }
    val title =
        when (snapshot.phase) {
            PhoneMicrophoneRecordingPhase.PAUSED -> "录音已暂停"
            PhoneMicrophoneRecordingPhase.STOPPING -> "正在保存录音"
            else -> "录音中"
        }
    return AppActivityFact(
        logicalKey = PHONE_LIVE_KEY,
        kind = AppActivityKind.RECORDING,
        source = AppActivitySource.PHONE_MICROPHONE,
        lifecycleClass = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
        title = title,
        subtitle = "手机",
        elapsedMs = snapshot.durationMs,
        mediaDisplayName = snapshot.displayName,
        mediaSizeBytes = phoneWavSizeBytes(snapshot.writtenSamples, snapshot.finalizedSizeBytes),
        audioActivityLevels = state.audioActivityLevels,
        waveformActive =
            snapshot.phase == PhoneMicrophoneRecordingPhase.RECORDING &&
                state.capture.phase == PhoneMicrophoneCapturePhase.RECORDING,
        // Ordinary phone recording is controlled entirely on Home; completed files still use Recording Detail.
        destination = null,
        availableActions = availableActions,
        priorityHint = RECORDING_PRIORITY,
        explicitlyFocused = sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
    )
}

private fun phoneRealtimeFact(
    recording: PhoneMicrophoneRecordingUiState,
    realtime: Stage16ARealtimeUiState,
    sourcePreference: HomeActivitySourcePreference,
): AppActivityFact? {
    if (!realtime.phase.isLiveProjectionPhase()) return null
    val recordingActive = recording.recording.phase.isPhoneActiveProjectionPhase()
    val lifecycle =
        if (realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && !recordingActive) {
            AppActivityLifecycleClass.INTERRUPTED_ATTENTION
        } else {
            AppActivityLifecycleClass.ACTIVE_CONTROLLABLE
        }
    val title =
        when {
            realtime.sourceCaptureInterrupted -> "录音采集中断"
            realtime.recordingOnlyMode -> if (recording.recording.phase == PhoneMicrophoneRecordingPhase.PAUSED) "录音已暂停" else "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && recordingActive -> "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "实时转写已暂停"
            else -> "实时转写"
        }
    return AppActivityFact(
        logicalKey = PHONE_LIVE_KEY,
        kind = AppActivityKind.REALTIME_TRANSCRIPTION,
        source = AppActivitySource.PHONE_MICROPHONE,
        lifecycleClass = lifecycle,
        title = title,
        subtitle = "手机 · ${realtimeDetail(realtime)}",
        elapsedMs = recording.recording.durationMs,
        modelLabel = realtime.modelLabel,
        audioActivityLevels = realtime.audioActivityLevels,
        waveformActive =
            realtime.phase == Stage16ARealtimeUiPhase.ACTIVE &&
                !realtime.recordingOnlyMode &&
                !realtime.sourceCaptureInterrupted,
        destination = AppActivityDestination.PhoneMicrophone(PhoneMicrophoneProductTarget.REALTIME),
        availableActions = realtimeActions(realtime),
        priorityHint = REALTIME_PRIORITY,
        explicitlyFocused = sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
    )
}

private fun realtimeDetail(state: Stage16ARealtimeUiState): String =
    when {
        state.sourceCaptureInterrupted -> "音频采集中断"
        state.recordingOnlyMode -> "实时转写已停止"
        state.phase == Stage16ARealtimeUiPhase.INTERRUPTED -> "实时转写已中断"
        state.phase == Stage16ARealtimeUiPhase.PREPARING -> "正在准备"
        state.phase == Stage16ARealtimeUiPhase.STARTING -> "正在启动"
        state.phase == Stage16ARealtimeUiPhase.RESUMING -> "正在继续"
        state.phase == Stage16ARealtimeUiPhase.FINALIZING -> "正在保存"
        state.phase == Stage16ARealtimeUiPhase.PAUSED -> "已暂停"
        else -> "转写中"
    }

private fun realtimeActions(state: Stage16ARealtimeUiState): Set<AppActivityAction> =
    when (state.phase) {
        Stage16ARealtimeUiPhase.ACTIVE -> setOf(AppActivityAction.PAUSE, AppActivityAction.STOP, AppActivityAction.OPEN)
        Stage16ARealtimeUiPhase.PAUSED -> setOf(AppActivityAction.RESUME, AppActivityAction.STOP, AppActivityAction.OPEN)
        Stage16ARealtimeUiPhase.PREPARING,
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.RESUMING,
        Stage16ARealtimeUiPhase.FINALIZING,
        Stage16ARealtimeUiPhase.INTERRUPTED,
        -> setOf(AppActivityAction.OPEN)
        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
        -> emptySet()
    }

private fun Stage16ARealtimeUiPhase.isLiveProjectionPhase(): Boolean =
    this in setOf(
        Stage16ARealtimeUiPhase.PREPARING,
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.ACTIVE,
        Stage16ARealtimeUiPhase.PAUSED,
        Stage16ARealtimeUiPhase.RESUMING,
        Stage16ARealtimeUiPhase.FINALIZING,
        Stage16ARealtimeUiPhase.INTERRUPTED,
    )

private fun PhoneMicrophoneRecordingPhase.isPhoneActiveProjectionPhase(): Boolean =
    this in setOf(
        PhoneMicrophoneRecordingPhase.STARTING,
        PhoneMicrophoneRecordingPhase.RECORDING,
        PhoneMicrophoneRecordingPhase.PAUSING,
        PhoneMicrophoneRecordingPhase.PAUSED,
        PhoneMicrophoneRecordingPhase.RESUMING,
        PhoneMicrophoneRecordingPhase.STOPPING,
    )

private fun deviceElapsedMs(durationSeconds: Int?): Long =
    (durationSeconds ?: 0).coerceAtLeast(0).toLong() * 1_000L

private fun phoneWavSizeBytes(writtenSamples: Long, finalizedSizeBytes: Long?): Long? {
    finalizedSizeBytes?.let { return it.coerceAtLeast(0L) }
    if (writtenSamples <= 0L) return null
    return WAV_HEADER_BYTES + writtenSamples.coerceAtLeast(0L) * PCM16_BYTES_PER_SAMPLE
}

private const val DEVICE_LIVE_KEY = "home-device-live"
private const val PHONE_LIVE_KEY = "home-phone-live"
private const val RECORDING_PRIORITY = 100
private const val REALTIME_PRIORITY = 200
private const val WAV_HEADER_BYTES = 44L
private const val PCM16_BYTES_PER_SAMPLE = 2L
