package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeRecordingLifecycleReconcilerTest {
    @Test
    fun freshRecordingToIdleWhileActiveRequestsReconciliationOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recording = MutableStateFlow(
            RecordingDeviceState(
                status = RecordingStatus.Idle,
                freshness = RecordingFreshness.FRESH,
            ),
        )
        val calls = AtomicInteger(0)
        val reconciler = RealtimeRecordingLifecycleReconciler(
            scope = scope,
            recordingStates = recording,
            runtimeSnapshot = { RealtimeAudioRuntimeSnapshot(phase = RealtimeRuntimePhase.ACTIVE) },
            reconcile = {
                calls.incrementAndGet()
                RealtimeRecordingReconciliationOutcome(
                    result = RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP,
                )
            },
        )

        awaitTrustedUpdates(reconciler, 1)
        recording.value = RecordingDeviceState(
            status = RecordingStatus.Recording,
            freshness = RecordingFreshness.FRESH,
        )
        awaitTrustedUpdates(reconciler, 2)
        recording.value = RecordingDeviceState(
            status = RecordingStatus.Idle,
            freshness = RecordingFreshness.FRESH,
        )
        awaitCompleted(reconciler, 1)

        assertEquals(1, calls.get())
        assertEquals(1L, reconciler.snapshot.value.recordingEndedTransitions)
        assertEquals(1L, reconciler.snapshot.value.reconciliationRequested)
        assertEquals(1L, reconciler.snapshot.value.reconciliationCompleted)
        assertEquals(RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP, reconciler.snapshot.value.lastResult)

        recording.value = recording.value.copy(lastUpdatedTimeMs = 123L)
        delay(50)
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun staleIdleDoesNotCreateTrustedTerminationTransition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recording = MutableStateFlow(
            RecordingDeviceState(
                status = RecordingStatus.Recording,
                freshness = RecordingFreshness.FRESH,
            ),
        )
        val calls = AtomicInteger(0)
        val reconciler = RealtimeRecordingLifecycleReconciler(
            scope = scope,
            recordingStates = recording,
            runtimeSnapshot = { RealtimeAudioRuntimeSnapshot(phase = RealtimeRuntimePhase.ACTIVE) },
            reconcile = {
                calls.incrementAndGet()
                RealtimeRecordingReconciliationOutcome(RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP)
            },
        )

        awaitTrustedUpdates(reconciler, 1)
        recording.value = RecordingDeviceState(
            status = RecordingStatus.Idle,
            freshness = RecordingFreshness.STALE,
        )
        delay(50)

        assertEquals(0, calls.get())
        assertEquals(0L, reconciler.snapshot.value.recordingEndedTransitions)
        assertEquals(0L, reconciler.snapshot.value.reconciliationRequested)
        scope.cancel()
    }

    @Test
    fun freshRecordingToIdleWhileRuntimeDrainingIsNoOp() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recording = MutableStateFlow(
            RecordingDeviceState(
                status = RecordingStatus.Recording,
                freshness = RecordingFreshness.FRESH,
            ),
        )
        val calls = AtomicInteger(0)
        val reconciler = RealtimeRecordingLifecycleReconciler(
            scope = scope,
            recordingStates = recording,
            runtimeSnapshot = { RealtimeAudioRuntimeSnapshot(phase = RealtimeRuntimePhase.DRAINING) },
            reconcile = {
                calls.incrementAndGet()
                RealtimeRecordingReconciliationOutcome(RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP)
            },
        )

        awaitTrustedUpdates(reconciler, 1)
        recording.value = RecordingDeviceState(
            status = RecordingStatus.Idle,
            freshness = RecordingFreshness.FRESH,
        )
        awaitNoOp(reconciler, 1)

        assertEquals(0, calls.get())
        assertEquals(1L, reconciler.snapshot.value.recordingEndedTransitions)
        assertEquals(0L, reconciler.snapshot.value.reconciliationRequested)
        assertEquals(1L, reconciler.snapshot.value.reconciliationNoOp)
        assertEquals(RealtimeRecordingReconciliationResult.NO_OP, reconciler.snapshot.value.lastResult)
        scope.cancel()
    }

    private suspend fun awaitTrustedUpdates(
        reconciler: RealtimeRecordingLifecycleReconciler,
        expected: Long,
    ) {
        withTimeout(2_000L) {
            while (reconciler.snapshot.value.trustedStateUpdates < expected) delay(5)
        }
    }

    private suspend fun awaitCompleted(
        reconciler: RealtimeRecordingLifecycleReconciler,
        expected: Long,
    ) {
        withTimeout(2_000L) {
            while (reconciler.snapshot.value.reconciliationCompleted < expected) delay(5)
        }
    }

    private suspend fun awaitNoOp(
        reconciler: RealtimeRecordingLifecycleReconciler,
        expected: Long,
    ) {
        withTimeout(2_000L) {
            while (reconciler.snapshot.value.reconciliationNoOp < expected) delay(5)
        }
    }
}
