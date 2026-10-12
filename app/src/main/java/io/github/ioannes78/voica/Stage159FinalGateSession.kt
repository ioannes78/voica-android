package io.github.ioannes78.voica

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Application-scope QA state for the Stage 15.9 final gate.
 *
 * The holder is created lazily by [AppContainer], so production builds never consume the
 * canonical QA seam unless the internal Final Gate surface is actually opened. Once created,
 * the state survives Activity recreation/reconnect for the lifetime of the app process.
 */
internal class Stage159FinalGateSession(
    private val scope: CoroutineScope,
    private val runtime: RealtimeAudioRuntime,
) {
    private val canonicalAccumulator = Stage15CanonicalProbeAccumulator()
    private val generationAccumulator = Stage159GenerationAccumulator()

    val canonicalSnapshot = MutableStateFlow(Stage15CanonicalProbeSnapshot())
    val baseline = MutableStateFlow<Stage159Baseline?>(null)
    val generationAccounting = MutableStateFlow(Stage159GenerationAccountingSnapshot())
    val heapWindow = MutableStateFlow(Stage159HeapWindow())
    val elapsedMs = MutableStateFlow(0L)

    @Volatile
    var lastDeviceAddress: String? = null

    private var startedAtElapsedMs: Long? = null
    private var finishedAtElapsedMs: Long? = null
    private var nextHeapSampleAtElapsedMs = 0L

    init {
        scope.launch {
            while (isActive) {
                val event = runtime.canonicalSource.receive() ?: break
                canonicalAccumulator.accept(event)
                canonicalSnapshot.value = canonicalAccumulator.snapshot()
            }
        }
        scope.launch {
            while (isActive) {
                observeRuntime(runtime.snapshot())
                updateElapsedAndMemory()
                delay(TRANSPORT_SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun startBaseline(
        reconciler: RealtimeRecordingLifecycleReconcilerSnapshot,
        fileCount: Int,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val runtimeNow = runtime.snapshot()
        val canonicalNow = canonicalAccumulator.snapshot()
        canonicalSnapshot.value = canonicalNow
        val counters = captureStage159Counters(runtimeNow, canonicalNow, reconciler, fileCount)
        baseline.value =
            Stage159Baseline(
                counters = counters,
                transportGeneration = runtimeNow.transportGeneration,
                canonicalSessionId = canonicalNow.lastSessionId,
                fileCount = fileCount,
            )
        generationAccumulator.reset(
            generation = runtimeNow.transportGeneration,
            counters = captureStage159TransportCounters(runtimeNow),
        )
        generationAccounting.value = generationAccumulator.snapshot()
        heapWindow.value = Stage159HeapWindow().withSample(readStage159HeapSample())
        startedAtElapsedMs = nowElapsedMs
        finishedAtElapsedMs = null
        nextHeapSampleAtElapsedMs = nowElapsedMs + HEAP_SAMPLE_INTERVAL_MS
        elapsedMs.value = 0L
    }

    fun finishTiming(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val started = startedAtElapsedMs ?: return
        finishedAtElapsedMs = nowElapsedMs
        elapsedMs.value = (nowElapsedMs - started).coerceAtLeast(0L)
        sampleHeap()
        observeRuntime(runtime.snapshot())
    }

    /** Capture the latest counters before an app-initiated disconnect tears down the transport. */
    fun observeRuntime(snapshot: RealtimeAudioRuntimeSnapshot) {
        if (baseline.value == null) return
        generationAccounting.value =
            generationAccumulator.observe(
                generation = snapshot.transportGeneration,
                counters = captureStage159TransportCounters(snapshot),
            )
    }

    fun deltaFor(current: Stage159CounterSnapshot): Stage159CounterSnapshot? {
        val base = baseline.value ?: return null
        val direct = current.deltaFrom(base.counters)
        return mergeStage159TransportDelta(
            direct = direct,
            transport = generationAccounting.value.transportDelta,
        )
    }

    private fun updateElapsedAndMemory() {
        val started = startedAtElapsedMs ?: return
        val now = SystemClock.elapsedRealtime()
        val end = finishedAtElapsedMs ?: now
        elapsedMs.value = (end - started).coerceAtLeast(0L)
        if (finishedAtElapsedMs == null && now >= nextHeapSampleAtElapsedMs) {
            sampleHeap()
            nextHeapSampleAtElapsedMs = now + HEAP_SAMPLE_INTERVAL_MS
        }
    }

    private fun sampleHeap() {
        heapWindow.value = heapWindow.value.withSample(readStage159HeapSample())
    }

    private companion object {
        const val TRANSPORT_SAMPLE_INTERVAL_MS = 250L
        const val HEAP_SAMPLE_INTERVAL_MS = 30_000L
    }
}

internal data class Stage159Baseline(
    val counters: Stage159CounterSnapshot,
    val transportGeneration: Long?,
    val canonicalSessionId: Long?,
    val fileCount: Int,
)

internal data class Stage159GenerationAccountingSnapshot(
    val baselineGeneration: Long? = null,
    val currentGeneration: Long? = null,
    val generationTransitions: Int = 0,
    val transportDelta: Stage159CounterSnapshot = Stage159CounterSnapshot(),
)

/**
 * Accumulates counters that belong to a transport runtime generation.
 *
 * Reconnect creates a new transport runtime whose ingress/pipeline/command counters start from
 * zero. The completed generation is therefore sealed before the new generation is counted from
 * zero, instead of subtracting the old baseline from the new runtime and producing negatives.
 */
internal class Stage159GenerationAccumulator {
    private val lock = Any()
    private var active = false
    private var baselineGeneration: Long? = null
    private var currentGeneration: Long? = null
    private var generationTransitions = 0
    private var completedDelta = Stage159CounterSnapshot()
    private var segmentBaseline = Stage159CounterSnapshot()
    private var lastObserved = Stage159CounterSnapshot()

    fun reset(
        generation: Long?,
        counters: Stage159CounterSnapshot,
    ) = synchronized(lock) {
        val transport = counters.stage159TransportOnly()
        active = true
        baselineGeneration = generation
        currentGeneration = generation
        generationTransitions = 0
        completedDelta = Stage159CounterSnapshot()
        segmentBaseline = transport
        lastObserved = transport
    }

    fun observe(
        generation: Long?,
        counters: Stage159CounterSnapshot,
    ): Stage159GenerationAccountingSnapshot = synchronized(lock) {
        if (!active) {
            return@synchronized Stage159GenerationAccountingSnapshot(currentGeneration = generation)
        }

        val transport = counters.stage159TransportOnly()
        if (generation == null) {
            // A disconnected/null generation is a boundary in progress. Keep the last counters
            // from the old generation so a zeroed teardown snapshot cannot erase its contribution.
            return@synchronized snapshotLocked()
        }

        if (currentGeneration == null) {
            currentGeneration = generation
            segmentBaseline = transport
            lastObserved = transport
        } else if (generation != currentGeneration) {
            completedDelta =
                addStage159TransportCounters(
                    completedDelta,
                    stage159TransportDelta(lastObserved, segmentBaseline),
                )
            generationTransitions += 1
            currentGeneration = generation
            // A new transport runtime starts its generation-owned counters at zero.
            segmentBaseline = Stage159CounterSnapshot()
            lastObserved = transport
        } else {
            lastObserved = transport
        }
        snapshotLocked()
    }

    fun snapshot(): Stage159GenerationAccountingSnapshot = synchronized(lock) {
        if (!active) {
            Stage159GenerationAccountingSnapshot()
        } else {
            snapshotLocked()
        }
    }

    private fun snapshotLocked(): Stage159GenerationAccountingSnapshot {
        val activeDelta = stage159TransportDelta(lastObserved, segmentBaseline)
        return Stage159GenerationAccountingSnapshot(
            baselineGeneration = baselineGeneration,
            currentGeneration = currentGeneration,
            generationTransitions = generationTransitions,
            transportDelta = addStage159TransportCounters(completedDelta, activeDelta),
        )
    }
}

/** Counters owned by ingress/pipeline/command objects that are recreated with transport runtime. */
internal fun captureStage159TransportCounters(runtime: RealtimeAudioRuntimeSnapshot): Stage159CounterSnapshot {
    val ingress = runtime.ingress
    val pipeline = runtime.pipeline
    val command = runtime.command
    return Stage159CounterSnapshot(
        acceptedBlocks = ingress.acceptedBlocks,
        acceptedBytes = ingress.acceptedBytes,
        opusPackets = pipeline.packetizedPackets,
        decodeSuccess = pipeline.decodeSuccess,
        decodeFailures = pipeline.decodeFailures,
        invalidOpusPackets = pipeline.invalidOpusPackets,
        zeroSampleDecodeFailures = pipeline.zeroSampleDecodeFailures,
        canonicalFrames = pipeline.canonicalFrames,
        canonicalSamples = pipeline.canonicalSamples,
        ingressOverflow = ingress.overflowEvents,
        malformedBlocks = ingress.malformedBlocks,
        droppedBlocks = ingress.droppedBlocks,
        sessions = pipeline.sessionCount,
        epochs = pipeline.epochCount,
        discontinuities = pipeline.discontinuityCount,
        transportBoundaries = pipeline.transportBoundaryCount,
        controlBoundaries = pipeline.controlBoundaryCount,
        decoderRecoveryBoundaries = pipeline.decoderRecoveryBoundaryCount,
        startSuccess = command.startSuccess,
        startFailure = command.startFailure,
        pauseSuccess = command.pauseSuccess,
        pauseFailure = command.pauseFailure,
        resumeSuccess = command.resumeSuccess,
        resumeFailure = command.resumeFailure,
        stopSuccess = command.stopSuccess,
        stopFailure = command.stopFailure,
    )
}

internal fun Stage159CounterSnapshot.stage159TransportOnly(): Stage159CounterSnapshot =
    Stage159CounterSnapshot(
        acceptedBlocks = acceptedBlocks,
        acceptedBytes = acceptedBytes,
        opusPackets = opusPackets,
        decodeSuccess = decodeSuccess,
        decodeFailures = decodeFailures,
        invalidOpusPackets = invalidOpusPackets,
        zeroSampleDecodeFailures = zeroSampleDecodeFailures,
        canonicalFrames = canonicalFrames,
        canonicalSamples = canonicalSamples,
        ingressOverflow = ingressOverflow,
        malformedBlocks = malformedBlocks,
        droppedBlocks = droppedBlocks,
        sessions = sessions,
        epochs = epochs,
        discontinuities = discontinuities,
        transportBoundaries = transportBoundaries,
        controlBoundaries = controlBoundaries,
        decoderRecoveryBoundaries = decoderRecoveryBoundaries,
        startSuccess = startSuccess,
        startFailure = startFailure,
        pauseSuccess = pauseSuccess,
        pauseFailure = pauseFailure,
        resumeSuccess = resumeSuccess,
        resumeFailure = resumeFailure,
        stopSuccess = stopSuccess,
        stopFailure = stopFailure,
    )

internal fun stage159TransportDelta(
    current: Stage159CounterSnapshot,
    baseline: Stage159CounterSnapshot,
): Stage159CounterSnapshot = current.deltaFrom(baseline).stage159TransportOnly()

internal fun addStage159TransportCounters(
    first: Stage159CounterSnapshot,
    second: Stage159CounterSnapshot,
): Stage159CounterSnapshot =
    Stage159CounterSnapshot(
        acceptedBlocks = first.acceptedBlocks + second.acceptedBlocks,
        acceptedBytes = first.acceptedBytes + second.acceptedBytes,
        opusPackets = first.opusPackets + second.opusPackets,
        decodeSuccess = first.decodeSuccess + second.decodeSuccess,
        decodeFailures = first.decodeFailures + second.decodeFailures,
        invalidOpusPackets = first.invalidOpusPackets + second.invalidOpusPackets,
        zeroSampleDecodeFailures = first.zeroSampleDecodeFailures + second.zeroSampleDecodeFailures,
        canonicalFrames = first.canonicalFrames + second.canonicalFrames,
        canonicalSamples = first.canonicalSamples + second.canonicalSamples,
        ingressOverflow = first.ingressOverflow + second.ingressOverflow,
        malformedBlocks = first.malformedBlocks + second.malformedBlocks,
        droppedBlocks = first.droppedBlocks + second.droppedBlocks,
        sessions = first.sessions + second.sessions,
        epochs = first.epochs + second.epochs,
        discontinuities = first.discontinuities + second.discontinuities,
        transportBoundaries = first.transportBoundaries + second.transportBoundaries,
        controlBoundaries = first.controlBoundaries + second.controlBoundaries,
        decoderRecoveryBoundaries = first.decoderRecoveryBoundaries + second.decoderRecoveryBoundaries,
        startSuccess = first.startSuccess + second.startSuccess,
        startFailure = first.startFailure + second.startFailure,
        pauseSuccess = first.pauseSuccess + second.pauseSuccess,
        pauseFailure = first.pauseFailure + second.pauseFailure,
        resumeSuccess = first.resumeSuccess + second.resumeSuccess,
        resumeFailure = first.resumeFailure + second.resumeFailure,
        stopSuccess = first.stopSuccess + second.stopSuccess,
        stopFailure = first.stopFailure + second.stopFailure,
    )

internal fun mergeStage159TransportDelta(
    direct: Stage159CounterSnapshot,
    transport: Stage159CounterSnapshot,
): Stage159CounterSnapshot =
    direct.copy(
        acceptedBlocks = transport.acceptedBlocks,
        acceptedBytes = transport.acceptedBytes,
        opusPackets = transport.opusPackets,
        decodeSuccess = transport.decodeSuccess,
        decodeFailures = transport.decodeFailures,
        invalidOpusPackets = transport.invalidOpusPackets,
        zeroSampleDecodeFailures = transport.zeroSampleDecodeFailures,
        canonicalFrames = transport.canonicalFrames,
        canonicalSamples = transport.canonicalSamples,
        ingressOverflow = transport.ingressOverflow,
        malformedBlocks = transport.malformedBlocks,
        droppedBlocks = transport.droppedBlocks,
        sessions = transport.sessions,
        epochs = transport.epochs,
        discontinuities = transport.discontinuities,
        transportBoundaries = transport.transportBoundaries,
        controlBoundaries = transport.controlBoundaries,
        decoderRecoveryBoundaries = transport.decoderRecoveryBoundaries,
        startSuccess = transport.startSuccess,
        startFailure = transport.startFailure,
        pauseSuccess = transport.pauseSuccess,
        pauseFailure = transport.pauseFailure,
        resumeSuccess = transport.resumeSuccess,
        resumeFailure = transport.resumeFailure,
        stopSuccess = transport.stopSuccess,
        stopFailure = transport.stopFailure,
    )
