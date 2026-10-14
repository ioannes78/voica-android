package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInspector
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSource
import io.github.ioannes78.voica.audio.RealtimeCanonicalAudioSourceSnapshot
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndResult
import io.github.ioannes78.voica.ble.RealtimeAudioConsumer
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeAudioIngressSnapshot
import io.github.ioannes78.voica.ble.RealtimeAudioOfferResult
import io.github.ioannes78.voica.ble.RealtimeCommandFence
import io.github.ioannes78.voica.ble.RealtimeCommandFenceSnapshot
import io.github.ioannes78.voica.ble.RealtimeControlCommand
import io.github.ioannes78.voica.ble.RealtimeDeviceTransport
import io.github.ioannes78.voica.ble.RoutedNotification
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RealtimeRecordingReconciliationResult {
    COMPLETED_AFTER_STOP,
    COMPLETED_WITH_FALLBACK,
    NO_OP,
    FAILED,
}

data class RealtimeRecordingReconciliationOutcome(
    val result: RealtimeRecordingReconciliationResult,
    val stopOutcome: RealtimeCommandOutcome? = null,
    val endResult: RealtimeCanonicalSessionEndResult? = null,
)

data class RealtimeRecordingReconciliationSnapshot(
    val attempts: Long = 0,
    val stopWriteSuccess: Long = 0,
    val stopWriteFailure: Long = 0,
    val fallbackCount: Long = 0,
    val completed: Long = 0,
    val noOp: Long = 0,
    val failure: Long = 0,
    val lastResult: RealtimeRecordingReconciliationResult? = null,
    val lastStopFailure: RealtimeCommandFailure? = null,
)

data class RealtimeAudioRuntimeSnapshot(
    val closed: Boolean = false,
    val transportGeneration: Long? = null,
    val transportRuntimeCreates: Long = 0,
    val transportRuntimeReleases: Long = 0,
    val transportCloseCount: Long = 0,
    val transportCloseWhileActiveCount: Long = 0,
    val decoderCreateCount: Long = 0,
    val decoderReleaseCount: Long = 0,
    val phase: RealtimeRuntimePhase = RealtimeRuntimePhase.IDLE,
    val command: RealtimeCommandControllerSnapshot = RealtimeCommandControllerSnapshot(),
    val fence: RealtimeCommandFenceSnapshot = RealtimeCommandFenceSnapshot(),
    val ingress: RealtimeAudioIngressSnapshot = RealtimeAudioIngressSnapshot(),
    val pipeline: RealtimeCanonicalAudioPipelineSnapshot = RealtimeCanonicalAudioPipelineSnapshot(),
    val source: RealtimeCanonicalAudioSourceSnapshot = RealtimeCanonicalAudioSourceSnapshot(),
    val reconciliation: RealtimeRecordingReconciliationSnapshot = RealtimeRecordingReconciliationSnapshot(),
)

/**
 * Application-scoped Stage 15 realtime media owner.
 *
 * A transport generation owns its ingress, command fence, canonical pipeline and decoder worker.
 * The canonical source survives transport generations and exposes one stable Stage16-facing stream.
 */
class RealtimeAudioRuntime(
    private val scope: CoroutineScope,
    private val transport: RealtimeDeviceTransport,
    private val packetInspector: OpusPacketInspector,
    decoderFactory: OpusDecoderFactory,
    val canonicalSource: RealtimeCanonicalAudioSource = RealtimeCanonicalAudioSource(),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lifecycleMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private val logicalSessionIds = AtomicLong(0)
    private val statsLock = Any()
    private val trackingDecoderFactory = TrackingDecoderFactory(decoderFactory)

    @Volatile
    private var closed = false

    @Volatile
    private var owner: TransportOwner? = null

    @Volatile
    private var phaseWithoutOwner = RealtimeRuntimePhase.IDLE

    @Volatile
    private var lastCommandSnapshot = RealtimeCommandControllerSnapshot()

    @Volatile
    private var lastFenceSnapshot = RealtimeCommandFenceSnapshot()

    @Volatile
    private var lastIngressSnapshot = RealtimeAudioIngressSnapshot()

    @Volatile
    private var lastPipelineSnapshot = RealtimeCanonicalAudioPipelineSnapshot()

    @Volatile
    private var lastTransportGeneration: Long? = null

    private var transportRuntimeCreates = 0L
    private var transportRuntimeReleases = 0L
    private var transportCloseCount = 0L
    private var transportCloseWhileActiveCount = 0L
    private var decoderCreateCount = 0L
    private var decoderReleaseCount = 0L
    private var reconciliationAttempts = 0L
    private var reconciliationStopWriteSuccess = 0L
    private var reconciliationStopWriteFailure = 0L
    private var reconciliationFallbackCount = 0L
    private var reconciliationCompleted = 0L
    private var reconciliationNoOp = 0L
    private var reconciliationFailure = 0L
    private var reconciliationLastResult: RealtimeRecordingReconciliationResult? = null
    private var reconciliationLastStopFailure: RealtimeCommandFailure? = null

    suspend fun start(): RealtimeCommandOutcome {
        val current = ensureOwner()
            ?: return unavailable(RealtimeControlCommand.START)
        return current.controller.start()
    }

    /**
     * Local-only reconnect seam. The caller must invoke this only after Stage 16 has confirmed that
     * the newly connected transport belongs to the same still-active physical device recording.
     * This method does not send START/RESUME to the recorder card.
     */
    suspend fun attachConfirmedExistingRecording(): RealtimeExistingRecordingAttachOutcome {
        val current = ensureOwner()
            ?: return unavailableExistingRecordingAttach()
        return current.controller.attachConfirmedExistingRecording()
    }

    suspend fun pause(): RealtimeCommandOutcome {
        val current = currentOwner()
            ?: return unavailable(RealtimeControlCommand.PAUSE)
        return current.controller.pause()
    }

    suspend fun resume(): RealtimeCommandOutcome {
        val current = currentOwner()
            ?: return unavailable(RealtimeControlCommand.RESUME)
        return current.controller.resume()
    }

    suspend fun stop(): RealtimeCommandOutcome {
        val current = currentOwner()
            ?: return unavailable(RealtimeControlCommand.STOP)
        val command = current.controller.stop()
        if (!command.success) return command

        val endResult = canonicalSource.endActiveSessionAfterQuiet(
            reason = RealtimeCanonicalSessionEndReason.STOP,
        )
        if (endResult.isTerminalDrainResult()) {
            current.controller.completeStopDrain()
        }
        return command
    }

    /**
     * Stage 15.8 convergence path for a trusted device Recording/Paused -> Idle transition.
     *
     * D2 proved that TYPE3 SAVE can stop device TYPE1 media while the app runtime remains ACTIVE.
     * We first best-effort the normal TYPE1 STOP transaction. If that write fails while the same
     * owner is still logically ACTIVE/PAUSED, the device Idle fact remains authoritative, so the
     * local audio gate and canonical lifecycle are closed without fabricating a successful STOP.
     */
    suspend fun reconcileDeviceRecordingEnded(): RealtimeRecordingReconciliationOutcome =
        reconciliationMutex.withLock {
            val current = currentOwner()
                ?: return@withLock recordReconciliationNoOp()
            val initialPhase = current.controller.snapshot.value.phase
            if (!initialPhase.isRecordingReconciliationEligible()) {
                return@withLock recordReconciliationNoOp()
            }

            synchronized(statsLock) { reconciliationAttempts += 1 }
            val stopOutcome = current.controller.stop()
            if (stopOutcome.success) {
                synchronized(statsLock) {
                    reconciliationStopWriteSuccess += 1
                    reconciliationLastStopFailure = null
                }
                val endResult = canonicalSource.endActiveSessionAfterQuiet(
                    reason = RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED,
                )
                if (endResult.isTerminalDrainResult()) {
                    current.controller.completeStopDrain()
                    return@withLock recordReconciliationCompleted(
                        result = RealtimeRecordingReconciliationResult.COMPLETED_AFTER_STOP,
                        stopOutcome = stopOutcome,
                        endResult = endResult,
                    )
                }
                return@withLock recordReconciliationFailure(stopOutcome, endResult)
            }

            val phaseAfterStopAttempt = current.controller.snapshot.value.phase
            if (
                stopOutcome.failure == RealtimeCommandFailure.INVALID_PHASE &&
                !phaseAfterStopAttempt.isRecordingReconciliationEligible()
            ) {
                return@withLock recordReconciliationNoOp(stopOutcome)
            }

            synchronized(statsLock) {
                reconciliationStopWriteFailure += 1
                reconciliationLastStopFailure = stopOutcome.failure
            }

            if (!current.controller.beginExternalTerminationDrain()) {
                val phaseAfterFallbackAttempt = current.controller.snapshot.value.phase
                return@withLock if (!phaseAfterFallbackAttempt.isRecordingReconciliationEligible()) {
                    recordReconciliationNoOp(stopOutcome)
                } else {
                    recordReconciliationFailure(stopOutcome, null)
                }
            }

            synchronized(statsLock) { reconciliationFallbackCount += 1 }
            val endResult = canonicalSource.endActiveSessionAfterQuiet(
                reason = RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED,
            )
            if (endResult.isTerminalDrainResult()) {
                current.controller.completeStopDrain()
                return@withLock recordReconciliationCompleted(
                    result = RealtimeRecordingReconciliationResult.COMPLETED_WITH_FALLBACK,
                    stopOutcome = stopOutcome,
                    endResult = endResult,
                )
            }
            recordReconciliationFailure(stopOutcome, endResult)
        }

    fun snapshot(): RealtimeAudioRuntimeSnapshot {
        val current = owner
        val counters = synchronized(statsLock) {
            RuntimeCounters(
                transportRuntimeCreates = transportRuntimeCreates,
                transportRuntimeReleases = transportRuntimeReleases,
                transportCloseCount = transportCloseCount,
                transportCloseWhileActiveCount = transportCloseWhileActiveCount,
                decoderCreateCount = decoderCreateCount,
                decoderReleaseCount = decoderReleaseCount,
                reconciliation = RealtimeRecordingReconciliationSnapshot(
                    attempts = reconciliationAttempts,
                    stopWriteSuccess = reconciliationStopWriteSuccess,
                    stopWriteFailure = reconciliationStopWriteFailure,
                    fallbackCount = reconciliationFallbackCount,
                    completed = reconciliationCompleted,
                    noOp = reconciliationNoOp,
                    failure = reconciliationFailure,
                    lastResult = reconciliationLastResult,
                    lastStopFailure = reconciliationLastStopFailure,
                ),
            )
        }
        val liveGeneration = transport.currentGeneration
        val phase = when {
            current != null -> current.controller.snapshot.value.phase
            closed -> RealtimeRuntimePhase.CLOSED
            liveGeneration != null -> RealtimeRuntimePhase.IDLE
            else -> phaseWithoutOwner
        }
        return RealtimeAudioRuntimeSnapshot(
            closed = closed,
            transportGeneration = current?.generation ?: liveGeneration ?: lastTransportGeneration,
            transportRuntimeCreates = counters.transportRuntimeCreates,
            transportRuntimeReleases = counters.transportRuntimeReleases,
            transportCloseCount = counters.transportCloseCount,
            transportCloseWhileActiveCount = counters.transportCloseWhileActiveCount,
            decoderCreateCount = counters.decoderCreateCount,
            decoderReleaseCount = counters.decoderReleaseCount,
            phase = phase,
            command = current?.controller?.snapshot?.value ?: lastCommandSnapshot,
            fence = current?.fence?.snapshot() ?: lastFenceSnapshot,
            ingress = current?.ingress?.snapshot() ?: lastIngressSnapshot,
            pipeline = current?.pipeline?.snapshot() ?: lastPipelineSnapshot,
            source = canonicalSource.snapshot(),
            reconciliation = counters.reconciliation,
        )
    }

    /** Process/runtime teardown. Transport resources are joined before the source closes. */
    suspend fun close() {
        lifecycleMutex.withLock {
            if (closed) return
            closed = true
            owner?.let {
                closeOwnerLocked(it, RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED)
                owner = null
            }
            canonicalSource.endActiveSessionImmediately(RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED)
            canonicalSource.close()
            phaseWithoutOwner = RealtimeRuntimePhase.CLOSED
        }
    }

    private suspend fun currentOwner(): TransportOwner? = lifecycleMutex.withLock {
        if (closed) return@withLock null
        val current = owner ?: return@withLock null
        if (transport.currentGeneration != current.generation) {
            recordTransportClose(current.controller.snapshot.value.phase)
            closeOwnerLocked(current, RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED)
            owner = null
            phaseWithoutOwner = RealtimeRuntimePhase.TRANSPORT_CLOSED
            return@withLock null
        }
        current
    }

    private suspend fun ensureOwner(): TransportOwner? = lifecycleMutex.withLock {
        if (closed) return@withLock null
        val generation = transport.currentGeneration ?: return@withLock null
        val existing = owner
        if (existing?.generation == generation) return@withLock existing
        if (existing != null) {
            recordTransportClose(existing.controller.snapshot.value.phase)
            closeOwnerLocked(existing, RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED)
            owner = null
            phaseWithoutOwner = RealtimeRuntimePhase.TRANSPORT_CLOSED
        }

        val ingress = RealtimeAudioIngress()
        val localToGlobalSession = mutableMapOf<Long, Long>()
        val pipeline = RealtimeCanonicalAudioPipeline(
            ingress = ingress,
            packetInspector = packetInspector,
            decoderFactory = trackingDecoderFactory,
            onFrame = { frame ->
                val globalSessionId = localToGlobalSession.getOrPut(frame.sessionId) {
                    logicalSessionIds.incrementAndGet()
                }
                canonicalSource.offer(frame.withSessionId(globalSessionId))
            },
        )
        val fence = RealtimeCommandFence(ingress)
        val controller = RealtimeCommandController(
            transport = transport,
            fence = fence,
            boundarySink = object : RealtimeCommandBoundarySink {
                override fun beginRealtimeSession(): Long = pipeline.beginRealtimeSession()
                override fun requestControlBoundary(): Long = pipeline.requestControlBoundary()
            },
        )
        val consumer = RuntimeTransportConsumer(
            generation = generation,
            fence = fence,
        )

        if (!transport.registerRealtimeAudioConsumer(consumer)) {
            ingress.close()
            pipeline.close()
            return@withLock null
        }

        val created = TransportOwner(
            generation = generation,
            ingress = ingress,
            pipeline = pipeline,
            fence = fence,
            controller = controller,
            consumer = consumer,
        )
        owner = created
        lastTransportGeneration = generation
        phaseWithoutOwner = RealtimeRuntimePhase.IDLE
        synchronized(statsLock) { transportRuntimeCreates += 1 }

        // Publish before the final generation check. If a close callback happened between consumer
        // registration and owner publication, it may have observed no owner; this second check catches
        // that race. Any later callback is serialized behind lifecycleMutex.
        if (transport.currentGeneration != generation) {
            recordTransportClose(created.controller.snapshot.value.phase)
            closeOwnerLocked(created, RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED)
            owner = null
            phaseWithoutOwner = RealtimeRuntimePhase.TRANSPORT_CLOSED
            return@withLock null
        }

        created.worker = scope.launch(workerDispatcher) { pipeline.run() }
        created
    }

    private suspend fun handleTransportClosed(generation: Long) {
        lifecycleMutex.withLock {
            val current = owner ?: return@withLock
            if (current.generation != generation) return@withLock
            recordTransportClose(current.controller.snapshot.value.phase)
            closeOwnerLocked(current, RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED)
            owner = null
            phaseWithoutOwner = RealtimeRuntimePhase.TRANSPORT_CLOSED
        }
    }

    /** lifecycleMutex must be held. */
    private suspend fun closeOwnerLocked(
        current: TransportOwner,
        endReason: RealtimeCanonicalSessionEndReason,
    ) {
        transport.unregisterRealtimeAudioConsumer(current.consumer)
        current.fence.onSessionClosed(current.generation)
        current.ingress.close()
        current.pipeline.close()
        current.worker?.join()
        canonicalSource.endActiveSessionImmediately(endReason)
        current.controller.onTransportClosed(current.generation)
        lastTransportGeneration = current.generation
        lastCommandSnapshot = current.controller.snapshot.value
        lastFenceSnapshot = current.fence.snapshot()
        lastIngressSnapshot = current.ingress.snapshot()
        lastPipelineSnapshot = current.pipeline.snapshot()
        synchronized(statsLock) { transportRuntimeReleases += 1 }
    }

    private fun recordTransportClose(phase: RealtimeRuntimePhase) = synchronized(statsLock) {
        transportCloseCount += 1
        if (phase.isMediaSessionInFlight()) {
            transportCloseWhileActiveCount += 1
        }
    }

    private fun recordReconciliationNoOp(
        stopOutcome: RealtimeCommandOutcome? = null,
    ): RealtimeRecordingReconciliationOutcome {
        synchronized(statsLock) {
            reconciliationNoOp += 1
            reconciliationLastResult = RealtimeRecordingReconciliationResult.NO_OP
            reconciliationLastStopFailure = stopOutcome?.failure
        }
        return RealtimeRecordingReconciliationOutcome(
            result = RealtimeRecordingReconciliationResult.NO_OP,
            stopOutcome = stopOutcome,
        )
    }

    private fun recordReconciliationCompleted(
        result: RealtimeRecordingReconciliationResult,
        stopOutcome: RealtimeCommandOutcome,
        endResult: RealtimeCanonicalSessionEndResult,
    ): RealtimeRecordingReconciliationOutcome {
        synchronized(statsLock) {
            reconciliationCompleted += 1
            reconciliationLastResult = result
        }
        return RealtimeRecordingReconciliationOutcome(
            result = result,
            stopOutcome = stopOutcome,
            endResult = endResult,
        )
    }

    private fun recordReconciliationFailure(
        stopOutcome: RealtimeCommandOutcome?,
        endResult: RealtimeCanonicalSessionEndResult?,
    ): RealtimeRecordingReconciliationOutcome {
        synchronized(statsLock) {
            reconciliationFailure += 1
            reconciliationLastResult = RealtimeRecordingReconciliationResult.FAILED
            reconciliationLastStopFailure = stopOutcome?.failure
        }
        return RealtimeRecordingReconciliationOutcome(
            result = RealtimeRecordingReconciliationResult.FAILED,
            stopOutcome = stopOutcome,
            endResult = endResult,
        )
    }

    private fun unavailable(command: RealtimeControlCommand): RealtimeCommandOutcome =
        RealtimeCommandOutcome(
            command = command,
            success = false,
            failure = if (closed) {
                RealtimeCommandFailure.CLOSED
            } else {
                RealtimeCommandFailure.TRANSPORT_NOT_READY
            },
        )

    private fun unavailableExistingRecordingAttach(): RealtimeExistingRecordingAttachOutcome =
        RealtimeExistingRecordingAttachOutcome(
            success = false,
            failure = if (closed) {
                RealtimeExistingRecordingAttachFailure.CLOSED
            } else {
                RealtimeExistingRecordingAttachFailure.TRANSPORT_NOT_READY
            },
            transportGeneration = transport.currentGeneration,
        )

    private fun RealtimeCanonicalPcmFrame.withSessionId(sessionId: Long): RealtimeCanonicalPcmFrame =
        copy(sessionId = sessionId)

    private fun RealtimeCanonicalSessionEndResult.isTerminalDrainResult(): Boolean =
        this == RealtimeCanonicalSessionEndResult.ENDED ||
            this == RealtimeCanonicalSessionEndResult.ENDED_AFTER_TIMEOUT ||
            this == RealtimeCanonicalSessionEndResult.NO_ACTIVE_SESSION

    private fun RealtimeRuntimePhase.isRecordingReconciliationEligible(): Boolean =
        this == RealtimeRuntimePhase.ACTIVE || this == RealtimeRuntimePhase.PAUSED

    private fun RealtimeRuntimePhase.isMediaSessionInFlight(): Boolean =
        this == RealtimeRuntimePhase.STARTING ||
            this == RealtimeRuntimePhase.ACTIVE ||
            this == RealtimeRuntimePhase.PAUSING ||
            this == RealtimeRuntimePhase.PAUSED ||
            this == RealtimeRuntimePhase.RESUMING ||
            this == RealtimeRuntimePhase.STOPPING ||
            this == RealtimeRuntimePhase.DRAINING

    private inner class RuntimeTransportConsumer(
        private val generation: Long,
        private val fence: RealtimeCommandFence,
    ) : RealtimeAudioConsumer {
        override fun offer(
            notification: RoutedNotification,
            sessionGeneration: Long,
            receivedAtNanos: Long,
        ): RealtimeAudioOfferResult =
            fence.offer(notification, sessionGeneration, receivedAtNanos)

        override fun onSessionClosed(sessionGeneration: Long) {
            fence.onSessionClosed(sessionGeneration)
            if (sessionGeneration != generation) return
            scope.launch { handleTransportClosed(generation) }
        }
    }

    private inner class TrackingDecoderFactory(
        private val delegate: OpusDecoderFactory,
    ) : OpusDecoderFactory {
        override val version: String
            get() = delegate.version

        override fun create(sampleRateHz: Int, channelCount: Int): OpusDecoder {
            val decoder = delegate.create(sampleRateHz, channelCount)
            synchronized(statsLock) { decoderCreateCount += 1 }
            return TrackingDecoder(decoder)
        }
    }

    private inner class TrackingDecoder(
        private val delegate: OpusDecoder,
    ) : OpusDecoder {
        private val released = AtomicBoolean(false)

        override val sampleRateHz: Int
            get() = delegate.sampleRateHz

        override val channelCount: Int
            get() = delegate.channelCount

        override fun decode(packet: ByteArray): ShortArray = delegate.decode(packet)

        override fun reset() = delegate.reset()

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            try {
                delegate.close()
            } finally {
                synchronized(statsLock) { decoderReleaseCount += 1 }
            }
        }
    }

    private class TransportOwner(
        val generation: Long,
        val ingress: RealtimeAudioIngress,
        val pipeline: RealtimeCanonicalAudioPipeline,
        val fence: RealtimeCommandFence,
        val controller: RealtimeCommandController,
        val consumer: RealtimeAudioConsumer,
    ) {
        var worker: Job? = null
    }

    private data class RuntimeCounters(
        val transportRuntimeCreates: Long,
        val transportRuntimeReleases: Long,
        val transportCloseCount: Long,
        val transportCloseWhileActiveCount: Long,
        val decoderCreateCount: Long,
        val decoderReleaseCount: Long,
        val reconciliation: RealtimeRecordingReconciliationSnapshot,
    )
}
