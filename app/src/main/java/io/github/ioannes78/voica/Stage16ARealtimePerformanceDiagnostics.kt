package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.RealtimeLocalAudioSideSinkSnapshot
import io.github.ioannes78.voica.transcript.RealtimeStreamingAsrStartupSnapshot
import java.util.Locale

/** Stable, read-only Stage 16A P7 diagnostics projection. */
data class Stage16ARealtimePerformanceDiagnosticsSnapshot(
    val modelId: String,
    val modelLabel: String,
    val effectiveThreads: Int,
    val targetChunkSamples: Int,
    val targetChunkDurationMs: Int,
    val canonicalAudioSeconds: Double,
    val nativeRtf: Double?,
    val partialLatency: Stage16ARealtimeLatencyStatSnapshot,
    val stableLatency: Stage16ARealtimeLatencyStatSnapshot,
    val finalLatency: Stage16ARealtimeLatencyStatSnapshot,
    val correlatedLatencyOutputs: Long,
    val uncorrelatedLatencyOutputs: Long,
    val systemMetrics: Stage16ARealtimeSystemMetricsSnapshot?,
    val currentBufferDepthMs: Double,
    val peakBufferDepthMs: Double,
    val bufferOverflowEvents: Long,
    val bufferDroppedFrames: Long,
    val bufferDroppedSamples: Long,
    val bufferUpstreamDiscontinuities: Long,
    val bufferRejectedAfterFinish: Long,
    val bufferRejectedOversizedFrames: Long,
    val processedEvents: Long,
    val processedPcmFrames: Long,
    val processedPcmSamples: Long,
    val emittedOutputs: Long,
    val outputSinkOffers: Long,
    val outputSinkRejects: Long,
    val outputSinkFailures: Long,
    val pauseCommits: Long,
    val consumedSourceEvents: Long,
    val consumedPcmFrames: Long,
    val consumedPcmSamples: Long,
    val consumedUpstreamDiscontinuities: Long,
    val sideSinkOffers: Long,
    val sideSinkRejects: Long,
    val sideSinkFailures: Long,
    val runnerState: String,
    val terminalReason: String?,
    val lastFailure: String?,
    val asrAcceptCalls: Long,
    val asrDecodeCalls: Long,
    val asrDecodeMs: Double,
    val maxAsrDecodeMs: Double,
    val asrFinishCalls: Long,
    val asrFinishMs: Double,
    val maxAsrFinishMs: Double,
    val vadAcceptCalls: Long,
    val vadFinishCalls: Long,
    val vadFinishMs: Double,
    val maxVadFinishMs: Double,
    val punctuationCalls: Long,
    val punctuationMs: Double,
    val maxPunctuationMs: Double,
    val localAudioSink: RealtimeLocalAudioSideSinkSnapshot? = null,
    val startup: RealtimeStreamingAsrStartupSnapshot? = null,
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A 实时性能]")
        appendLine("model=$modelLabel ($modelId)")
        appendLine("threads=$effectiveThreads")
        appendLine("chunk=$targetChunkSamples samples / ${targetChunkDurationMs}ms")
        appendLine("canonicalAudio=${format3(canonicalAudioSeconds)}s")
        appendLine("nativeRTF=${nativeRtf?.let(::format4) ?: "n/a"}")
        val first = startup
        if (first == null) {
            appendLine("startup=n/a")
        } else {
            appendLine(
                "startup firstPcmSample=${first.firstPcmSampleIndex ?: "n/a"} " +
                    "firstAsrSample=${first.firstAsrAcceptedSampleIndex ?: "n/a"} " +
                    "firstVadSpeechSample=${first.firstVadSpeechSampleIndex ?: "n/a"} " +
                    "firstPartialSample=${first.firstNonEmptyPartialSampleIndex ?: "n/a"}",
            )
            appendLine(
                "startup skippedSamplesBeforeFirstAsrAccept=${first.skippedSamplesBeforeFirstAsrAccept ?: "n/a"} " +
                    "asrAcceptLatency=${formatNullableMs(first.firstAsrAcceptLatencyMs)} " +
                    "vadSpeechLatency=${formatNullableMs(first.firstVadSpeechLatencyMs)} " +
                    "firstPartialLatency=${formatNullableMs(first.firstNonEmptyPartialLatencyMs)}",
            )
        }
        appendLine(formatLatency("PARTIAL", partialLatency))
        appendLine(formatLatency("STABLE", stableLatency))
        appendLine(formatLatency("FINAL", finalLatency))
        appendLine(
            "latencyCorrelation correlated=$correlatedLatencyOutputs " +
                "uncorrelated=$uncorrelatedLatencyOutputs",
        )
        val metrics = systemMetrics
        if (metrics == null) {
            appendLine("systemMetrics=n/a")
        } else {
            appendLine(
                "system elapsed=${metrics.elapsedMs}ms processCpu=${metrics.processCpuMs}ms " +
                    "cpuOneCore=${metrics.processCpuPercentOfOneCore?.let(::format1) ?: "n/a"}%",
            )
            appendLine(
                "pss start=${formatNullableInt(metrics.startPssKb)}KB " +
                    "current=${formatNullableInt(metrics.currentPssKb)}KB " +
                    "delta=${formatNullableSignedInt(metrics.pssDeltaKb)}KB",
            )
            appendLine(
                "thermal start=${formatThermal(metrics.startThermalStatus)} " +
                    "current=${formatThermal(metrics.currentThermalStatus)}",
            )
            appendLine(
                "batteryCapacity start=${formatNullableInt(metrics.startBatteryCapacityPercent)}% " +
                    "current=${formatNullableInt(metrics.currentBatteryCapacityPercent)}% " +
                    "delta=${formatNullableSignedInt(metrics.batteryCapacityDeltaPercent)}%",
            )
            appendLine(
                "batteryCharge start=${formatNullableInt(metrics.startBatteryChargeCounterUah)}uAh " +
                    "current=${formatNullableInt(metrics.currentBatteryChargeCounterUah)}uAh " +
                    "delta=${formatNullableSignedInt(metrics.batteryChargeDeltaUah)}uAh",
            )
        }
        appendLine(
            "buffer=${format1(currentBufferDepthMs)}ms peak=${format1(peakBufferDepthMs)}ms " +
                "overflow=$bufferOverflowEvents droppedFrames=$bufferDroppedFrames " +
                "droppedSamples=$bufferDroppedSamples",
        )
        appendLine(
            "bufferDiscontinuity=$bufferUpstreamDiscontinuities " +
                "rejectedAfterFinish=$bufferRejectedAfterFinish " +
                "rejectedOversized=$bufferRejectedOversizedFrames",
        )
        appendLine(
            "runner=$runnerState events=$processedEvents pcmFrames=$processedPcmFrames " +
                "pcmSamples=$processedPcmSamples outputs=$emittedOutputs pauseCommits=$pauseCommits",
        )
        appendLine(
            "outputSink offers=$outputSinkOffers rejects=$outputSinkRejects failures=$outputSinkFailures",
        )
        appendLine(
            "source events=$consumedSourceEvents pcmFrames=$consumedPcmFrames " +
                "pcmSamples=$consumedPcmSamples discontinuities=$consumedUpstreamDiscontinuities",
        )
        appendLine("sideSink offers=$sideSinkOffers rejects=$sideSinkRejects failures=$sideSinkFailures")
        val localAudio = localAudioSink
        if (localAudio == null) {
            appendLine("localAudio=n/a")
        } else {
            val failure =
                localAudio.lastFailureCode?.let { code ->
                    "${code.name}:${localAudio.lastFailureMessage ?: "n/a"}"
                } ?: "n/a"
            appendLine(
                "localAudio closed=${localAudio.closed} degraded=${localAudio.degraded} " +
                    "activeSession=${localAudio.activeSessionId ?: "n/a"} " +
                    "acceptedEvents=${localAudio.acceptedEvents} " +
                    "acceptedPcmFrames=${localAudio.acceptedPcmFrames} " +
                    "acceptedPcmSamples=${localAudio.acceptedPcmSamples} " +
                    "rejectedEvents=${localAudio.rejectedEvents} " +
                    "finalized=${localAudio.finalizedSegments} " +
                    "salvaged=${localAudio.salvagedFailedSegments} " +
                    "workerFailures=${localAudio.workerFailures} " +
                    "resultHandlerFailures=${localAudio.resultHandlerFailures} " +
                    "lastFailure=$failure",
            )
        }
        appendLine(
            "asr acceptCalls=$asrAcceptCalls decodeCalls=$asrDecodeCalls " +
                "decode=${format1(asrDecodeMs)}ms maxDecode=${format1(maxAsrDecodeMs)}ms " +
                "finishCalls=$asrFinishCalls finish=${format1(asrFinishMs)}ms " +
                "maxFinish=${format1(maxAsrFinishMs)}ms",
        )
        appendLine(
            "vad acceptCalls=$vadAcceptCalls finishCalls=$vadFinishCalls " +
                "finish=${format1(vadFinishMs)}ms maxFinish=${format1(maxVadFinishMs)}ms",
        )
        appendLine(
            "punctuation calls=$punctuationCalls total=${format1(punctuationMs)}ms " +
                "max=${format1(maxPunctuationMs)}ms",
        )
        appendLine("terminalReason=${terminalReason ?: "n/a"}")
        appendLine("lastFailure=${lastFailure ?: "n/a"}")
        Stage16ALifecycleDiagnosticsRegistry.snapshotText()?.let { lifecycle ->
            appendLine()
            append(lifecycle)
        }
    }

    private fun formatLatency(label: String, value: Stage16ARealtimeLatencyStatSnapshot): String =
        "latency$label count=${value.count} avg=${formatNullableMs(value.averageMs)} " +
            "min=${formatNullableMs(value.minMs)} max=${formatNullableMs(value.maxMs)}"

    private fun formatNullableMs(value: Double?): String = value?.let { "${format1(it)}ms" } ?: "n/a"
    private fun formatNullableInt(value: Int?): String = value?.toString() ?: "n/a"
    private fun formatNullableSignedInt(value: Int?): String =
        value?.let { if (it > 0) "+$it" else it.toString() } ?: "n/a"

    private fun formatThermal(value: Int?): String = when (value) {
        null -> "n/a"
        0 -> "NONE(0)"
        1 -> "LIGHT(1)"
        2 -> "MODERATE(2)"
        3 -> "SEVERE(3)"
        4 -> "CRITICAL(4)"
        5 -> "EMERGENCY(5)"
        6 -> "SHUTDOWN(6)"
        else -> "UNKNOWN($value)"
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
    private fun format4(value: Double): String = String.format(Locale.US, "%.4f", value)
}

object Stage16ARealtimePerformanceDiagnostics {
    private val lock = Any()
    private var activeOwner: Any? = null
    private var activeProvider: (() -> Stage16ARealtimePerformanceDiagnosticsSnapshot)? = null
    private var retainedSnapshot: Stage16ARealtimePerformanceDiagnosticsSnapshot? = null

    fun snapshot(): Stage16ARealtimePerformanceDiagnosticsSnapshot? {
        val provider = synchronized(lock) { activeProvider }
        val live = provider?.let { runCatching(it).getOrNull() }
        if (live != null) return live
        return synchronized(lock) { retainedSnapshot }
    }

    internal fun attach(owner: Any, provider: () -> Stage16ARealtimePerformanceDiagnosticsSnapshot) {
        synchronized(lock) {
            activeOwner = owner
            activeProvider = provider
            retainedSnapshot = null
        }
    }

    internal fun retainAndDetach(
        owner: Any,
        snapshot: Stage16ARealtimePerformanceDiagnosticsSnapshot?,
    ) {
        synchronized(lock) {
            if (activeOwner !== owner) return
            if (snapshot != null) retainedSnapshot = snapshot
            activeOwner = null
            activeProvider = null
        }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            activeOwner = null
            activeProvider = null
            retainedSnapshot = null
        }
    }
}

internal fun Stage16ARealtimePipelinePerformanceSnapshot.toDiagnosticsSnapshot(
    outputLatency: Stage16ARealtimeOutputLatencySnapshot = Stage16ARealtimeOutputLatencySnapshot(),
    systemMetrics: Stage16ARealtimeSystemMetricsSnapshot? = null,
    localAudioSink: RealtimeLocalAudioSideSinkSnapshot? = null,
    startup: RealtimeStreamingAsrStartupSnapshot? = null,
): Stage16ARealtimePerformanceDiagnosticsSnapshot {
    val coordinator = runner.coordinator
    return Stage16ARealtimePerformanceDiagnosticsSnapshot(
        modelId = modelId,
        modelLabel = modelLabel,
        effectiveThreads = effectiveThreads,
        targetChunkSamples = targetChunkSamples,
        targetChunkDurationMs = targetChunkDurationMs,
        canonicalAudioSeconds = canonicalAudioSeconds,
        nativeRtf = nativeRtf,
        partialLatency = outputLatency.partial,
        stableLatency = outputLatency.stable,
        finalLatency = outputLatency.final,
        correlatedLatencyOutputs = outputLatency.correlatedOutputs,
        uncorrelatedLatencyOutputs = outputLatency.uncorrelatedOutputs,
        systemMetrics = systemMetrics,
        currentBufferDepthMs = currentBufferDepthMs,
        peakBufferDepthMs = peakBufferDepthMs,
        bufferOverflowEvents = buffer.overflowEvents,
        bufferDroppedFrames = buffer.droppedFrames,
        bufferDroppedSamples = buffer.droppedSamples,
        bufferUpstreamDiscontinuities = buffer.upstreamDiscontinuities,
        bufferRejectedAfterFinish = buffer.rejectedAfterFinish,
        bufferRejectedOversizedFrames = buffer.rejectedOversizedFrames,
        processedEvents = runner.processedEvents,
        processedPcmFrames = runner.processedPcmFrames,
        processedPcmSamples = runner.processedPcmSamples,
        emittedOutputs = runner.emittedOutputs,
        outputSinkOffers = runner.outputSinkOffers,
        outputSinkRejects = runner.outputSinkRejects,
        outputSinkFailures = runner.outputSinkFailures,
        pauseCommits = runner.pauseCommits,
        consumedSourceEvents = coordinator.consumedSourceEvents,
        consumedPcmFrames = coordinator.consumedPcmFrames,
        consumedPcmSamples = coordinator.consumedPcmSamples,
        consumedUpstreamDiscontinuities = coordinator.consumedUpstreamDiscontinuities,
        sideSinkOffers = coordinator.sideSinkOffers,
        sideSinkRejects = coordinator.sideSinkRejects,
        sideSinkFailures = coordinator.sideSinkFailures,
        runnerState = runner.state.name,
        terminalReason = runner.terminalReason?.name,
        lastFailure = runner.lastFailure,
        asrAcceptCalls = native.asrAcceptCalls,
        asrDecodeCalls = native.asrDecodeCalls,
        asrDecodeMs = native.asrDecodeNanos.toMilliseconds(),
        maxAsrDecodeMs = native.maxAsrDecodeNanos.toMilliseconds(),
        asrFinishCalls = native.asrFinishCalls,
        asrFinishMs = native.asrFinishNanos.toMilliseconds(),
        maxAsrFinishMs = native.maxAsrFinishNanos.toMilliseconds(),
        vadAcceptCalls = native.vadAcceptCalls,
        vadFinishCalls = native.vadFinishCalls,
        vadFinishMs = native.vadFinishNanos.toMilliseconds(),
        maxVadFinishMs = native.maxVadFinishNanos.toMilliseconds(),
        punctuationCalls = native.punctuationCalls,
        punctuationMs = native.punctuationNanos.toMilliseconds(),
        maxPunctuationMs = native.maxPunctuationNanos.toMilliseconds(),
        localAudioSink = localAudioSink,
        startup = startup,
    )
}

private fun Long.toMilliseconds(): Double = toDouble() / 1_000_000.0
