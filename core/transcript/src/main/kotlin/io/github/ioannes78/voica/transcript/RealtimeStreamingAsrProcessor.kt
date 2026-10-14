package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.audio.StreamingChunkAssembler
import io.github.ioannes78.voica.audio.StreamingPcmChunk
import java.io.Closeable

enum class RealtimeAsrFinalizationReason {
    VAD_SPEECH_END,
    EPOCH_BOUNDARY,
    DISCONTINUITY,
    SESSION_END,
    SUPERSEDED_SESSION,
}

sealed interface RealtimeAsrPipelineEvent {
    val sessionId: Long

    data class SessionStarted(
        override val sessionId: Long,
    ) : RealtimeAsrPipelineEvent

    data class ContinuityStarted(
        override val sessionId: Long,
        val epochId: Long,
        val continuityIndex: Long,
        val startSampleIndex: Long?,
        val epochReason: RealtimeCanonicalEpochStartReason?,
    ) : RealtimeAsrPipelineEvent

    data class VadSegment(
        override val sessionId: Long,
        val epochId: Long,
        val continuityIndex: Long,
        val startSampleIndex: Long,
        val endSampleIndexExclusive: Long,
    ) : RealtimeAsrPipelineEvent

    data class Hypothesis(
        override val sessionId: Long,
        val epochId: Long,
        val continuityIndex: Long,
        val recognizerBaseSampleIndex: Long,
        val acceptedEndSampleIndexExclusive: Long,
        val hypothesis: AsrHypothesis,
        val finalizationReason: RealtimeAsrFinalizationReason? = null,
    ) : RealtimeAsrPipelineEvent {
        init {
            require(acceptedEndSampleIndexExclusive >= recognizerBaseSampleIndex)
            require(hypothesis.isFinal == (finalizationReason != null))
        }
    }

    data class Discontinuity(
        override val sessionId: Long,
        val epochId: Long,
        val resumeAtSampleIndex: Long,
        val reason: StreamingAudioDiscontinuityReason,
        val droppedFrames: Long,
        val droppedSamples: Long,
    ) : RealtimeAsrPipelineEvent

    data class SessionEnded(
        override val sessionId: Long,
        val reason: RealtimeCanonicalSessionEndReason,
        val drainTimedOut: Boolean,
    ) : RealtimeAsrPipelineEvent
}

data class RealtimeStreamingAsrConfig(
    val targetChunkSamples: Int,
    /**
     * Retained for Stage 16A config compatibility. ASR no longer waits for VAD and therefore does
     * not depend on a preroll window to preserve sentence beginnings.
     */
    val vadPrerollSamples: Int,
) {
    init {
        require(targetChunkSamples > 0)
        require(vadPrerollSamples >= targetChunkSamples)
    }
}

data class RealtimeStreamingAsrStartupSnapshot(
    val firstPcmSampleIndex: Long?,
    val firstAsrAcceptedSampleIndex: Long?,
    val firstVadSpeechSampleIndex: Long?,
    val firstNonEmptyPartialSampleIndex: Long?,
    val firstPcmAtNs: Long?,
    val firstAsrAcceptedAtNs: Long?,
    val firstVadSpeechAtNs: Long?,
    val firstNonEmptyPartialAtNs: Long?,
) {
    val skippedSamplesBeforeFirstAsrAccept: Long?
        get() {
            val pcm = firstPcmSampleIndex ?: return null
            val asr = firstAsrAcceptedSampleIndex ?: return null
            return (asr - pcm).coerceAtLeast(0L)
        }

    val firstAsrAcceptLatencyMs: Double?
        get() = latencyMs(firstPcmAtNs, firstAsrAcceptedAtNs)

    val firstVadSpeechLatencyMs: Double?
        get() = latencyMs(firstPcmAtNs, firstVadSpeechAtNs)

    val firstNonEmptyPartialLatencyMs: Double?
        get() = latencyMs(firstPcmAtNs, firstNonEmptyPartialAtNs)

    private fun latencyMs(startNs: Long?, endNs: Long?): Double? {
        if (startNs == null || endNs == null || endNs < startNs) return null
        return (endNs - startNs).toDouble() / 1_000_000.0
    }
}

/**
 * Stage 16A local true-streaming processor.
 *
 * Every canonical PCM chunk is accepted by the recognizer from the first chunk of each continuity.
 * Silero VAD runs in parallel and controls utterance/finalization boundaries only; it never gates
 * whether the recognizer hears the beginning of the recording. This guarantees that VAD activation
 * latency can delay boundary decisions but cannot permanently drop sentence-leading PCM.
 */
class RealtimeStreamingAsrProcessor(
    private val asrEngine: StreamingAsrEngine,
    private val vadEngine: StreamingVadEngine,
    private val config: RealtimeStreamingAsrConfig,
    private val monotonicNs: () -> Long = System::nanoTime,
) : Closeable {
    private val assembler = StreamingChunkAssembler(config.targetChunkSamples)

    private var activeSessionId: Long? = null
    private var activeEpochId: Long? = null
    private var epochReason: RealtimeCanonicalEpochStartReason? = null
    private var continuityIndex = 0L
    private var continuityStartSampleIndex: Long? = null
    private var recognizerFinalizedInContinuity = false
    private var vadSession: StreamingVadSession? = null
    private var asrSession: StreamingAsrSession? = null
    private var asrBaseSampleIndex: Long? = null
    private var asrAcceptedEndSampleIndexExclusive: Long? = null
    private var lastPartial: AsrHypothesis? = null
    private var closed = false

    @Volatile private var firstPcmSampleIndex: Long? = null
    @Volatile private var firstAsrAcceptedSampleIndex: Long? = null
    @Volatile private var firstVadSpeechSampleIndex: Long? = null
    @Volatile private var firstNonEmptyPartialSampleIndex: Long? = null
    @Volatile private var firstPcmAtNs: Long? = null
    @Volatile private var firstAsrAcceptedAtNs: Long? = null
    @Volatile private var firstVadSpeechAtNs: Long? = null
    @Volatile private var firstNonEmptyPartialAtNs: Long? = null

    fun startupSnapshot(): RealtimeStreamingAsrStartupSnapshot =
        RealtimeStreamingAsrStartupSnapshot(
            firstPcmSampleIndex = firstPcmSampleIndex,
            firstAsrAcceptedSampleIndex = firstAsrAcceptedSampleIndex,
            firstVadSpeechSampleIndex = firstVadSpeechSampleIndex,
            firstNonEmptyPartialSampleIndex = firstNonEmptyPartialSampleIndex,
            firstPcmAtNs = firstPcmAtNs,
            firstAsrAcceptedAtNs = firstAsrAcceptedAtNs,
            firstVadSpeechAtNs = firstVadSpeechAtNs,
            firstNonEmptyPartialAtNs = firstNonEmptyPartialAtNs,
        )

    suspend fun accept(event: StreamingAudioEvent): List<RealtimeAsrPipelineEvent> {
        check(!closed) { "RealtimeStreamingAsrProcessor is closed" }
        return buildList {
            when (event) {
                is StreamingAudioEvent.SessionStarted -> {
                    if (activeSessionId != null && activeSessionId != event.sessionId) {
                        terminateCurrentContinuity(
                            RealtimeAsrFinalizationReason.SUPERSEDED_SESSION,
                            this,
                        )
                    }
                    activeSessionId = event.sessionId
                    add(RealtimeAsrPipelineEvent.SessionStarted(event.sessionId))
                }

                is StreamingAudioEvent.EpochStarted -> {
                    require(activeSessionId == event.sessionId) {
                        "epoch arrived without matching realtime session"
                    }
                    if (activeEpochId != null) {
                        terminateCurrentContinuity(
                            RealtimeAsrFinalizationReason.EPOCH_BOUNDARY,
                            this,
                        )
                    }
                    startContinuity(
                        sessionId = event.sessionId,
                        epochId = event.epochId,
                        reason = event.reason,
                        startSampleIndex = null,
                        target = this,
                    )
                }

                is StreamingAudioEvent.PcmFrame -> {
                    require(activeSessionId == event.sessionId)
                    require(activeEpochId == event.frame.epochId) {
                        "PCM arrived without matching canonical epoch"
                    }
                    noteFirstPcm(event.frame.startSampleIndex)
                    assembler.accept(event.frame).forEach { chunk ->
                        processChunk(chunk, this)
                    }
                }

                is StreamingAudioEvent.Discontinuity -> {
                    require(activeSessionId == event.sessionId)
                    require(activeEpochId == event.epochId)
                    flushAssembler(this)
                    finishVad(this)
                    finalizeAsr(RealtimeAsrFinalizationReason.DISCONTINUITY, this)
                    closeContinuitySessions()
                    assembler.reset()
                    startContinuity(
                        sessionId = event.sessionId,
                        epochId = event.epochId,
                        reason = null,
                        startSampleIndex = event.resumeAtSampleIndex,
                        target = this,
                    )
                    add(
                        RealtimeAsrPipelineEvent.Discontinuity(
                            sessionId = event.sessionId,
                            epochId = event.epochId,
                            resumeAtSampleIndex = event.resumeAtSampleIndex,
                            reason = event.reason,
                            droppedFrames = event.droppedFrames,
                            droppedSamples = event.droppedSamples,
                        ),
                    )
                }

                is StreamingAudioEvent.SessionEnded -> {
                    require(activeSessionId == event.sessionId)
                    terminateCurrentContinuity(
                        RealtimeAsrFinalizationReason.SESSION_END,
                        this,
                    )
                    add(
                        RealtimeAsrPipelineEvent.SessionEnded(
                            sessionId = event.sessionId,
                            reason = event.reason,
                            drainTimedOut = event.drainTimedOut,
                        ),
                    )
                    activeSessionId = null
                    activeEpochId = null
                    epochReason = null
                }
            }
        }
    }

    suspend fun onPauseCommitted(): List<RealtimeAsrPipelineEvent> {
        check(!closed) { "RealtimeStreamingAsrProcessor is closed" }
        if (activeSessionId == null || activeEpochId == null) return emptyList()
        return buildList {
            terminateCurrentContinuity(
                RealtimeAsrFinalizationReason.EPOCH_BOUNDARY,
                this,
            )
            activeEpochId = null
            epochReason = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        asrSession?.close()
        vadSession?.close()
        asrSession = null
        vadSession = null
        assembler.reset()
    }

    private suspend fun startContinuity(
        sessionId: Long,
        epochId: Long,
        reason: RealtimeCanonicalEpochStartReason?,
        startSampleIndex: Long?,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        closeContinuitySessions()
        activeEpochId = epochId
        epochReason = reason
        continuityIndex = Math.addExact(continuityIndex, 1L)
        continuityStartSampleIndex = startSampleIndex
        recognizerFinalizedInContinuity = false
        assembler.startEpoch(sessionId, epochId)
        vadSession = vadEngine.openSession()
        target +=
            RealtimeAsrPipelineEvent.ContinuityStarted(
                sessionId = sessionId,
                epochId = epochId,
                continuityIndex = continuityIndex,
                startSampleIndex = startSampleIndex,
                epochReason = reason,
            )
    }

    private fun startNextRecognizerContinuity(
        startSampleIndex: Long,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        check(recognizerFinalizedInContinuity) {
            "recognizer continuity may only restart after FINAL"
        }
        val sessionId = checkNotNull(activeSessionId)
        val epochId = checkNotNull(activeEpochId)
        continuityIndex = Math.addExact(continuityIndex, 1L)
        recognizerFinalizedInContinuity = false
        target +=
            RealtimeAsrPipelineEvent.ContinuityStarted(
                sessionId = sessionId,
                epochId = epochId,
                continuityIndex = continuityIndex,
                startSampleIndex = startSampleIndex,
                epochReason = null,
            )
    }

    private suspend fun processChunk(
        chunk: StreamingPcmChunk,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        if (continuityStartSampleIndex == null) {
            continuityStartSampleIndex = chunk.startSampleIndex
        }

        val vad = checkNotNull(vadSession)
        val vadUpdate = vad.acceptSamples(chunk.samples)
        if (vadUpdate.speechDetected && firstVadSpeechSampleIndex == null) {
            firstVadSpeechSampleIndex = chunk.endSampleIndexExclusive
            firstVadSpeechAtNs = monotonicNs()
        }

        ensureAsrSessionForChunk(chunk, target)
        feedActiveAsr(chunk)
        noteFirstAsrAccepted(chunk.startSampleIndex)
        decodePartial(target)

        emitVadSegments(vadUpdate, target)
        if (vadUpdate.completedSegments.isNotEmpty() && asrSession != null) {
            finalizeAsr(RealtimeAsrFinalizationReason.VAD_SPEECH_END, target)
        }
    }

    private suspend fun ensureAsrSessionForChunk(
        chunk: StreamingPcmChunk,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        if (asrSession != null) return
        if (recognizerFinalizedInContinuity) {
            startNextRecognizerContinuity(
                startSampleIndex = chunk.startSampleIndex,
                target = target,
            )
        }
        asrSession = asrEngine.openSession()
        asrBaseSampleIndex = chunk.startSampleIndex
        asrAcceptedEndSampleIndexExclusive = chunk.startSampleIndex
        lastPartial = null
    }

    private suspend fun feedActiveAsr(chunk: StreamingPcmChunk) {
        val session = checkNotNull(asrSession)
        val expected = checkNotNull(asrAcceptedEndSampleIndexExclusive)
        require(chunk.startSampleIndex == expected) {
            "ASR canonical input gap/overlap: expected=$expected actual=${chunk.startSampleIndex}"
        }
        session.acceptSamples(chunk.samples)
        asrAcceptedEndSampleIndexExclusive = chunk.endSampleIndexExclusive
    }

    private suspend fun decodePartial(target: MutableList<RealtimeAsrPipelineEvent>) {
        val session = asrSession ?: return
        val hypothesis = session.decode()
        require(!hypothesis.isFinal)
        if (hypothesis.text.isNotBlank() && firstNonEmptyPartialSampleIndex == null) {
            firstNonEmptyPartialSampleIndex = asrAcceptedEndSampleIndexExclusive
            firstNonEmptyPartialAtNs = monotonicNs()
        }
        if (hypothesis == lastPartial) return
        lastPartial = hypothesis
        target += hypothesisEvent(hypothesis, finalizationReason = null)
    }

    private suspend fun finalizeAsr(
        reason: RealtimeAsrFinalizationReason,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        val session = asrSession ?: return
        val hypothesis = session.finishInput()
        require(hypothesis.isFinal)
        target += hypothesisEvent(hypothesis, finalizationReason = reason)
        session.close()
        asrSession = null
        asrBaseSampleIndex = null
        asrAcceptedEndSampleIndexExclusive = null
        lastPartial = null
        recognizerFinalizedInContinuity = true
    }

    private fun hypothesisEvent(
        hypothesis: AsrHypothesis,
        finalizationReason: RealtimeAsrFinalizationReason?,
    ): RealtimeAsrPipelineEvent.Hypothesis =
        RealtimeAsrPipelineEvent.Hypothesis(
            sessionId = checkNotNull(activeSessionId),
            epochId = checkNotNull(activeEpochId),
            continuityIndex = continuityIndex,
            recognizerBaseSampleIndex = checkNotNull(asrBaseSampleIndex),
            acceptedEndSampleIndexExclusive = checkNotNull(asrAcceptedEndSampleIndexExclusive),
            hypothesis = hypothesis,
            finalizationReason = finalizationReason,
        )

    private fun emitVadSegments(
        update: StreamingVadUpdate,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        val base = continuityStartSampleIndex ?: return
        val sessionId = checkNotNull(activeSessionId)
        val epochId = checkNotNull(activeEpochId)
        update.completedSegments.forEach { segment ->
            target +=
                RealtimeAsrPipelineEvent.VadSegment(
                    sessionId = sessionId,
                    epochId = epochId,
                    continuityIndex = continuityIndex,
                    startSampleIndex = Math.addExact(base, segment.startSampleOffset),
                    endSampleIndexExclusive = Math.addExact(base, segment.endSampleOffsetExclusive),
                )
        }
    }

    private suspend fun finishVad(target: MutableList<RealtimeAsrPipelineEvent>) {
        val vad = vadSession ?: return
        emitVadSegments(vad.finishInput(), target)
    }

    private suspend fun flushAssembler(target: MutableList<RealtimeAsrPipelineEvent>) {
        assembler.flush()?.let { processChunk(it, target) }
    }

    private suspend fun terminateCurrentContinuity(
        reason: RealtimeAsrFinalizationReason,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        if (activeEpochId == null) return
        flushAssembler(target)
        finishVad(target)
        finalizeAsr(reason, target)
        closeContinuitySessions()
        assembler.reset()
        continuityStartSampleIndex = null
    }

    private fun closeContinuitySessions() {
        asrSession?.close()
        vadSession?.close()
        asrSession = null
        vadSession = null
        asrBaseSampleIndex = null
        asrAcceptedEndSampleIndexExclusive = null
        lastPartial = null
    }

    private fun noteFirstPcm(sampleIndex: Long) {
        if (firstPcmSampleIndex != null) return
        firstPcmSampleIndex = sampleIndex
        firstPcmAtNs = monotonicNs()
    }

    private fun noteFirstAsrAccepted(sampleIndex: Long) {
        if (firstAsrAcceptedSampleIndex != null) return
        firstAsrAcceptedSampleIndex = sampleIndex
        firstAsrAcceptedAtNs = monotonicNs()
    }
}
