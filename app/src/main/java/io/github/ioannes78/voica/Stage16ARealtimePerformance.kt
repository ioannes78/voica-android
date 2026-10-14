package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.StreamingAudioBufferSnapshot
import io.github.ioannes78.voica.transcript.AsrCapabilities
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.PunctuationEngine
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunnerSnapshot
import io.github.ioannes78.voica.transcript.StreamingAsrEngine
import io.github.ioannes78.voica.transcript.StreamingAsrSession
import io.github.ioannes78.voica.transcript.StreamingVadEngine
import io.github.ioannes78.voica.transcript.StreamingVadSession
import io.github.ioannes78.voica.transcript.StreamingVadUpdate
import io.github.ioannes78.voica.model.ModelDescriptor

/**
 * Low-overhead native timing counters used only for Stage 16A P7 profiling/benchmark evidence.
 *
 * Timings are monotonic elapsed durations around native-facing calls. They never invent media time
 * and therefore stay separate from canonical sample indices/timestamps.
 */
internal data class Stage16ARealtimeNativeTimingSnapshot(
    val asrOpenCalls: Long,
    val asrOpenNanos: Long,
    val asrAcceptCalls: Long,
    val asrAcceptedSamples: Long,
    val asrAcceptNanos: Long,
    val asrDecodeCalls: Long,
    val asrDecodeNanos: Long,
    val maxAsrDecodeNanos: Long,
    val asrFinishCalls: Long,
    val asrFinishNanos: Long,
    val maxAsrFinishNanos: Long,
    val asrResetCalls: Long,
    val asrResetNanos: Long,
    val vadOpenCalls: Long,
    val vadOpenNanos: Long,
    val vadAcceptCalls: Long,
    val vadAcceptedSamples: Long,
    val vadAcceptNanos: Long,
    val vadFinishCalls: Long,
    val vadFinishNanos: Long,
    val maxVadFinishNanos: Long,
    val vadResetCalls: Long,
    val vadResetNanos: Long,
    val punctuationCalls: Long,
    val punctuationNanos: Long,
    val maxPunctuationNanos: Long,
) {
    val totalNativeNanos: Long
        get() =
            asrOpenNanos +
                asrAcceptNanos +
                asrDecodeNanos +
                asrFinishNanos +
                asrResetNanos +
                vadOpenNanos +
                vadAcceptNanos +
                vadFinishNanos +
                vadResetNanos +
                punctuationNanos

    fun nativeRtfForCanonicalSamples(
        canonicalSamples: Long,
        sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
    ): Double? {
        if (canonicalSamples <= 0L || sampleRateHz <= 0) return null
        val mediaNanos = canonicalSamples.toDouble() * 1_000_000_000.0 / sampleRateHz.toDouble()
        return totalNativeNanos.toDouble() / mediaNanos
    }
}

internal data class Stage16ARealtimePipelinePerformanceSnapshot(
    val modelId: String,
    val modelLabel: String,
    val effectiveThreads: Int,
    val targetChunkSamples: Int,
    val targetChunkDurationMs: Int,
    val native: Stage16ARealtimeNativeTimingSnapshot,
    val buffer: StreamingAudioBufferSnapshot,
    val runner: RealtimeTranscriptionSessionRunnerSnapshot,
) {
    val canonicalAudioSeconds: Double
        get() = runner.processedPcmSamples.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()

    val nativeRtf: Double?
        get() = native.nativeRtfForCanonicalSamples(runner.processedPcmSamples)

    val currentBufferDepthMs: Double
        get() = buffer.queuedPcmSamples.toDouble() * 1_000.0 / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()

    val peakBufferDepthMs: Double
        get() = buffer.peakQueuedPcmSamples.toDouble() * 1_000.0 / CanonicalPcmProfile.SAMPLE_RATE_HZ.toDouble()
}

/**
 * Wraps Stage 16A native engines without changing recognizer/VAD/punctuation behavior.
 *
 * All updates are tiny synchronized counter mutations. No file/Room/UI work occurs on inference
 * calls, so the probe cannot become another canonical consumer or backpressure BLE ingest.
 */
internal class Stage16ARealtimePerformanceProbe(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()

    private var asrOpenCalls = 0L
    private var asrOpenNanos = 0L
    private var asrAcceptCalls = 0L
    private var asrAcceptedSamples = 0L
    private var asrAcceptNanos = 0L
    private var asrDecodeCalls = 0L
    private var asrDecodeNanos = 0L
    private var maxAsrDecodeNanos = 0L
    private var asrFinishCalls = 0L
    private var asrFinishNanos = 0L
    private var maxAsrFinishNanos = 0L
    private var asrResetCalls = 0L
    private var asrResetNanos = 0L
    private var vadOpenCalls = 0L
    private var vadOpenNanos = 0L
    private var vadAcceptCalls = 0L
    private var vadAcceptedSamples = 0L
    private var vadAcceptNanos = 0L
    private var vadFinishCalls = 0L
    private var vadFinishNanos = 0L
    private var maxVadFinishNanos = 0L
    private var vadResetCalls = 0L
    private var vadResetNanos = 0L
    private var punctuationCalls = 0L
    private var punctuationNanos = 0L
    private var maxPunctuationNanos = 0L

    fun wrapAsr(delegate: StreamingAsrEngine): StreamingAsrEngine = TimedAsrEngine(delegate, this)

    fun wrapVad(delegate: StreamingVadEngine): StreamingVadEngine = TimedVadEngine(delegate, this)

    fun wrapPunctuation(delegate: PunctuationEngine): PunctuationEngine =
        TimedPunctuationEngine(delegate, this)

    fun snapshot(): Stage16ARealtimeNativeTimingSnapshot = synchronized(lock) {
        Stage16ARealtimeNativeTimingSnapshot(
            asrOpenCalls = asrOpenCalls,
            asrOpenNanos = asrOpenNanos,
            asrAcceptCalls = asrAcceptCalls,
            asrAcceptedSamples = asrAcceptedSamples,
            asrAcceptNanos = asrAcceptNanos,
            asrDecodeCalls = asrDecodeCalls,
            asrDecodeNanos = asrDecodeNanos,
            maxAsrDecodeNanos = maxAsrDecodeNanos,
            asrFinishCalls = asrFinishCalls,
            asrFinishNanos = asrFinishNanos,
            maxAsrFinishNanos = maxAsrFinishNanos,
            asrResetCalls = asrResetCalls,
            asrResetNanos = asrResetNanos,
            vadOpenCalls = vadOpenCalls,
            vadOpenNanos = vadOpenNanos,
            vadAcceptCalls = vadAcceptCalls,
            vadAcceptedSamples = vadAcceptedSamples,
            vadAcceptNanos = vadAcceptNanos,
            vadFinishCalls = vadFinishCalls,
            vadFinishNanos = vadFinishNanos,
            maxVadFinishNanos = maxVadFinishNanos,
            vadResetCalls = vadResetCalls,
            vadResetNanos = vadResetNanos,
            punctuationCalls = punctuationCalls,
            punctuationNanos = punctuationNanos,
            maxPunctuationNanos = maxPunctuationNanos,
        )
    }

    internal fun mark(): Long = nanoTime()

    internal fun elapsedSince(startNanos: Long): Long =
        (nanoTime() - startNanos).coerceAtLeast(0L)

    internal fun recordAsrOpen(elapsedNanos: Long) = synchronized(lock) {
        asrOpenCalls += 1L
        asrOpenNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordAsrAccept(acceptedSamples: Int, elapsedNanos: Long) = synchronized(lock) {
        asrAcceptCalls += 1L
        asrAcceptedSamples += acceptedSamples.coerceAtLeast(0).toLong()
        asrAcceptNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordAsrDecode(elapsedNanos: Long) = synchronized(lock) {
        val safe = elapsedNanos.coerceAtLeast(0L)
        asrDecodeCalls += 1L
        asrDecodeNanos += safe
        maxAsrDecodeNanos = maxOf(maxAsrDecodeNanos, safe)
    }

    internal fun recordAsrFinish(elapsedNanos: Long) = synchronized(lock) {
        val safe = elapsedNanos.coerceAtLeast(0L)
        asrFinishCalls += 1L
        asrFinishNanos += safe
        maxAsrFinishNanos = maxOf(maxAsrFinishNanos, safe)
    }

    internal fun recordAsrReset(elapsedNanos: Long) = synchronized(lock) {
        asrResetCalls += 1L
        asrResetNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordVadOpen(elapsedNanos: Long) = synchronized(lock) {
        vadOpenCalls += 1L
        vadOpenNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordVadAccept(acceptedSamples: Int, elapsedNanos: Long) = synchronized(lock) {
        vadAcceptCalls += 1L
        vadAcceptedSamples += acceptedSamples.coerceAtLeast(0).toLong()
        vadAcceptNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordVadFinish(elapsedNanos: Long) = synchronized(lock) {
        val safe = elapsedNanos.coerceAtLeast(0L)
        vadFinishCalls += 1L
        vadFinishNanos += safe
        maxVadFinishNanos = maxOf(maxVadFinishNanos, safe)
    }

    internal fun recordVadReset(elapsedNanos: Long) = synchronized(lock) {
        vadResetCalls += 1L
        vadResetNanos += elapsedNanos.coerceAtLeast(0L)
    }

    internal fun recordPunctuation(elapsedNanos: Long) = synchronized(lock) {
        val safe = elapsedNanos.coerceAtLeast(0L)
        punctuationCalls += 1L
        punctuationNanos += safe
        maxPunctuationNanos = maxOf(maxPunctuationNanos, safe)
    }
}

private class TimedAsrEngine(
    private val delegate: StreamingAsrEngine,
    private val probe: Stage16ARealtimePerformanceProbe,
) : StreamingAsrEngine {
    override val model: ModelDescriptor
        get() = delegate.model
    override val capabilities: AsrCapabilities
        get() = delegate.capabilities

    override suspend fun openSession(): StreamingAsrSession {
        val start = probe.mark()
        return try {
            TimedAsrSession(delegate.openSession(), probe)
        } finally {
            probe.recordAsrOpen(probe.elapsedSince(start))
        }
    }

    override fun close() = delegate.close()
}

private class TimedAsrSession(
    private val delegate: StreamingAsrSession,
    private val probe: Stage16ARealtimePerformanceProbe,
) : StreamingAsrSession {
    override suspend fun acceptSamples(samples: ShortArray, offset: Int, count: Int) {
        val start = probe.mark()
        var accepted = false
        try {
            delegate.acceptSamples(samples, offset, count)
            accepted = true
        } finally {
            probe.recordAsrAccept(if (accepted) count else 0, probe.elapsedSince(start))
        }
    }

    override suspend fun decode(): AsrHypothesis {
        val start = probe.mark()
        return try {
            delegate.decode()
        } finally {
            probe.recordAsrDecode(probe.elapsedSince(start))
        }
    }

    override suspend fun finishInput(): AsrHypothesis {
        val start = probe.mark()
        return try {
            delegate.finishInput()
        } finally {
            probe.recordAsrFinish(probe.elapsedSince(start))
        }
    }

    override suspend fun reset() {
        val start = probe.mark()
        try {
            delegate.reset()
        } finally {
            probe.recordAsrReset(probe.elapsedSince(start))
        }
    }

    override fun close() = delegate.close()
}

private class TimedVadEngine(
    private val delegate: StreamingVadEngine,
    private val probe: Stage16ARealtimePerformanceProbe,
) : StreamingVadEngine {
    override val model: ModelDescriptor
        get() = delegate.model

    override suspend fun openSession(): StreamingVadSession {
        val start = probe.mark()
        return try {
            TimedVadSession(delegate.openSession(), probe)
        } finally {
            probe.recordVadOpen(probe.elapsedSince(start))
        }
    }

    override fun close() = delegate.close()
}

private class TimedVadSession(
    private val delegate: StreamingVadSession,
    private val probe: Stage16ARealtimePerformanceProbe,
) : StreamingVadSession {
    override suspend fun acceptSamples(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): StreamingVadUpdate {
        val start = probe.mark()
        var accepted = false
        return try {
            delegate.acceptSamples(samples, offset, count).also { accepted = true }
        } finally {
            probe.recordVadAccept(if (accepted) count else 0, probe.elapsedSince(start))
        }
    }

    override suspend fun finishInput(): StreamingVadUpdate {
        val start = probe.mark()
        return try {
            delegate.finishInput()
        } finally {
            probe.recordVadFinish(probe.elapsedSince(start))
        }
    }

    override suspend fun reset() {
        val start = probe.mark()
        try {
            delegate.reset()
        } finally {
            probe.recordVadReset(probe.elapsedSince(start))
        }
    }

    override fun close() = delegate.close()
}

private class TimedPunctuationEngine(
    private val delegate: PunctuationEngine,
    private val probe: Stage16ARealtimePerformanceProbe,
) : PunctuationEngine {
    override val model: ModelDescriptor
        get() = delegate.model

    override suspend fun addPunctuation(text: String): String {
        val start = probe.mark()
        return try {
            delegate.addPunctuation(text)
        } finally {
            probe.recordPunctuation(probe.elapsedSince(start))
        }
    }

    override fun close() = delegate.close()
}
