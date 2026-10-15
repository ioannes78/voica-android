package io.github.ioannes78.voica.ui.device

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

/**
 * Maps the existing recording/realtime truths into the Stage 16A.1.5 presentation model.
 * This adapter is read-only: no controller/runtime call is made here.
 */
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
        if (!controlsEnabled) {
            emptySet()
        } else if (paused) {
            setOf(AppActivityAction.RESUME, AppActivityAction.STOP)
        } else {
            setOf(AppActivityAction.PAUSE, AppActivityAction.STOP)
        }
    return AppActivityFact(
        logicalKey = DEVICE_LIVE_KEY,
        kind = AppActivityKind.RECORDING,
        source = AppActivitySource.RECORDER_CARD,
        lifecycleClass = AppActivityLifecycleClass.ACTIVE_CONTROLLABLE,
        title = if (paused) "录音已暂停" else "录音中",
        subtitle = listOf("录音卡", formatDeviceDuration(state.durationSeconds)).joinToString(" · "),
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
            realtime.recordingOnlyMode ->
                if (recording.status == RecordingStatus.Paused) "录音已暂停" else "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && physicalRecordingActive -> "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "实时转写已暂停"
            else -> "实时转写"
        }
    val detail =
        when {
            realtime.sourceCaptureInterrupted -> "音频采集中断"
            realtime.recordingOnlyMode -> "实时转写已停止"
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED -> "实时转写已中断"
            realtime.phase == Stage16ARealtimeUiPhase.PREPARING -> "正在准备"
            realtime.phase == Stage16ARealtimeUiPhase.STARTING -> "正在启动"
            realtime.phase == Stage16ARealtimeUiPhase.RESUMING -> "正在继续"
            realtime.phase == Stage16ARealtimeUiPhase.FINALIZING -> "正在保存"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "已暂停"
            else -> "转写中"
        }
    return AppActivityFact(
        logicalKey = DEVICE_LIVE_KEY,
        kind = AppActivityKind.REALTIME_TRANSCRIPTION,
        source = AppActivitySource.RECORDER_CARD,
        lifecycleClass = lifecycle,
        title = title,
        subtitle = listOf("录音卡", formatDeviceDuration(recording.durationSeconds), detail).joinToString(" · "),
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
        if (state.ownedByRealtime) {
            emptySet()
        } else {
            when (snapshot.phase) {
                PhoneMicrophoneRecordingPhase.RECORDING ->
                    setOf(AppActivityAction.PAUSE, AppActivityAction.STOP)
                PhoneMicrophoneRecordingPhase.PAUSED ->
                    setOf(AppActivityAction.RESUME, AppActivityAction.STOP)
                else -> emptySet()
            }
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
        subtitle = "手机麦克风 · ${formatDurationMs(snapshot.durationMs)}",
        destination = AppActivityDestination.PhoneMicrophone(PhoneMicrophoneProductTarget.RECORDING),
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
            realtime.recordingOnlyMode ->
                if (recording.recording.phase == PhoneMicrophoneRecordingPhase.PAUSED) {
                    "录音已暂停"
                } else {
                    "录音继续中"
                }
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED && recordingActive -> "录音继续中"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "实时转写已暂停"
            else -> "实时转写"
        }
    val detail =
        when {
            realtime.sourceCaptureInterrupted -> "音频采集中断"
            realtime.recordingOnlyMode -> "实时转写已停止"
            realtime.phase == Stage16ARealtimeUiPhase.INTERRUPTED -> "实时转写已中断"
            realtime.phase == Stage16ARealtimeUiPhase.PREPARING -> "正在准备"
            realtime.phase == Stage16ARealtimeUiPhase.STARTING -> "正在启动"
            realtime.phase == Stage16ARealtimeUiPhase.RESUMING -> "正在继续"
            realtime.phase == Stage16ARealtimeUiPhase.FINALIZING -> "正在保存"
            realtime.phase == Stage16ARealtimeUiPhase.PAUSED -> "已暂停"
            else -> "转写中"
        }
    return AppActivityFact(
        logicalKey = PHONE_LIVE_KEY,
        kind = AppActivityKind.REALTIME_TRANSCRIPTION,
        source = AppActivitySource.PHONE_MICROPHONE,
        lifecycleClass = lifecycle,
        title = title,
        subtitle = "手机麦克风 · ${formatDurationMs(recording.recording.durationMs)} · $detail",
        destination = AppActivityDestination.PhoneMicrophone(PhoneMicrophoneProductTarget.REALTIME),
        availableActions = realtimeActions(realtime),
        priorityHint = REALTIME_PRIORITY,
        explicitlyFocused = sourcePreference == HomeActivitySourcePreference.PHONE_MICROPHONE,
    )
}

private fun realtimeActions(state: Stage16ARealtimeUiState): Set<AppActivityAction> =
    when (state.phase) {
        Stage16ARealtimeUiPhase.ACTIVE ->
            setOf(AppActivityAction.PAUSE, AppActivityAction.STOP, AppActivityAction.OPEN)
        Stage16ARealtimeUiPhase.PAUSED ->
            setOf(AppActivityAction.RESUME, AppActivityAction.STOP, AppActivityAction.OPEN)
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
    when (this) {
        Stage16ARealtimeUiPhase.PREPARING,
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.ACTIVE,
        Stage16ARealtimeUiPhase.PAUSED,
        Stage16ARealtimeUiPhase.RESUMING,
        Stage16ARealtimeUiPhase.FINALIZING,
        Stage16ARealtimeUiPhase.INTERRUPTED,
        -> true
        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
        -> false
    }

private fun PhoneMicrophoneRecordingPhase.isPhoneActiveProjectionPhase(): Boolean =
    when (this) {
        PhoneMicrophoneRecordingPhase.STARTING,
        PhoneMicrophoneRecordingPhase.RECORDING,
        PhoneMicrophoneRecordingPhase.PAUSING,
        PhoneMicrophoneRecordingPhase.PAUSED,
        PhoneMicrophoneRecordingPhase.RESUMING,
        PhoneMicrophoneRecordingPhase.STOPPING,
        -> true
        PhoneMicrophoneRecordingPhase.IDLE,
        PhoneMicrophoneRecordingPhase.COMPLETED,
        PhoneMicrophoneRecordingPhase.EMPTY,
        PhoneMicrophoneRecordingPhase.FAILED,
        PhoneMicrophoneRecordingPhase.CLOSED,
        -> false
    }

private fun formatDeviceDuration(durationSeconds: Int?): String =
    formatDurationMs((durationSeconds ?: 0).coerceAtLeast(0).toLong() * 1_000L)

private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private const val DEVICE_LIVE_KEY = "home-device-live"
private const val PHONE_LIVE_KEY = "home-phone-live"
private const val RECORDING_PRIORITY = 100
private const val REALTIME_PRIORITY = 200
