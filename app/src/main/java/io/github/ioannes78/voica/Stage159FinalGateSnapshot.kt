package io.github.ioannes78.voica

/** Flat, read-only counter sample used by the Stage 15.9 final stability gate. */
data class Stage159CounterSnapshot(
    val acceptedBlocks: Long = 0,
    val acceptedBytes: Long = 0,
    val opusPackets: Long = 0,
    val decodeSuccess: Long = 0,
    val decodeFailures: Long = 0,
    val invalidOpusPackets: Long = 0,
    val zeroSampleDecodeFailures: Long = 0,
    val canonicalFrames: Long = 0,
    val canonicalSamples: Long = 0,
    val ingressOverflow: Long = 0,
    val malformedBlocks: Long = 0,
    val droppedBlocks: Long = 0,
    val sourceOverflow: Long = 0,
    val sourceDroppedFrames: Long = 0,
    val sessions: Long = 0,
    val epochs: Long = 0,
    val discontinuities: Long = 0,
    val transportBoundaries: Long = 0,
    val controlBoundaries: Long = 0,
    val decoderRecoveryBoundaries: Long = 0,
    val sourceSessionStarted: Long = 0,
    val sourceSessionEnded: Long = 0,
    val consumerDiscontinuities: Long = 0,
    val canonicalGaps: Long = 0,
    val canonicalOverlaps: Long = 0,
    val invalidEpochStarts: Long = 0,
    val sessionEndMismatches: Long = 0,
    val drainTimeoutEnds: Long = 0,
    val reconciliationAttempts: Long = 0,
    val reconciliationCompleted: Long = 0,
    val reconciliationFallback: Long = 0,
    val reconciliationFailures: Long = 0,
    val appReconciliationRequested: Long = 0,
    val appReconciliationCompleted: Long = 0,
    val appReconciliationFallback: Long = 0,
    val appReconciliationFailures: Long = 0,
    val startSuccess: Long = 0,
    val startFailure: Long = 0,
    val pauseSuccess: Long = 0,
    val pauseFailure: Long = 0,
    val resumeSuccess: Long = 0,
    val resumeFailure: Long = 0,
    val stopSuccess: Long = 0,
    val stopFailure: Long = 0,
    val fileCount: Long = 0,
) {
    fun deltaFrom(baseline: Stage159CounterSnapshot): Stage159CounterSnapshot =
        Stage159CounterSnapshot(
            acceptedBlocks = acceptedBlocks - baseline.acceptedBlocks,
            acceptedBytes = acceptedBytes - baseline.acceptedBytes,
            opusPackets = opusPackets - baseline.opusPackets,
            decodeSuccess = decodeSuccess - baseline.decodeSuccess,
            decodeFailures = decodeFailures - baseline.decodeFailures,
            invalidOpusPackets = invalidOpusPackets - baseline.invalidOpusPackets,
            zeroSampleDecodeFailures = zeroSampleDecodeFailures - baseline.zeroSampleDecodeFailures,
            canonicalFrames = canonicalFrames - baseline.canonicalFrames,
            canonicalSamples = canonicalSamples - baseline.canonicalSamples,
            ingressOverflow = ingressOverflow - baseline.ingressOverflow,
            malformedBlocks = malformedBlocks - baseline.malformedBlocks,
            droppedBlocks = droppedBlocks - baseline.droppedBlocks,
            sourceOverflow = sourceOverflow - baseline.sourceOverflow,
            sourceDroppedFrames = sourceDroppedFrames - baseline.sourceDroppedFrames,
            sessions = sessions - baseline.sessions,
            epochs = epochs - baseline.epochs,
            discontinuities = discontinuities - baseline.discontinuities,
            transportBoundaries = transportBoundaries - baseline.transportBoundaries,
            controlBoundaries = controlBoundaries - baseline.controlBoundaries,
            decoderRecoveryBoundaries = decoderRecoveryBoundaries - baseline.decoderRecoveryBoundaries,
            sourceSessionStarted = sourceSessionStarted - baseline.sourceSessionStarted,
            sourceSessionEnded = sourceSessionEnded - baseline.sourceSessionEnded,
            consumerDiscontinuities = consumerDiscontinuities - baseline.consumerDiscontinuities,
            canonicalGaps = canonicalGaps - baseline.canonicalGaps,
            canonicalOverlaps = canonicalOverlaps - baseline.canonicalOverlaps,
            invalidEpochStarts = invalidEpochStarts - baseline.invalidEpochStarts,
            sessionEndMismatches = sessionEndMismatches - baseline.sessionEndMismatches,
            drainTimeoutEnds = drainTimeoutEnds - baseline.drainTimeoutEnds,
            reconciliationAttempts = reconciliationAttempts - baseline.reconciliationAttempts,
            reconciliationCompleted = reconciliationCompleted - baseline.reconciliationCompleted,
            reconciliationFallback = reconciliationFallback - baseline.reconciliationFallback,
            reconciliationFailures = reconciliationFailures - baseline.reconciliationFailures,
            appReconciliationRequested = appReconciliationRequested - baseline.appReconciliationRequested,
            appReconciliationCompleted = appReconciliationCompleted - baseline.appReconciliationCompleted,
            appReconciliationFallback = appReconciliationFallback - baseline.appReconciliationFallback,
            appReconciliationFailures = appReconciliationFailures - baseline.appReconciliationFailures,
            startSuccess = startSuccess - baseline.startSuccess,
            startFailure = startFailure - baseline.startFailure,
            pauseSuccess = pauseSuccess - baseline.pauseSuccess,
            pauseFailure = pauseFailure - baseline.pauseFailure,
            resumeSuccess = resumeSuccess - baseline.resumeSuccess,
            resumeFailure = resumeFailure - baseline.resumeFailure,
            stopSuccess = stopSuccess - baseline.stopSuccess,
            stopFailure = stopFailure - baseline.stopFailure,
            fileCount = fileCount - baseline.fileCount,
        )

    fun anomalyCount(): Long =
        decodeFailures +
            invalidOpusPackets +
            zeroSampleDecodeFailures +
            ingressOverflow +
            malformedBlocks +
            droppedBlocks +
            sourceOverflow +
            sourceDroppedFrames +
            canonicalGaps +
            canonicalOverlaps +
            invalidEpochStarts +
            sessionEndMismatches +
            drainTimeoutEnds +
            reconciliationFailures +
            appReconciliationFailures
}

data class Stage159HeapSample(
    val usedBytes: Long,
    val maxBytes: Long,
)

data class Stage159HeapWindow(
    val startBytes: Long = 0,
    val currentBytes: Long = 0,
    val peakBytes: Long = 0,
    val maxBytes: Long = 0,
) {
    fun withSample(sample: Stage159HeapSample): Stage159HeapWindow =
        if (startBytes == 0L) {
            Stage159HeapWindow(
                startBytes = sample.usedBytes,
                currentBytes = sample.usedBytes,
                peakBytes = sample.usedBytes,
                maxBytes = sample.maxBytes,
            )
        } else {
            copy(
                currentBytes = sample.usedBytes,
                peakBytes = maxOf(peakBytes, sample.usedBytes),
                maxBytes = sample.maxBytes,
            )
        }
}

internal fun captureStage159Counters(
    runtime: RealtimeAudioRuntimeSnapshot,
    canonical: Stage15CanonicalProbeSnapshot,
    reconciler: RealtimeRecordingLifecycleReconcilerSnapshot,
    fileCount: Int,
): Stage159CounterSnapshot {
    val command = runtime.command
    val ingress = runtime.ingress
    val pipeline = runtime.pipeline
    val source = runtime.source
    val reconciliation = runtime.reconciliation
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
        sourceOverflow = source.overflowEvents,
        sourceDroppedFrames = source.droppedFrames,
        sessions = pipeline.sessionCount,
        epochs = pipeline.epochCount,
        discontinuities = pipeline.discontinuityCount,
        transportBoundaries = pipeline.transportBoundaryCount,
        controlBoundaries = pipeline.controlBoundaryCount,
        decoderRecoveryBoundaries = pipeline.decoderRecoveryBoundaryCount,
        sourceSessionStarted = source.sessionStartedEvents,
        sourceSessionEnded = source.sessionEndedEvents,
        consumerDiscontinuities = source.consumerDiscontinuities,
        canonicalGaps = canonical.gapCount,
        canonicalOverlaps = canonical.overlapCount,
        invalidEpochStarts = canonical.invalidEpochStartCount,
        sessionEndMismatches = canonical.sessionEndMismatchCount,
        drainTimeoutEnds = canonical.drainTimeoutEnds,
        reconciliationAttempts = reconciliation.attempts,
        reconciliationCompleted = reconciliation.completed,
        reconciliationFallback = reconciliation.fallbackCount,
        reconciliationFailures = reconciliation.failure,
        appReconciliationRequested = reconciler.reconciliationRequested,
        appReconciliationCompleted = reconciler.reconciliationCompleted,
        appReconciliationFallback = reconciler.reconciliationFallbackCompleted,
        appReconciliationFailures = reconciler.reconciliationFailure,
        startSuccess = command.startSuccess,
        startFailure = command.startFailure,
        pauseSuccess = command.pauseSuccess,
        pauseFailure = command.pauseFailure,
        resumeSuccess = command.resumeSuccess,
        resumeFailure = command.resumeFailure,
        stopSuccess = command.stopSuccess,
        stopFailure = command.stopFailure,
        fileCount = fileCount.toLong(),
    )
}

internal fun readStage159HeapSample(): Stage159HeapSample {
    val runtime = Runtime.getRuntime()
    return Stage159HeapSample(
        usedBytes = runtime.totalMemory() - runtime.freeMemory(),
        maxBytes = runtime.maxMemory(),
    )
}

internal fun stage159QaEntryVisible(
    debuggable: Boolean,
    applicationId: String,
): Boolean = debuggable || applicationId.endsWith(".qa")
