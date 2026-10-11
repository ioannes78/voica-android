package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInspector
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.ble.RealtimeAudioIngress
import io.github.ioannes78.voica.ble.RealtimeEncodedBlock
import io.github.ioannes78.voica.ble.RealtimeOpusPacketizer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

data class RealtimeCanonicalAudioPipelineSnapshot(
    val running: Boolean = false,
    val sessionCount: Long = 0,
    val epochCount: Long = 0,
    val receivedBlocks: Long = 0,
    val packetizedPackets: Long = 0,
    val invalidOpusPackets: Long = 0,
    val opusShapeAnomalies: Long = 0,
    val decodeSuccess: Long = 0,
    val decodeFailures: Long = 0,
    val zeroSampleDecodeFailures: Long = 0,
    val unexpectedDecodedSampleCount: Long = 0,
    val decodedSampleHistogram: Map<Int, Long> = emptyMap(),
    val decoderResetCount: Long = 0,
    val decoderRecreateCount: Long = 0,
    val decoderCreateFailures: Long = 0,
    val discontinuityCount: Long = 0,
    val transportBoundaryCount: Long = 0,
    val controlBoundaryCount: Long = 0,
    val decoderRecoveryBoundaryCount: Long = 0,
    val canonicalFrames: Long = 0,
    val canonicalSamples: Long = 0,
    val frameObserverFailures: Long = 0,
    val activeSessionId: Long? = null,
    val activeEpochId: Long? = null,
    val nextSampleIndex: Long = 0,
    val maxBlockProcessingMs: Double = 0.0,
    val maxArrivalToEmitLagMs: Double = 0.0,
    val lastError: String? = null,
) {
    val canonicalDurationMs: Double
        get() = canonicalSamples * 1_000.0 / CanonicalPcmProfile.SAMPLE_RATE_HZ
}

/**
 * Stage 15.3/15.4 production worker from bounded encoded ingress to canonical PCM frames.
 *
 * The worker is single-consumer and must run off the GATT callback thread. Media time advances only
 * by the actual number of PCM16 samples returned by the decoder. Transport arrival timestamps are
 * used only for diagnostics.
 */
class RealtimeCanonicalAudioPipeline(
    private val ingress: RealtimeAudioIngress,
    private val packetInspector: OpusPacketInspector,
    private val decoderFactory: OpusDecoderFactory,
    private val onFrame: (RealtimeCanonicalPcmFrame) -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime,
) : Closeable {
    private val lifecycleLock = Any()
    private val statsLock = Any()
    private val requestedSessionVersion = AtomicLong(0)
    private val requestedControlBoundaryVersion = AtomicLong(0)

    @Volatile
    private var closed = false

    @Volatile
    private var running = false

    private var counters = Counters()

    /** Call before sending a successful-or-attempted realtime START command. */
    fun beginRealtimeSession(): Long = requestedSessionVersion.incrementAndGet()

    /** Call before RESUME so the next audio frame starts a fresh epoch. */
    fun requestControlBoundary(): Long = requestedControlBoundaryVersion.incrementAndGet()

    fun resetDiagnostics() = synchronized(statsLock) {
        counters = Counters(running = running)
    }

    fun snapshot(): RealtimeCanonicalAudioPipelineSnapshot = synchronized(statsLock) {
        counters.snapshot()
    }

    suspend fun run() {
        synchronized(lifecycleLock) {
            check(!running) { "RealtimeCanonicalAudioPipeline is already running" }
            check(!closed) { "RealtimeCanonicalAudioPipeline is closed" }
            running = true
        }
        synchronized(statsLock) { counters.running = true }

        var decoder: OpusDecoder? = null
        var appliedSessionVersion = -1L
        var appliedControlBoundaryVersion = requestedControlBoundaryVersion.get()
        var activeTransportGeneration: Long? = null
        var activeContinuityId: Long? = null
        var sessionId = 0L
        var epochId = 0L
        var nextSampleIndex = 0L
        var pendingEpochReason: RealtimeCanonicalEpochStartReason? = null

        fun recordError(message: String) {
            synchronized(statsLock) { counters.lastError = message }
        }

        fun createDecoder(): OpusDecoder? =
            runCatching {
                decoderFactory.create(
                    sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                )
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    synchronized(statsLock) {
                        counters.decoderCreateFailures += 1
                        counters.lastError = "decoder-create:${error.javaClass.simpleName}"
                    }
                    null
                },
            )

        fun resetDecoder(): Boolean {
            val current = decoder ?: createDecoder()?.also { decoder = it } ?: return false
            return runCatching { current.reset() }
                .fold(
                    onSuccess = {
                        synchronized(statsLock) { counters.decoderResetCount += 1 }
                        true
                    },
                    onFailure = { resetError ->
                        runCatching { current.close() }
                        decoder = createDecoder()
                        synchronized(statsLock) {
                            counters.decoderRecreateCount += 1
                            counters.lastError = "decoder-reset:${resetError.javaClass.simpleName}"
                        }
                        decoder != null
                    },
                )
        }

        fun startSession(block: RealtimeEncodedBlock, desiredSessionVersion: Long, transportBreak: Boolean) {
            if (sessionId > 0L) {
                resetDecoder()
            }
            sessionId += 1
            epochId = 0
            nextSampleIndex = 0
            pendingEpochReason = RealtimeCanonicalEpochStartReason.SESSION_START
            appliedSessionVersion = desiredSessionVersion
            appliedControlBoundaryVersion = requestedControlBoundaryVersion.get()
            activeTransportGeneration = block.sessionGeneration
            activeContinuityId = block.continuityId
            synchronized(statsLock) {
                counters.sessionCount += 1
                counters.activeSessionId = sessionId
                counters.activeEpochId = null
                counters.nextSampleIndex = 0
                if (transportBreak) {
                    counters.discontinuityCount += 1
                    counters.transportBoundaryCount += 1
                }
            }
        }

        fun startEpochBoundary(reason: RealtimeCanonicalEpochStartReason) {
            resetDecoder()
            nextSampleIndex = 0
            pendingEpochReason = reason
            synchronized(statsLock) {
                counters.discontinuityCount += 1
                counters.activeEpochId = null
                counters.nextSampleIndex = 0
                when (reason) {
                    RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY ->
                        counters.controlBoundaryCount += 1
                    RealtimeCanonicalEpochStartReason.DISCONTINUITY ->
                        counters.transportBoundaryCount += 1
                    RealtimeCanonicalEpochStartReason.DECODER_RECOVERY ->
                        counters.decoderRecoveryBoundaryCount += 1
                    RealtimeCanonicalEpochStartReason.SESSION_START -> Unit
                }
            }
        }

        try {
            decoder = createDecoder()
            if (decoder == null) return

            while (!closed && currentCoroutineContext().isActive) {
                val block = ingress.receive() ?: break
                val processingStartedAt = nowNanos()
                synchronized(statsLock) { counters.receivedBlocks += 1 }

                val desiredSessionVersion = requestedSessionVersion.get()
                val firstBlock = sessionId == 0L
                val explicitNewSession = !firstBlock && desiredSessionVersion != appliedSessionVersion
                val transportGenerationChanged =
                    activeTransportGeneration != null && activeTransportGeneration != block.sessionGeneration

                if (firstBlock || explicitNewSession || transportGenerationChanged) {
                    startSession(
                        block = block,
                        desiredSessionVersion = desiredSessionVersion,
                        transportBreak = transportGenerationChanged,
                    )
                } else {
                    val transportBoundary =
                        block.discontinuityBefore || activeContinuityId != block.continuityId
                    if (transportBoundary) {
                        startEpochBoundary(RealtimeCanonicalEpochStartReason.DISCONTINUITY)
                        activeContinuityId = block.continuityId
                    }

                    val desiredControlBoundary = requestedControlBoundaryVersion.get()
                    if (desiredControlBoundary != appliedControlBoundaryVersion) {
                        if (!transportBoundary) {
                            startEpochBoundary(RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY)
                        }
                        appliedControlBoundaryVersion = desiredControlBoundary
                    }
                }

                activeTransportGeneration = block.sessionGeneration
                activeContinuityId = block.continuityId

                val packets = RealtimeOpusPacketizer.split(block)
                synchronized(statsLock) { counters.packetizedPackets += packets.size }

                for (packet in packets) {
                    val info = runCatching { packetInspector.inspect(packet.encodedBytes) }
                        .getOrElse { error ->
                            recordError("inspect:${error.javaClass.simpleName}")
                            null
                        }
                    if (info == null || !info.valid) {
                        synchronized(statsLock) { counters.invalidOpusPackets += 1 }
                        startEpochBoundary(RealtimeCanonicalEpochStartReason.DECODER_RECOVERY)
                        continue
                    }
                    if (
                        info.channels != EXPECTED_OPUS_CHANNELS ||
                        info.totalSamples48k != EXPECTED_OPUS_SAMPLES_48K
                    ) {
                        synchronized(statsLock) { counters.opusShapeAnomalies += 1 }
                    }

                    val currentDecoder = decoder ?: createDecoder()?.also { decoder = it }
                    if (currentDecoder == null) {
                        startEpochBoundary(RealtimeCanonicalEpochStartReason.DECODER_RECOVERY)
                        continue
                    }
                    val pcm = runCatching { currentDecoder.decode(packet.encodedBytes) }
                        .getOrElse { error ->
                            synchronized(statsLock) {
                                counters.decodeFailures += 1
                                counters.lastError = "decode:${error.javaClass.simpleName}"
                            }
                            startEpochBoundary(RealtimeCanonicalEpochStartReason.DECODER_RECOVERY)
                            null
                        } ?: continue

                    if (pcm.isEmpty()) {
                        synchronized(statsLock) {
                            counters.zeroSampleDecodeFailures += 1
                            counters.lastError = "decode:empty-pcm"
                        }
                        startEpochBoundary(RealtimeCanonicalEpochStartReason.DECODER_RECOVERY)
                        continue
                    }

                    val reason = pendingEpochReason
                    if (reason != null) {
                        epochId += 1
                        nextSampleIndex = 0
                        pendingEpochReason = null
                        synchronized(statsLock) {
                            counters.epochCount += 1
                            counters.activeEpochId = epochId
                            counters.nextSampleIndex = 0
                        }
                    }

                    synchronized(statsLock) {
                        counters.decodeSuccess += 1
                        counters.decodedSampleHistogram[pcm.size] =
                            (counters.decodedSampleHistogram[pcm.size] ?: 0L) + 1L
                        if (pcm.size != EXPECTED_DECODED_SAMPLES_16K) {
                            counters.unexpectedDecodedSampleCount += 1
                        }
                    }

                    val frame = RealtimeCanonicalPcmFrame(
                        sessionId = sessionId,
                        epochId = epochId,
                        startSampleIndex = nextSampleIndex,
                        samples = pcm,
                        epochStartReason = reason,
                    )
                    nextSampleIndex = frame.endSampleIndexExclusive

                    synchronized(statsLock) {
                        counters.canonicalFrames += 1
                        counters.canonicalSamples += pcm.size
                        counters.activeSessionId = sessionId
                        counters.activeEpochId = epochId
                        counters.nextSampleIndex = nextSampleIndex
                        val lagNanos = (nowNanos() - block.receivedAtNanos).coerceAtLeast(0L)
                        counters.maxArrivalToEmitLagNanos =
                            maxOf(counters.maxArrivalToEmitLagNanos, lagNanos)
                    }

                    runCatching { onFrame(frame) }
                        .onFailure { error ->
                            synchronized(statsLock) {
                                counters.frameObserverFailures += 1
                                counters.lastError = "frame-observer:${error.javaClass.simpleName}"
                            }
                        }
                }

                val processingNanos = (nowNanos() - processingStartedAt).coerceAtLeast(0L)
                synchronized(statsLock) {
                    counters.maxBlockProcessingNanos =
                        maxOf(counters.maxBlockProcessingNanos, processingNanos)
                }
            }
        } finally {
            decoder?.let { runCatching { it.close() } }
            synchronized(lifecycleLock) { running = false }
            synchronized(statsLock) { counters.running = false }
        }
    }

    override fun close() {
        closed = true
    }

    private class Counters(
        var running: Boolean = false,
        var sessionCount: Long = 0,
        var epochCount: Long = 0,
        var receivedBlocks: Long = 0,
        var packetizedPackets: Long = 0,
        var invalidOpusPackets: Long = 0,
        var opusShapeAnomalies: Long = 0,
        var decodeSuccess: Long = 0,
        var decodeFailures: Long = 0,
        var zeroSampleDecodeFailures: Long = 0,
        var unexpectedDecodedSampleCount: Long = 0,
        val decodedSampleHistogram: MutableMap<Int, Long> = linkedMapOf(),
        var decoderResetCount: Long = 0,
        var decoderRecreateCount: Long = 0,
        var decoderCreateFailures: Long = 0,
        var discontinuityCount: Long = 0,
        var transportBoundaryCount: Long = 0,
        var controlBoundaryCount: Long = 0,
        var decoderRecoveryBoundaryCount: Long = 0,
        var canonicalFrames: Long = 0,
        var canonicalSamples: Long = 0,
        var frameObserverFailures: Long = 0,
        var activeSessionId: Long? = null,
        var activeEpochId: Long? = null,
        var nextSampleIndex: Long = 0,
        var maxBlockProcessingNanos: Long = 0,
        var maxArrivalToEmitLagNanos: Long = 0,
        var lastError: String? = null,
    ) {
        fun snapshot() = RealtimeCanonicalAudioPipelineSnapshot(
            running = running,
            sessionCount = sessionCount,
            epochCount = epochCount,
            receivedBlocks = receivedBlocks,
            packetizedPackets = packetizedPackets,
            invalidOpusPackets = invalidOpusPackets,
            opusShapeAnomalies = opusShapeAnomalies,
            decodeSuccess = decodeSuccess,
            decodeFailures = decodeFailures,
            zeroSampleDecodeFailures = zeroSampleDecodeFailures,
            unexpectedDecodedSampleCount = unexpectedDecodedSampleCount,
            decodedSampleHistogram = decodedSampleHistogram.toMap(),
            decoderResetCount = decoderResetCount,
            decoderRecreateCount = decoderRecreateCount,
            decoderCreateFailures = decoderCreateFailures,
            discontinuityCount = discontinuityCount,
            transportBoundaryCount = transportBoundaryCount,
            controlBoundaryCount = controlBoundaryCount,
            decoderRecoveryBoundaryCount = decoderRecoveryBoundaryCount,
            canonicalFrames = canonicalFrames,
            canonicalSamples = canonicalSamples,
            frameObserverFailures = frameObserverFailures,
            activeSessionId = activeSessionId,
            activeEpochId = activeEpochId,
            nextSampleIndex = nextSampleIndex,
            maxBlockProcessingMs = maxBlockProcessingNanos / NANOS_PER_MILLISECOND,
            maxArrivalToEmitLagMs = maxArrivalToEmitLagNanos / NANOS_PER_MILLISECOND,
            lastError = lastError,
        )
    }

    private companion object {
        const val EXPECTED_OPUS_CHANNELS = 1
        const val EXPECTED_OPUS_SAMPLES_48K = 960
        const val EXPECTED_DECODED_SAMPLES_16K = 320
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
