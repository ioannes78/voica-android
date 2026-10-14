package io.github.ioannes78.voica

import java.util.Locale

/**
 * Manual Stage 16A P7 regression evidence projected from the already-owned Stage 15 runtime.
 *
 * This formatter is deliberately read-only. It does not subscribe to canonical media, reset
 * diagnostics, write files, or create a second realtime consumer.
 */
fun RealtimeAudioRuntimeSnapshot.toStage15RegressionDiagnosticsText(): String = buildString {
    appendLine("[Stage 15 canonical regression]")
    appendLine(
        "runtime phase=$phase generation=${transportGeneration ?: "n/a"} " +
            "creates=$transportRuntimeCreates releases=$transportRuntimeReleases " +
            "transportCloses=$transportCloseCount closeWhileActive=$transportCloseWhileActiveCount",
    )
    appendLine(
        "decoder creates=$decoderCreateCount releases=$decoderReleaseCount",
    )

    appendLine(
        "ingress acceptedBlocks=${ingress.acceptedBlocks} malformed=${ingress.malformedBlocks} " +
            "overflow=${ingress.overflowEvents} droppedBlocks=${ingress.droppedBlocks} " +
            "droppedBytes=${ingress.droppedBytes} rejectedAfterClose=${ingress.rejectedAfterClose}",
    )
    appendLine(
        "ingressQueue blocks=${ingress.queueBlocks} peakBlocks=${ingress.peakQueueBlocks} " +
            "bytes=${ingress.queueBytes} peakBytes=${ingress.peakQueueBytes} " +
            "generationTransitions=${ingress.generationTransitions} sessionClosures=${ingress.sessionClosures}",
    )

    appendLine(
        "canonical receivedBlocks=${pipeline.receivedBlocks} packets=${pipeline.packetizedPackets} " +
            "invalidOpus=${pipeline.invalidOpusPackets} opusShapeAnomalies=${pipeline.opusShapeAnomalies}",
    )
    appendLine(
        "decode success=${pipeline.decodeSuccess} failures=${pipeline.decodeFailures} " +
            "zeroSampleFailures=${pipeline.zeroSampleDecodeFailures} " +
            "unexpectedSampleCount=${pipeline.unexpectedDecodedSampleCount} " +
            "decoderCreateFailures=${pipeline.decoderCreateFailures}",
    )
    appendLine(
        "boundaries discontinuity=${pipeline.discontinuityCount} " +
            "transport=${pipeline.transportBoundaryCount} control=${pipeline.controlBoundaryCount} " +
            "decoderRecovery=${pipeline.decoderRecoveryBoundaryCount}",
    )
    appendLine(
        "canonical frames=${pipeline.canonicalFrames} samples=${pipeline.canonicalSamples} " +
            "duration=${format1(pipeline.canonicalDurationMs)}ms frameObserverFailures=${pipeline.frameObserverFailures}",
    )
    appendLine(
        "canonicalTiming maxBlockProcessing=${format1(pipeline.maxBlockProcessingMs)}ms " +
            "maxArrivalToEmitLag=${format1(pipeline.maxArrivalToEmitLagMs)}ms",
    )
    appendLine("canonicalLastError=${pipeline.lastError ?: "n/a"}")

    appendLine(
        "source active=${source.sessionActive} queuedFrames=${source.queuedPcmFrames} " +
            "peakFrames=${source.peakQueuedPcmFrames} queuedSamples=${source.queuedPcmSamples} " +
            "peakSamples=${source.peakQueuedPcmSamples}",
    )
    appendLine(
        "source offeredFrames=${source.offeredFrames} offeredSamples=${source.offeredSamples} " +
            "overflow=${source.overflowEvents} discontinuities=${source.consumerDiscontinuities} " +
            "droppedFrames=${source.droppedFrames} droppedSamples=${source.droppedSamples} " +
            "rejectedAfterClose=${source.rejectedAfterClose}",
    )
    appendLine(
        "sourceEnd reason=${source.lastSessionEndReason?.name ?: "n/a"} " +
            "drainTimedOut=${source.lastSessionEndDrainTimedOut}",
    )

    appendLine(
        "reconciliation attempts=${reconciliation.attempts} completed=${reconciliation.completed} " +
            "fallback=${reconciliation.fallbackCount} failures=${reconciliation.failure} " +
            "noOp=${reconciliation.noOp}",
    )
    append(
        "reconciliationLast result=${reconciliation.lastResult?.name ?: "n/a"} " +
            "stopFailure=${reconciliation.lastStopFailure?.name ?: "n/a"}",
    )
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
