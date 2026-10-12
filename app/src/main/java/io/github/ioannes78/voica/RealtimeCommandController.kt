package io.github.ioannes78.voica

import io.github.ioannes78.voica.ble.RealtimeCommandFence
import io.github.ioannes78.voica.ble.RealtimeCommandFenceCommitResult
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import io.github.ioannes78.voica.ble.RealtimeDeviceTransport
import io.github.ioannes78.voica.ble.RealtimeTransportCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RealtimeRuntimePhase {
    IDLE,
    STARTING,
    ACTIVE,
    PAUSING,
    PAUSED,
    RESUMING,
    STOPPING,
    DRAINING,
    TRANSPORT_CLOSED,
    ERROR,
    CLOSED,
}

enum class RealtimeCommandFailure {
    INVALID_PHASE,
    TRANSPORT_NOT_READY,
    WRITE_FAILED,
    STALE_GENERATION,
    COMMAND_AMBIGUITY,
    DOWNSTREAM_REJECTED,
    INTERNAL_ERROR,
    CLOSED,
}

data class RealtimeCommandOutcome(
    val command: RealtimeControlCommand,
    val success: Boolean,
    val failure: RealtimeCommandFailure? = null,
    val transportResult: RealtimeTransportCommandResult? = null,
)

data class RealtimeCommandControllerSnapshot(
    val phase: RealtimeRuntimePhase = RealtimeRuntimePhase.IDLE,
    val transportGeneration: Long? = null,
    val lastCommand: RealtimeControlCommand? = null,
    val lastFailure: RealtimeCommandFailure? = null,
    val startSuccess: Long = 0,
    val startFailure: Long = 0,
    val pauseSuccess: Long = 0,
    val pauseFailure: Long = 0,
    val resumeSuccess: Long = 0,
    val resumeFailure: Long = 0,
    val stopSuccess: Long = 0,
    val stopFailure: Long = 0,
    val staleCommandCompletionCount: Long = 0,
    val commandAmbiguityCount: Long = 0,
)

interface RealtimeCommandBoundarySink {
    fun beginRealtimeSession(): Long
    fun requestControlBoundary(): Long
}

/**
 * Stage 15.6 command transaction coordinator.
 *
 * START/RESUME arm a generation-aware audio fence before the BLE write. A successful write commits
 * the canonical boundary atomically inside the fence, then releases fenced audio. Failed/stale or
 * ambiguous commands therefore cannot fabricate a logical session/epoch boundary.
 */
class RealtimeCommandController(
    private val transport: RealtimeDeviceTransport,
    private val fence: RealtimeCommandFence,
    private val boundarySink: RealtimeCommandBoundarySink,
) {
    private val commandMutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(RealtimeCommandControllerSnapshot())
    val snapshot: StateFlow<RealtimeCommandControllerSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun start(): RealtimeCommandOutcome = commandMutex.withLock {
        val current = mutableSnapshot.value
        if (current.phase == RealtimeRuntimePhase.CLOSED) {
            return@withLock rejected(RealtimeControlCommand.START, RealtimeCommandFailure.CLOSED)
        }
        if (current.phase != RealtimeRuntimePhase.IDLE && current.phase != RealtimeRuntimePhase.TRANSPORT_CLOSED) {
            return@withLock rejected(RealtimeControlCommand.START, RealtimeCommandFailure.INVALID_PHASE)
        }
        val generation = transport.currentGeneration
            ?: return@withLock rejected(RealtimeControlCommand.START, RealtimeCommandFailure.TRANSPORT_NOT_READY)
        val token = fence.prepare(RealtimeControlCommand.START, generation)
        publishPhase(RealtimeRuntimePhase.STARTING, generation, RealtimeControlCommand.START, null)

        val result = sendSafely(RealtimeControlCommand.START, tokenAbort = { fence.abort(token) })
            ?: return@withLock failureAfterException(
                command = RealtimeControlCommand.START,
                phase = RealtimeRuntimePhase.IDLE,
                counter = Counter.START_FAILURE,
            )
        if (!result.written) {
            fence.abort(token)
            increment(Counter.START_FAILURE)
            publishPhase(RealtimeRuntimePhase.IDLE, generation, RealtimeControlCommand.START, RealtimeCommandFailure.WRITE_FAILED)
            return@withLock RealtimeCommandOutcome(
                RealtimeControlCommand.START,
                false,
                RealtimeCommandFailure.WRITE_FAILED,
                result,
            )
        }
        if (!isCurrentGeneration(generation, result)) {
            fence.abort(token)
            increment(Counter.START_FAILURE)
            increment(Counter.STALE_COMPLETION)
            publishPhase(
                RealtimeRuntimePhase.TRANSPORT_CLOSED,
                transport.currentGeneration,
                RealtimeControlCommand.START,
                RealtimeCommandFailure.STALE_GENERATION,
            )
            return@withLock RealtimeCommandOutcome(
                RealtimeControlCommand.START,
                false,
                RealtimeCommandFailure.STALE_GENERATION,
                result,
            )
        }

        val failure = commitFailure(
            fence.commit(token) { boundarySink.beginRealtimeSession() },
        )
        if (failure != null) {
            increment(Counter.START_FAILURE)
            if (failure == RealtimeCommandFailure.COMMAND_AMBIGUITY) increment(Counter.AMBIGUITY)
            publishPhase(RealtimeRuntimePhase.ERROR, generation, RealtimeControlCommand.START, failure)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.START, false, failure, result)
        }

        increment(Counter.START_SUCCESS)
        publishPhase(RealtimeRuntimePhase.ACTIVE, generation, RealtimeControlCommand.START, null)
        RealtimeCommandOutcome(RealtimeControlCommand.START, true, transportResult = result)
    }

    suspend fun pause(): RealtimeCommandOutcome = commandMutex.withLock {
        val current = mutableSnapshot.value
        if (current.phase == RealtimeRuntimePhase.CLOSED) {
            return@withLock rejected(RealtimeControlCommand.PAUSE, RealtimeCommandFailure.CLOSED)
        }
        if (current.phase != RealtimeRuntimePhase.ACTIVE) {
            return@withLock rejected(RealtimeControlCommand.PAUSE, RealtimeCommandFailure.INVALID_PHASE)
        }
        val generation = current.transportGeneration ?: transport.currentGeneration
            ?: return@withLock rejected(RealtimeControlCommand.PAUSE, RealtimeCommandFailure.TRANSPORT_NOT_READY)
        publishPhase(RealtimeRuntimePhase.PAUSING, generation, RealtimeControlCommand.PAUSE, null)
        val result = sendSafely(RealtimeControlCommand.PAUSE)
            ?: return@withLock failureAfterException(
                command = RealtimeControlCommand.PAUSE,
                phase = RealtimeRuntimePhase.ACTIVE,
                counter = Counter.PAUSE_FAILURE,
            )
        if (!result.written) {
            increment(Counter.PAUSE_FAILURE)
            publishPhase(RealtimeRuntimePhase.ACTIVE, generation, RealtimeControlCommand.PAUSE, RealtimeCommandFailure.WRITE_FAILED)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.PAUSE, false, RealtimeCommandFailure.WRITE_FAILED, result)
        }
        if (!isCurrentGeneration(generation, result)) {
            increment(Counter.PAUSE_FAILURE)
            increment(Counter.STALE_COMPLETION)
            publishPhase(RealtimeRuntimePhase.TRANSPORT_CLOSED, transport.currentGeneration, RealtimeControlCommand.PAUSE, RealtimeCommandFailure.STALE_GENERATION)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.PAUSE, false, RealtimeCommandFailure.STALE_GENERATION, result)
        }

        fence.closeAudioGate()
        increment(Counter.PAUSE_SUCCESS)
        publishPhase(RealtimeRuntimePhase.PAUSED, generation, RealtimeControlCommand.PAUSE, null)
        RealtimeCommandOutcome(RealtimeControlCommand.PAUSE, true, transportResult = result)
    }

    suspend fun resume(): RealtimeCommandOutcome = commandMutex.withLock {
        val current = mutableSnapshot.value
        if (current.phase == RealtimeRuntimePhase.CLOSED) {
            return@withLock rejected(RealtimeControlCommand.RESUME, RealtimeCommandFailure.CLOSED)
        }
        if (current.phase != RealtimeRuntimePhase.PAUSED) {
            return@withLock rejected(RealtimeControlCommand.RESUME, RealtimeCommandFailure.INVALID_PHASE)
        }
        val generation = current.transportGeneration ?: transport.currentGeneration
            ?: return@withLock rejected(RealtimeControlCommand.RESUME, RealtimeCommandFailure.TRANSPORT_NOT_READY)
        val token = fence.prepare(RealtimeControlCommand.RESUME, generation)
        publishPhase(RealtimeRuntimePhase.RESUMING, generation, RealtimeControlCommand.RESUME, null)
        val result = sendSafely(RealtimeControlCommand.RESUME, tokenAbort = { fence.abort(token) })
            ?: return@withLock failureAfterException(
                command = RealtimeControlCommand.RESUME,
                phase = RealtimeRuntimePhase.PAUSED,
                counter = Counter.RESUME_FAILURE,
            )
        if (!result.written) {
            fence.abort(token)
            increment(Counter.RESUME_FAILURE)
            publishPhase(RealtimeRuntimePhase.PAUSED, generation, RealtimeControlCommand.RESUME, RealtimeCommandFailure.WRITE_FAILED)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.RESUME, false, RealtimeCommandFailure.WRITE_FAILED, result)
        }
        if (!isCurrentGeneration(generation, result)) {
            fence.abort(token)
            increment(Counter.RESUME_FAILURE)
            increment(Counter.STALE_COMPLETION)
            publishPhase(RealtimeRuntimePhase.TRANSPORT_CLOSED, transport.currentGeneration, RealtimeControlCommand.RESUME, RealtimeCommandFailure.STALE_GENERATION)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.RESUME, false, RealtimeCommandFailure.STALE_GENERATION, result)
        }

        val failure = commitFailure(
            fence.commit(token) { boundarySink.requestControlBoundary() },
        )
        if (failure != null) {
            increment(Counter.RESUME_FAILURE)
            if (failure == RealtimeCommandFailure.COMMAND_AMBIGUITY) increment(Counter.AMBIGUITY)
            publishPhase(RealtimeRuntimePhase.ERROR, generation, RealtimeControlCommand.RESUME, failure)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.RESUME, false, failure, result)
        }

        increment(Counter.RESUME_SUCCESS)
        publishPhase(RealtimeRuntimePhase.ACTIVE, generation, RealtimeControlCommand.RESUME, null)
        RealtimeCommandOutcome(RealtimeControlCommand.RESUME, true, transportResult = result)
    }

    suspend fun stop(): RealtimeCommandOutcome = commandMutex.withLock {
        val current = mutableSnapshot.value
        if (current.phase == RealtimeRuntimePhase.CLOSED) {
            return@withLock rejected(RealtimeControlCommand.STOP, RealtimeCommandFailure.CLOSED)
        }
        if (current.phase != RealtimeRuntimePhase.ACTIVE && current.phase != RealtimeRuntimePhase.PAUSED) {
            return@withLock rejected(RealtimeControlCommand.STOP, RealtimeCommandFailure.INVALID_PHASE)
        }
        val restorePhase = current.phase
        val generation = current.transportGeneration ?: transport.currentGeneration
            ?: return@withLock rejected(RealtimeControlCommand.STOP, RealtimeCommandFailure.TRANSPORT_NOT_READY)
        publishPhase(RealtimeRuntimePhase.STOPPING, generation, RealtimeControlCommand.STOP, null)
        val result = sendSafely(RealtimeControlCommand.STOP)
            ?: return@withLock failureAfterException(
                command = RealtimeControlCommand.STOP,
                phase = restorePhase,
                counter = Counter.STOP_FAILURE,
            )
        if (!result.written) {
            increment(Counter.STOP_FAILURE)
            publishPhase(restorePhase, generation, RealtimeControlCommand.STOP, RealtimeCommandFailure.WRITE_FAILED)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.STOP, false, RealtimeCommandFailure.WRITE_FAILED, result)
        }
        if (!isCurrentGeneration(generation, result)) {
            increment(Counter.STOP_FAILURE)
            increment(Counter.STALE_COMPLETION)
            publishPhase(RealtimeRuntimePhase.TRANSPORT_CLOSED, transport.currentGeneration, RealtimeControlCommand.STOP, RealtimeCommandFailure.STALE_GENERATION)
            return@withLock RealtimeCommandOutcome(RealtimeControlCommand.STOP, false, RealtimeCommandFailure.STALE_GENERATION, result)
        }
        increment(Counter.STOP_SUCCESS)
        publishPhase(RealtimeRuntimePhase.DRAINING, generation, RealtimeControlCommand.STOP, null)
        RealtimeCommandOutcome(RealtimeControlCommand.STOP, true, transportResult = result)
    }

    /**
     * Stage 15.8 fallback for a device-side recording termination that already stopped TYPE1 media.
     * No transport success is fabricated: this only closes the local audio gate and enters DRAINING.
     */
    suspend fun beginExternalTerminationDrain(): Boolean = commandMutex.withLock {
        val current = mutableSnapshot.value
        if (current.phase != RealtimeRuntimePhase.ACTIVE && current.phase != RealtimeRuntimePhase.PAUSED) {
            return@withLock false
        }
        fence.closeAudioGate()
        publishPhase(
            RealtimeRuntimePhase.DRAINING,
            current.transportGeneration ?: transport.currentGeneration,
            current.lastCommand,
            current.lastFailure,
        )
        true
    }

    suspend fun completeStopDrain() = commandMutex.withLock {
        if (mutableSnapshot.value.phase == RealtimeRuntimePhase.DRAINING) {
            fence.closeAudioGate()
            publishPhase(RealtimeRuntimePhase.IDLE, transport.currentGeneration, RealtimeControlCommand.STOP, null)
        }
    }

    suspend fun onTransportClosed(generation: Long) = commandMutex.withLock {
        fence.closeAudioGate()
        val current = mutableSnapshot.value
        if (current.transportGeneration == generation && current.phase != RealtimeRuntimePhase.CLOSED) {
            publishPhase(RealtimeRuntimePhase.TRANSPORT_CLOSED, null, current.lastCommand, current.lastFailure)
        }
    }

    suspend fun close() = commandMutex.withLock {
        fence.closeAudioGate()
        publishPhase(RealtimeRuntimePhase.CLOSED, null, mutableSnapshot.value.lastCommand, mutableSnapshot.value.lastFailure)
    }

    private suspend fun sendSafely(
        command: RealtimeControlCommand,
        tokenAbort: () -> Unit = {},
    ): RealtimeTransportCommandResult? = try {
        transport.sendRealtimeCommand(command)
    } catch (cancelled: CancellationException) {
        tokenAbort()
        throw cancelled
    } catch (_: Throwable) {
        tokenAbort()
        null
    }

    private fun isCurrentGeneration(
        expectedGeneration: Long,
        result: RealtimeTransportCommandResult,
    ): Boolean =
        result.transportGeneration == expectedGeneration &&
            result.generationStillCurrent &&
            transport.currentGeneration == expectedGeneration

    private fun commitFailure(result: RealtimeCommandFenceCommitResult): RealtimeCommandFailure? =
        when (result) {
            RealtimeCommandFenceCommitResult.COMMITTED -> null
            RealtimeCommandFenceCommitResult.AMBIGUOUS -> RealtimeCommandFailure.COMMAND_AMBIGUITY
            RealtimeCommandFenceCommitResult.DOWNSTREAM_REJECTED -> RealtimeCommandFailure.DOWNSTREAM_REJECTED
            RealtimeCommandFenceCommitResult.COMMIT_ACTION_FAILED,
            RealtimeCommandFenceCommitResult.STALE_TOKEN,
            -> RealtimeCommandFailure.INTERNAL_ERROR
        }

    private fun rejected(
        command: RealtimeControlCommand,
        failure: RealtimeCommandFailure,
    ): RealtimeCommandOutcome {
        mutableSnapshot.value = mutableSnapshot.value.copy(lastCommand = command, lastFailure = failure)
        return RealtimeCommandOutcome(command, false, failure)
    }

    private fun failureAfterException(
        command: RealtimeControlCommand,
        phase: RealtimeRuntimePhase,
        counter: Counter,
    ): RealtimeCommandOutcome {
        increment(counter)
        publishPhase(phase, transport.currentGeneration, command, RealtimeCommandFailure.WRITE_FAILED)
        return RealtimeCommandOutcome(command, false, RealtimeCommandFailure.WRITE_FAILED)
    }

    private fun publishPhase(
        phase: RealtimeRuntimePhase,
        generation: Long?,
        command: RealtimeControlCommand?,
        failure: RealtimeCommandFailure?,
    ) {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            phase = phase,
            transportGeneration = generation,
            lastCommand = command,
            lastFailure = failure,
        )
    }

    private fun increment(counter: Counter) {
        val current = mutableSnapshot.value
        mutableSnapshot.value = when (counter) {
            Counter.START_SUCCESS -> current.copy(startSuccess = current.startSuccess + 1)
            Counter.START_FAILURE -> current.copy(startFailure = current.startFailure + 1)
            Counter.PAUSE_SUCCESS -> current.copy(pauseSuccess = current.pauseSuccess + 1)
            Counter.PAUSE_FAILURE -> current.copy(pauseFailure = current.pauseFailure + 1)
            Counter.RESUME_SUCCESS -> current.copy(resumeSuccess = current.resumeSuccess + 1)
            Counter.RESUME_FAILURE -> current.copy(resumeFailure = current.resumeFailure + 1)
            Counter.STOP_SUCCESS -> current.copy(stopSuccess = current.stopSuccess + 1)
            Counter.STOP_FAILURE -> current.copy(stopFailure = current.stopFailure + 1)
            Counter.STALE_COMPLETION -> current.copy(staleCommandCompletionCount = current.staleCommandCompletionCount + 1)
            Counter.AMBIGUITY -> current.copy(commandAmbiguityCount = current.commandAmbiguityCount + 1)
        }
    }

    private enum class Counter {
        START_SUCCESS,
        START_FAILURE,
        PAUSE_SUCCESS,
        PAUSE_FAILURE,
        RESUME_SUCCESS,
        RESUME_FAILURE,
        STOP_SUCCESS,
        STOP_FAILURE,
        STALE_COMPLETION,
        AMBIGUITY,
    }
}
