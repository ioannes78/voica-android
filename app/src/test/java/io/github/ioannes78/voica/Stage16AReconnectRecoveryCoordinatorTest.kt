package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DisconnectReason
import io.github.ioannes78.voica.ble.MtuPolicy
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryContext
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryDecision
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16AReconnectRecoveryCoordinatorTest {
    @Test
    fun `same device same recording on new generation resumes locally`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val connection = MutableStateFlow<DeviceConnectionState>(ready("AA:BB"))
            val recording = MutableStateFlow(activeRecording("note-a.opus"))
            val realtime = MutableStateFlow(activeRealtime())
            var generation = 1L
            var syncRequests = 0
            val contexts = mutableListOf<RealtimeAutoRecoveryContext>()
            val coordinator =
                Stage16AReconnectRecoveryCoordinator(
                    scope = scope,
                    connectionStates = connection,
                    recordingStates = recording,
                    realtimeState = realtime,
                    transportGeneration = { generation },
                    syncRecordingState = {
                        syncRequests += 1
                        recording.value = activeRecording("note-a.opus")
                    },
                    recover = { context ->
                        contexts += context
                        val decision = RealtimeAutoRecoveryPolicy.evaluate(context)
                        Stage16ARealtimeRecoveryOutcome(
                            decision = decision,
                            attached = decision == RealtimeAutoRecoveryDecision.RESUME,
                        )
                    },
                    startCollectors = false,
                )

            coordinator.handleRecordingState(recording.value)
            val disconnected = DeviceConnectionState.Disconnected("AA:BB", DisconnectReason.USER)
            connection.value = disconnected
            coordinator.handleConnectionState(disconnected)
            realtime.value = realtime.value.copy(phase = Stage16ARealtimeUiPhase.INTERRUPTED)
            recording.value = recording.value.copy(freshness = RecordingFreshness.STALE)
            generation = 2L
            connection.value = ready("AA:BB")

            coordinator.handleConnectionState(connection.value)

            assertEquals(1, syncRequests)
            assertEquals(1, contexts.size)
            assertEquals("AA:BB", contexts.single().previousDeviceIdentity)
            assertEquals("AA:BB", contexts.single().currentDeviceIdentity)
            assertEquals("note-a.opus", contexts.single().previousRecordingIdentity)
            assertEquals("note-a.opus", contexts.single().currentRecordingIdentity)
            assertEquals(1L, contexts.single().previousTransportGeneration)
            assertEquals(2L, contexts.single().currentTransportGeneration)
            assertTrue(contexts.single().currentDeviceRecordingActive)
            assertFalse(contexts.single().userDisabledRealtimeSinceDisconnect)
            assertFalse(coordinator.snapshot().pendingRecovery)
            assertEquals(1L, coordinator.snapshot().recoveryAttempts)
            assertEquals(1L, coordinator.snapshot().recoveryAttached)
            assertEquals(RealtimeAutoRecoveryDecision.RESUME, coordinator.snapshot().lastDecision)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recording identity mismatch refuses recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val connection = MutableStateFlow<DeviceConnectionState>(ready("AA:BB"))
            val recording = MutableStateFlow(activeRecording("note-a.opus"))
            val realtime = MutableStateFlow(activeRealtime())
            var generation = 1L
            val coordinator =
                Stage16AReconnectRecoveryCoordinator(
                    scope = scope,
                    connectionStates = connection,
                    recordingStates = recording,
                    realtimeState = realtime,
                    transportGeneration = { generation },
                    syncRecordingState = { recording.value = activeRecording("note-b.opus") },
                    recover = { context ->
                        Stage16ARealtimeRecoveryOutcome(
                            decision = RealtimeAutoRecoveryPolicy.evaluate(context),
                            attached = false,
                        )
                    },
                    startCollectors = false,
                )

            coordinator.handleRecordingState(recording.value)
            val disconnected = DeviceConnectionState.Disconnected("AA:BB", DisconnectReason.USER)
            connection.value = disconnected
            coordinator.handleConnectionState(disconnected)
            realtime.value = realtime.value.copy(phase = Stage16ARealtimeUiPhase.INTERRUPTED)
            recording.value = recording.value.copy(freshness = RecordingFreshness.STALE)
            generation = 2L
            connection.value = ready("AA:BB")

            coordinator.handleConnectionState(connection.value)

            assertFalse(coordinator.snapshot().pendingRecovery)
            assertEquals(1L, coordinator.snapshot().recoveryAttempts)
            assertEquals(0L, coordinator.snapshot().recoveryAttached)
            assertEquals(
                RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_MISMATCH,
                coordinator.snapshot().lastDecision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recording ended while disconnected refuses recovery`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val connection = MutableStateFlow<DeviceConnectionState>(ready("AA:BB"))
            val recording = MutableStateFlow(activeRecording("note-a.opus"))
            val realtime = MutableStateFlow(activeRealtime())
            var generation = 1L
            val coordinator =
                Stage16AReconnectRecoveryCoordinator(
                    scope = scope,
                    connectionStates = connection,
                    recordingStates = recording,
                    realtimeState = realtime,
                    transportGeneration = { generation },
                    syncRecordingState = {
                        recording.value =
                            RecordingDeviceState(
                                status = RecordingStatus.Idle,
                                filename = "note-a.opus",
                                freshness = RecordingFreshness.FRESH,
                            )
                    },
                    recover = { context ->
                        Stage16ARealtimeRecoveryOutcome(
                            decision = RealtimeAutoRecoveryPolicy.evaluate(context),
                            attached = false,
                        )
                    },
                    startCollectors = false,
                )

            coordinator.handleRecordingState(recording.value)
            val disconnected = DeviceConnectionState.Disconnected("AA:BB", DisconnectReason.USER)
            connection.value = disconnected
            coordinator.handleConnectionState(disconnected)
            realtime.value = realtime.value.copy(phase = Stage16ARealtimeUiPhase.INTERRUPTED)
            recording.value = recording.value.copy(freshness = RecordingFreshness.STALE)
            generation = 2L
            connection.value = ready("AA:BB")

            coordinator.handleConnectionState(connection.value)

            assertFalse(coordinator.snapshot().pendingRecovery)
            assertEquals(
                RealtimeAutoRecoveryDecision.RECORDING_NOT_ACTIVE,
                coordinator.snapshot().lastDecision,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `same transport generation never attempts attach`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val connection = MutableStateFlow<DeviceConnectionState>(ready("AA:BB"))
            val recording = MutableStateFlow(activeRecording("note-a.opus"))
            val realtime = MutableStateFlow(activeRealtime())
            var recoverCalls = 0
            val coordinator =
                Stage16AReconnectRecoveryCoordinator(
                    scope = scope,
                    connectionStates = connection,
                    recordingStates = recording,
                    realtimeState = realtime,
                    transportGeneration = { 1L },
                    syncRecordingState = { error("must not sync without a new generation") },
                    recover = {
                        recoverCalls += 1
                        Stage16ARealtimeRecoveryOutcome(RealtimeAutoRecoveryDecision.RESUME, attached = true)
                    },
                    startCollectors = false,
                )

            coordinator.handleRecordingState(recording.value)
            val disconnected = DeviceConnectionState.Disconnected("AA:BB", DisconnectReason.USER)
            connection.value = disconnected
            coordinator.handleConnectionState(disconnected)
            realtime.value = realtime.value.copy(phase = Stage16ARealtimeUiPhase.INTERRUPTED)
            connection.value = ready("AA:BB")

            coordinator.handleConnectionState(connection.value)

            assertEquals(0, recoverCalls)
            assertTrue(coordinator.snapshot().pendingRecovery)
            assertEquals(0L, coordinator.snapshot().recoveryAttempts)
        } finally {
            scope.cancel()
        }
    }

    private fun ready(address: String): DeviceConnectionState.Ready =
        DeviceConnectionState.Ready(
            address = address,
            negotiatedMtu = 517,
            capability = MtuPolicy.evaluate(517),
        )

    private fun activeRecording(filename: String): RecordingDeviceState =
        RecordingDeviceState(
            status = RecordingStatus.Recording,
            filename = filename,
            freshness = RecordingFreshness.FRESH,
        )

    private fun activeRealtime(): Stage16ARealtimeUiState =
        Stage16ARealtimeUiState(
            phase = Stage16ARealtimeUiPhase.ACTIVE,
            runId = "run-a",
            modelId = "small",
            modelLabel = "Small",
        )
}
