package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.PhoneMicrophoneRecordingPhase
import io.github.ioannes78.voica.PhoneMicrophoneRecordingSnapshot
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.protocol.RecordingStatus

internal enum class LiveActivityStripKind {
    RECORDING,
    REALTIME,
}

internal enum class LiveActivityStripPhase {
    ACTIVE,
    PAUSED,
}

internal data class LiveActivityStripItem(
    val source: AppActivitySource,
    val kind: LiveActivityStripKind,
    val phase: LiveActivityStripPhase,
    val elapsedMs: Long,
) {
    val statusLabel: String
        get() =
            when (kind) {
                LiveActivityStripKind.RECORDING ->
                    if (phase == LiveActivityStripPhase.PAUSED) "录音已暂停" else "正在录音"
                LiveActivityStripKind.REALTIME ->
                    if (phase == LiveActivityStripPhase.PAUSED) "实时转写已暂停" else "实时转写中"
            }

    val sourceLabel: String
        get() = if (source == AppActivitySource.PHONE_MICROPHONE) "手机" else "QS668 / CB08"
}

/**
 * Resolves compact live strips from existing recording/realtime truth.
 *
 * Realtime has presentation priority over its backing recording because it is the product activity
 * the user started. Exact visible realtime readers are suppressed by source identity only, so a
 * concurrent activity from the other source remains globally visible.
 */
internal fun resolveLiveActivityStrips(
    deviceRecording: RecordingDeviceState,
    deviceRealtime: Stage16ARealtimeUiState,
    phoneRecording: PhoneMicrophoneRecordingSnapshot,
    phoneRealtime: Stage16ARealtimeUiState,
    visibleRealtimeSource: AppActivitySource?,
): List<LiveActivityStripItem> {
    val candidates = buildList {
        realtimeStripItem(
            source = AppActivitySource.RECORDER_CARD,
            state = deviceRealtime,
            elapsedMs = (deviceRecording.durationSeconds ?: 0).coerceAtLeast(0) * 1_000L,
        )?.let(::add)
        realtimeStripItem(
            source = AppActivitySource.PHONE_MICROPHONE,
            state = phoneRealtime,
            elapsedMs = phoneRecording.durationMs.coerceAtLeast(0L),
        )?.let(::add)

        if (!deviceRealtime.isProductRealtimeLive()) {
            recordingStripItem(deviceRecording)?.let(::add)
        }
        if (!phoneRealtime.isProductRealtimeLive()) {
            phoneRecordingStripItem(phoneRecording)?.let(::add)
        }
    }

    return candidates
        .filterNot { item ->
            item.kind == LiveActivityStripKind.REALTIME && item.source == visibleRealtimeSource
        }
        .sortedWith(
            compareBy<LiveActivityStripItem>(
                { if (it.kind == LiveActivityStripKind.REALTIME) 0 else 1 },
                { if (it.source == AppActivitySource.RECORDER_CARD) 0 else 1 },
            ),
        )
}

/** Compatibility helper for callers that intentionally need only the highest-priority live item. */
internal fun resolveLiveActivityStrip(
    deviceRecording: RecordingDeviceState,
    deviceRealtime: Stage16ARealtimeUiState,
    phoneRecording: PhoneMicrophoneRecordingSnapshot,
    phoneRealtime: Stage16ARealtimeUiState,
    visibleRealtimeSource: AppActivitySource?,
): LiveActivityStripItem? =
    resolveLiveActivityStrips(
        deviceRecording = deviceRecording,
        deviceRealtime = deviceRealtime,
        phoneRecording = phoneRecording,
        phoneRealtime = phoneRealtime,
        visibleRealtimeSource = visibleRealtimeSource,
    ).firstOrNull()

private fun realtimeStripItem(
    source: AppActivitySource,
    state: Stage16ARealtimeUiState,
    elapsedMs: Long,
): LiveActivityStripItem? {
    if (!state.isProductRealtimeLive()) return null
    return LiveActivityStripItem(
        source = source,
        kind = LiveActivityStripKind.REALTIME,
        phase =
            if (state.phase == Stage16ARealtimeUiPhase.PAUSED) {
                LiveActivityStripPhase.PAUSED
            } else {
                LiveActivityStripPhase.ACTIVE
            },
        elapsedMs = elapsedMs,
    )
}

private fun Stage16ARealtimeUiState.isProductRealtimeLive(): Boolean =
    !recordingOnlyMode &&
        phase in setOf(
            Stage16ARealtimeUiPhase.PREPARING,
            Stage16ARealtimeUiPhase.STARTING,
            Stage16ARealtimeUiPhase.ACTIVE,
            Stage16ARealtimeUiPhase.PAUSED,
            Stage16ARealtimeUiPhase.RESUMING,
            Stage16ARealtimeUiPhase.FINALIZING,
            Stage16ARealtimeUiPhase.INTERRUPTED,
        )

private fun recordingStripItem(state: RecordingDeviceState): LiveActivityStripItem? =
    when (state.status) {
        RecordingStatus.Recording ->
            LiveActivityStripItem(
                source = AppActivitySource.RECORDER_CARD,
                kind = LiveActivityStripKind.RECORDING,
                phase = LiveActivityStripPhase.ACTIVE,
                elapsedMs = (state.durationSeconds ?: 0).coerceAtLeast(0) * 1_000L,
            )
        RecordingStatus.Paused ->
            LiveActivityStripItem(
                source = AppActivitySource.RECORDER_CARD,
                kind = LiveActivityStripKind.RECORDING,
                phase = LiveActivityStripPhase.PAUSED,
                elapsedMs = (state.durationSeconds ?: 0).coerceAtLeast(0) * 1_000L,
            )
        else -> null
    }

private fun phoneRecordingStripItem(state: PhoneMicrophoneRecordingSnapshot): LiveActivityStripItem? =
    when (state.phase) {
        PhoneMicrophoneRecordingPhase.RECORDING ->
            LiveActivityStripItem(
                source = AppActivitySource.PHONE_MICROPHONE,
                kind = LiveActivityStripKind.RECORDING,
                phase = LiveActivityStripPhase.ACTIVE,
                elapsedMs = state.durationMs.coerceAtLeast(0L),
            )
        PhoneMicrophoneRecordingPhase.PAUSED ->
            LiveActivityStripItem(
                source = AppActivitySource.PHONE_MICROPHONE,
                kind = LiveActivityStripKind.RECORDING,
                phase = LiveActivityStripPhase.PAUSED,
                elapsedMs = state.durationMs.coerceAtLeast(0L),
            )
        else -> null
    }
