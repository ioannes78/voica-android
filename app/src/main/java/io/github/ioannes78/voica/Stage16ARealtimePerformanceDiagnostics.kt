package io.github.ioannes78.voica

import java.util.Locale

/**
 * Stable, read-only Stage 16A P7 diagnostics projection.
 *
 * This type intentionally contains only counters already owned by the realtime pipeline. Reading it
 * never consumes canonical media and never performs file/Room work. It is suitable for the manual
 * diagnostics clipboard used during real-device QA; it does not reintroduce automatic diagnostics
 * export.
 */
data class Stage16ARealtimePerformanceDiagnosticsSnapshot(
    val modelId: String,
    val modelLabel: String,
    val effectiveThreads: Int,
    val targetChunkSamples: Int,
    val targetChunkDurationMs: Int,
    val canonicalAudioSeconds: Double,
    val nativeRtf: Double?,
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
) {
    fun toDiagnosticsText(): String = buildString {
        appendLine("[Stage 16A 实时性能]")
        appendLine("model=$modelLabel ($modelId)")
        appendLine("threads=$effectiveThreads")
        appendLine("chunk=$targetChunkSamples samples / ${targetChunkDurationMs}ms")
        appendLine("canonicalAudio=${format3(canonicalAudioSeconds)}s")
        appendLine("nativeRTF=${nativeRtf?.let(::format4) ?: "n/a"}")
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
        appendLine(
            "sideSink offers=$sideSinkOffers rejects=$sideSinkRejects failures=$sideSinkFailures",
        )
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
        append("lastFailure=${lastFailure ?: "n/a"}")
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
    private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
    private fun format4(value: Double): String = String.format(Locale.US, "%.4f", value)
}

/**
 * Process-local read seam for the manual diagnostics screen.
 *
 * The active provider is pull-only. The last snapshot is retained when a run is released so QA can
 * stop the recording first and then copy the evidence. Owner identity prevents a stale run from
 * replacing a newer run's diagnostics.
 */
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

    internal fun attach(
        owner: Any,
        provider: () -> Stage16ARealtimePerformanceDiagnosticsSnapshot,
    ) {
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

internal fun Stage16ARealtimePipelinePerformanceSnapshot.toDiagnosticsSnapshot():
    Stage16ARealtimePerformanceDiagnosticsSnapshot {
    val coordinator = runner.coordinator
    return Stage16ARealtimePerformanceDiagnosticsSnapshot(
        modelId = modelId,
        modelLabel = modelLabel,
        effectiveThreads = effectiveThreads,
        targetChunkSamples = targetChunkSamples,
        targetChunkDurationMs = targetChunkDurationMs,
        canonicalAudioSeconds = canonicalAudioSeconds,
        nativeRtf = nativeRtf,
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
    )
}

private fun Long.toMilliseconds(): Double = toDouble() / 1_000_000.0
