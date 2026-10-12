package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class RealtimeRecordingLifecycleReconcilerSnapshot(
    val trustedStateUpdates: Long = 0,
    val recordingEndedTransitions: Long = 0,
    val reconciliationRequested: Long = 0,
    val reconciliationCompleted: Long = 0,
    val reconciliationFallbackCompleted: Long = 0,
    val reconciliationNoOp: Long = 0,
    val reconciliationFailure: Long = 0,
    val lastPreviousStatus: RecordingStatus? = null,
    val lastCurrentStatus: RecordingStatus? = null,
    val lastRuntimePhase: RealtimeRuntimePhase? = null,
    val lastResult: RealtimeRecordingReconciliationResult? = null,
)

/**
 * Stage 15.8 app-layer bridge between the device recording lifecycle and TYPE1 realtime runtime.
 *
 * D2 proved that a device-side recording termination can stop TYPE1 media without changing the app
 * runtime phase. Only trusted FRESH Recording/Paused -> Idle transitions are considered. The bridge
 * remains outside core:ble so BLE does not depend on the app realtime runtime.
 */
class RealtimeRecordingLifecycleReconciler(
    scope: CoroutineScope,
    recordingStates: StateFlow<RecordingDeviceState>,
    private val runtimeSnapshot: () -> RealtimeAudioRuntimeSnapshot,
    private val reconcile: suspend () -> RealtimeRecordingReconciliationOutcome,
) {
    private val mutableSnapshot = MutableStateFlow(RealtimeRecordingLifecycleReconcilerSnapshot())
    val snapshot: StateFlow<RealtimeRecordingLifecycleReconcilerSnapshot> = mutableSnapshot.asStateFlow()

    private var previousTrustedStatus: RecordingStatus? = null

    init {
        scope.launch {
            recordingStates.collect { state -> handle(state) }
        }
    }

    private suspend fun handle(state: RecordingDeviceState) {
        val current = state.status
        if (state.freshness != RecordingFreshness.FRESH || current == null) return

        val previous = previousTrustedStatus
        previousTrustedStatus = current
        mutableSnapshot.value = mutableSnapshot.value.copy(
            trustedStateUpdates = mutableSnapshot.value.trustedStateUpdates + 1,
            lastPreviousStatus = previous,
            lastCurrentStatus = current,
        )

        val recordingEnded =
            (previous == RecordingStatus.Recording || previous == RecordingStatus.Paused) &&
                current == RecordingStatus.Idle
        if (!recordingEnded) return

        val runtimePhase = runtimeSnapshot().phase
        mutableSnapshot.value = mutableSnapshot.value.copy(
            recordingEndedTransitions = mutableSnapshot.value.recordingEndedTransitions + 1,
            lastRuntimePhase = runtimePhase,
        )
        if (runtimePhase != RealtimeRuntimePhase.ACTIVE && runtimePhase != RealtimeRuntimePhase.PAUSED) {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                reconciliationNoOp = mutableSnapshot.value.reconciliationNoOp + 1,
                lastResult = RealtimeRecordingReconciliationResult.NO_OP,
            )
            return
        }

        mutableSnapshot.value = mutableSnapshot.value.copy(
            reconciliationRequested = mutableSnapshot.value.reconciliationRequested + 1,
        )
        val outcome = reconcile()
        mutableSnapshot.value = when (outcome.result) {
            RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP ->
                mutableSnapshot.value.copy(
                    reconciliationCompleted = mutableSnapshot.value.reconciliationCompleted + 1,
                    lastResult = outcome.result,
                )
            RealtimeRecordingReconciliationResult.COMPLETED_WITH_FALLBACK ->
                mutableSnapshot.value.copy(
                    reconciliationCompleted = mutableSnapshot.value.reconciliationCompleted + 1,
                    reconciliationFallbackCompleted = mutableSnapshot.value.reconciliationFallbackCompleted + 1,
                    lastResult = outcome.result,
                )
            RealtimeRecordingReconciliationResult.NO_OP ->
                mutableSnapshot.value.copy(
                    reconciliationNoOp = mutableSnapshot.value.reconciliationNoOp + 1,
                    lastResult = outcome.result,
                )
            RealtimeRecordingReconciliationResult.FAILED ->
                mutableSnapshot.value.copy(
                    reconciliationFailure = mutableSnapshot.value.reconciliationFailure + 1,
                    lastResult = outcome.result,
                )
        }
    }
}
