package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RecordingFreshness
import io.github.ioannes78.voica.protocol.RecordingStatus
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryContext
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class Stage16AReconnectRecoverySnapshot(
    val pendingRecovery: Boolean = false,
    val syncRequests: Long = 0,
    val syncFailures: Long = 0,
    val recoveryAttempts: Long = 0,
    val recoveryAttached: Long = 0,
    val recoveryFailures: Long = 0,
    val lastDecision: RealtimeAutoRecoveryDecision? = null,
    val lastAttachFailure: RealtimeExistingRecordingAttachFailure? = null,
)

/**
 * App-scoped Stage 16A reconnect owner.
 *
 * A transport close never fabricates media continuity. Before the disconnect we remember only a
 * trusted FRESH active device-recording identity. When the same device reaches Ready on a new
 * transport generation, we explicitly refresh recording truth and delegate the final conservative
 * decision to [RealtimeAutoRecoveryPolicy] through [Stage16ARealtimeRuntime.recoverAfterReconnect].
 * The recovery path is local-only: it never sends recorder START/RESUME.
 */
class Stage16AReconnectRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val connectionStates: StateFlow<DeviceConnectionState>,
    private val recordingStates: StateFlow<RecordingDeviceState>,
    private val realtimeState: StateFlow<Stage16ARealtimeUiState>,
    private val transportGeneration: () -> Long?,
    private val syncRecordingState: suspend () -> Unit,
    private val recover: suspend (RealtimeAutoRecoveryContext) -> Stage16ARealtimeRecoveryOutcome,
    private val freshStateTimeoutMs: Long = DEFAULT_FRESH_STATE_TIMEOUT_MS,
    startCollectors: Boolean = true,
) {
    private val mutex = Mutex()

    @Volatile
    private var latestSnapshot = Stage16AReconnectRecoverySnapshot()

    private var lastReadyAddress: String? = null
    private var lastTrustedActiveRecording: TrustedActiveRecording? = null
    private var pendingRecovery: PendingRecovery? = null

    init {
        require(freshStateTimeoutMs > 0L)
        if (startCollectors) {
            scope.launch {
                connectionStates.collect { state -> handleConnectionState(state) }
            }
            scope.launch {
                recordingStates.collect { state -> handleRecordingState(state) }
            }
        }
    }

    fun snapshot(): Stage16AReconnectRecoverySnapshot = latestSnapshot

    internal suspend fun handleConnectionState(state: DeviceConnectionState) {
        mutex.withLock {
            when (state) {
                is DeviceConnectionState.Ready -> {
                    lastReadyAddress = state.address
                    val pending = pendingRecovery
                    if (pending != null) {
                        attemptRecoveryLocked(state, pending, syncFirst = true, suppliedRecording = null)
                    } else {
                        rememberTrustedActiveRecordingLocked(state.address, recordingStates.value)
                    }
                }

                is DeviceConnectionState.Disconnecting,
                is DeviceConnectionState.Disconnected,
                is DeviceConnectionState.ReconnectWaiting,
                DeviceConnectionState.BluetoothOff,
                is DeviceConnectionState.Error,
                -> capturePendingRecoveryLocked()

                else -> Unit
            }
        }
    }

    internal suspend fun handleRecordingState(state: RecordingDeviceState) {
        mutex.withLock {
            val ready = connectionStates.value as? DeviceConnectionState.Ready ?: return@withLock
            val pending = pendingRecovery
            if (pending != null) {
                if (state.freshness == RecordingFreshness.FRESH) {
                    attemptRecoveryLocked(
                        ready = ready,
                        pending = pending,
                        syncFirst = false,
                        suppliedRecording = state,
                    )
                }
            } else {
                rememberTrustedActiveRecordingLocked(ready.address, state)
            }
        }
    }

    private fun rememberTrustedActiveRecordingLocked(
        deviceAddress: String,
        state: RecordingDeviceState,
    ) {
        val realtime = realtimeState.value
        if (!realtime.phase.canSeedReconnectAnchor()) return
        val runId = realtime.runId ?: return
        val recordingIdentity = trustedActiveRecordingIdentity(state) ?: return
        val generation = transportGeneration() ?: return
        lastTrustedActiveRecording =
            TrustedActiveRecording(
                runId = runId,
                deviceIdentity = deviceAddress,
                recordingIdentity = recordingIdentity,
                transportGeneration = generation,
            )
    }

    private fun capturePendingRecoveryLocked() {
        if (pendingRecovery != null) return
        val realtime = realtimeState.value
        if (!realtime.phase.canRemainActiveAcrossTransportClose()) return
        val runId = realtime.runId ?: return
        val trusted = lastTrustedActiveRecording ?: return
        if (trusted.runId != runId) return
        if (lastReadyAddress != null && trusted.deviceIdentity != lastReadyAddress) return

        pendingRecovery =
            PendingRecovery(
                runId = trusted.runId,
                previousDeviceIdentity = trusted.deviceIdentity,
                previousRecordingIdentity = trusted.recordingIdentity,
                previousTransportGeneration = trusted.transportGeneration,
            )
        publishSnapshot(latestSnapshot.copy(pendingRecovery = true))
    }

    private suspend fun attemptRecoveryLocked(
        ready: DeviceConnectionState.Ready,
        pending: PendingRecovery,
        syncFirst: Boolean,
        suppliedRecording: RecordingDeviceState?,
    ) {
        if (pendingRecovery !== pending) return

        val currentGeneration = transportGeneration()
        if (currentGeneration == null || currentGeneration == pending.previousTransportGeneration) {
            return
        }

        if (ready.address != pending.previousDeviceIdentity) {
            invokeRecoveryLocked(
                pending = pending,
                currentDeviceIdentity = ready.address,
                currentRecording = suppliedRecording ?: recordingStates.value,
                currentGeneration = currentGeneration,
            )
            return
        }

        if (syncFirst) {
            publishSnapshot(latestSnapshot.copy(syncRequests = latestSnapshot.syncRequests + 1))
            val syncFailure = runCatching { syncRecordingState() }.exceptionOrNull()
            if (syncFailure != null) {
                publishSnapshot(latestSnapshot.copy(syncFailures = latestSnapshot.syncFailures + 1))
                return
            }
        }

        val currentRecording =
            suppliedRecording?.takeIf { it.freshness == RecordingFreshness.FRESH }
                ?: recordingStates.value.takeIf { it.freshness == RecordingFreshness.FRESH }
                ?: withTimeoutOrNull(freshStateTimeoutMs) {
                    recordingStates.filter { it.freshness == RecordingFreshness.FRESH }.first()
                }
                ?: return

        invokeRecoveryLocked(
            pending = pending,
            currentDeviceIdentity = ready.address,
            currentRecording = currentRecording,
            currentGeneration = currentGeneration,
        )
    }

    private suspend fun invokeRecoveryLocked(
        pending: PendingRecovery,
        currentDeviceIdentity: String,
        currentRecording: RecordingDeviceState,
        currentGeneration: Long,
    ) {
        val realtime = realtimeState.value
        val userDisabledRealtime =
            realtime.runId != pending.runId || realtime.phase.isTerminalOrIdle()
        val context =
            RealtimeAutoRecoveryContext(
                realtimeIntentActiveBeforeDisconnect = true,
                userDisabledRealtimeSinceDisconnect = userDisabledRealtime,
                previousDeviceIdentity = pending.previousDeviceIdentity,
                currentDeviceIdentity = currentDeviceIdentity,
                previousRecordingIdentity = pending.previousRecordingIdentity,
                currentRecordingIdentity = normalizedRecordingIdentity(currentRecording),
                currentDeviceRecordingActive = currentRecording.isTrustedActiveRecording(),
                previousTransportGeneration = pending.previousTransportGeneration,
                currentTransportGeneration = currentGeneration,
            )

        publishSnapshot(latestSnapshot.copy(recoveryAttempts = latestSnapshot.recoveryAttempts + 1))
        val outcome =
            try {
                recover(context)
            } catch (_: Throwable) {
                pendingRecovery = null
                publishSnapshot(
                    latestSnapshot.copy(
                        pendingRecovery = false,
                        recoveryFailures = latestSnapshot.recoveryFailures + 1,
                    ),
                )
                return
            }

        val shouldKeepPending =
            outcome.decision == RealtimeAutoRecoveryDecision.RECORDING_IDENTITY_UNCONFIRMED ||
                outcome.decision == RealtimeAutoRecoveryDecision.TRANSPORT_GENERATION_NOT_ADVANCED
        if (outcome.decision == RealtimeAutoRecoveryDecision.RESUME && outcome.attached) {
            pendingRecovery = null
            val currentIdentity = normalizedRecordingIdentity(currentRecording)
            if (currentIdentity != null) {
                lastTrustedActiveRecording =
                    TrustedActiveRecording(
                        runId = pending.runId,
                        deviceIdentity = currentDeviceIdentity,
                        recordingIdentity = currentIdentity,
                        transportGeneration = currentGeneration,
                    )
            }
            publishSnapshot(
                latestSnapshot.copy(
                    pendingRecovery = false,
                    recoveryAttached = latestSnapshot.recoveryAttached + 1,
                    lastDecision = outcome.decision,
                    lastAttachFailure = outcome.failure,
                ),
            )
            return
        }

        if (!shouldKeepPending) {
            pendingRecovery = null
        }
        publishSnapshot(
            latestSnapshot.copy(
                pendingRecovery = shouldKeepPending,
                recoveryFailures =
                    latestSnapshot.recoveryFailures +
                        if (outcome.decision == RealtimeAutoRecoveryDecision.RESUME && !outcome.attached) 1 else 0,
                lastDecision = outcome.decision,
                lastAttachFailure = outcome.failure,
            ),
        )
    }

    private fun publishSnapshot(snapshot: Stage16AReconnectRecoverySnapshot) {
        latestSnapshot = snapshot
    }

    private fun trustedActiveRecordingIdentity(state: RecordingDeviceState): String? {
        if (!state.isTrustedActiveRecording()) return null
        return normalizedRecordingIdentity(state)
    }

    private fun normalizedRecordingIdentity(state: RecordingDeviceState): String? =
        state.filename?.trim()?.takeIf { it.isNotEmpty() }

    private fun RecordingDeviceState.isTrustedActiveRecording(): Boolean =
        freshness == RecordingFreshness.FRESH &&
            (status == RecordingStatus.Recording || status == RecordingStatus.Paused)

    private fun Stage16ARealtimeUiPhase.canSeedReconnectAnchor(): Boolean =
        this == Stage16ARealtimeUiPhase.STARTING ||
            this == Stage16ARealtimeUiPhase.ACTIVE ||
            this == Stage16ARealtimeUiPhase.PAUSED ||
            this == Stage16ARealtimeUiPhase.RESUMING

    private fun Stage16ARealtimeUiPhase.canRemainActiveAcrossTransportClose(): Boolean =
        canSeedReconnectAnchor() || this == Stage16ARealtimeUiPhase.INTERRUPTED

    private fun Stage16ARealtimeUiPhase.isTerminalOrIdle(): Boolean =
        this == Stage16ARealtimeUiPhase.IDLE ||
            this == Stage16ARealtimeUiPhase.COMPLETED ||
            this == Stage16ARealtimeUiPhase.FAILED

    private data class TrustedActiveRecording(
        val runId: String,
        val deviceIdentity: String,
        val recordingIdentity: String,
        val transportGeneration: Long,
    )

    private data class PendingRecovery(
        val runId: String,
        val previousDeviceIdentity: String,
        val previousRecordingIdentity: String,
        val previousTransportGeneration: Long,
    )

    private companion object {
        const val DEFAULT_FRESH_STATE_TIMEOUT_MS = 3_000L
    }
}
