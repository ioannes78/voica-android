package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.StreamingAudioDiscontinuityReason
import io.github.ioannes78.voica.audio.StreamingAudioEvent
import io.github.ioannes78.voica.audio.StreamingChunkAssembler
import io.github.ioannes78.voica.audio.StreamingPcmChunk
import java.io.Closeable
import java.util.ArrayDeque

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
    val vadPrerollSamples: Int,
) {
    init {
        require(targetChunkSamples > 0)
        require(vadPrerollSamples >= targetChunkSamples)
    }
}

/**
 * P3 local true-streaming processor.
 *
 * VAD controls recognizer activation/finalization without rewriting canonical media time. A bounded
 * pre-roll keeps the beginning of speech that precedes Silero's `isSpeechDetected()` transition.
 * PARTIAL/FINAL are native engine results; STABLE and punctuation are intentionally left to P4/P5.
 */
class RealtimeStreamingAsrProcessor(
    private val asrEngine: StreamingAsrEngine,
    private val vadEngine: StreamingVadEngine,
    private val config: RealtimeStreamingAsrConfig,
) : Closeable {
    private val assembler = StreamingChunkAssembler(config.targetChunkSamples)
    private val preRoll = ArrayDeque<StreamingPcmChunk>()
    private var preRollSamples = 0

    private var activeSessionId: Long? = null
    private var activeEpochId: Long? = null
    private var epochReason: RealtimeCanonicalEpochStartReason? = null
    private var continuityIndex = 0L
    private var continuityStartSampleIndex: Long? = null
    private var vadSession: StreamingVadSession? = null
    private var asrSession: StreamingAsrSession? = null
    private var asrBaseSampleIndex: Long? = null
    private var asrAcceptedEndSampleIndexExclusive: Long? = null
    private var lastPartial: AsrHypothesis? = null
    private var closed = false

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

    /**
     * Called by the Stage 16 product coordinator immediately after a PAUSE command is confirmed.
     *
     * Stage 15 intentionally emits CONTROL_BOUNDARY only when resumed PCM begins, so waiting for the
     * next EpochStarted event would leave the current recognizer continuity open for the entire pause.
     * This local-only boundary finalizes already accepted audio now, without sending any additional
     * recorder command or fabricating canonical samples. The following RESUME EpochStarted event will
     * create a fresh recognizer continuity.
     *
     * Repeated calls are idempotent while paused: once the active epoch has been detached, no second
     * FINAL can be produced.
     */
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
        clearPreRoll()
    }

    private suspend fun startContinuity(
        sessionId: Long,
        epochId: Long,
        reason: RealtimeCanonicalEpochStartReason?,
        startSampleIndex: Long?,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        closeContinuitySessions()
        clearPreRoll()
        activeEpochId = epochId
        epochReason = reason
        continuityIndex = Math.addExact(continuityIndex, 1L)
        continuityStartSampleIndex = startSampleIndex
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

    private suspend fun processChunk(
        chunk: StreamingPcmChunk,
        target: MutableList<RealtimeAsrPipelineEvent>,
    ) {
        if (continuityStartSampleIndex == null) {
            continuityStartSampleIndex = chunk.startSampleIndex
        }
        val vad = checkNotNull(vadSession)
        val vadUpdate = vad.acceptSamples(chunk.samples)
        appendPreRollIfIdle(chunk)

        if (asrSession != null) {
            feedActiveAsr(chunk)
            decodePartial(target)
        } else if (vadUpdate.speechDetected || vadUpdate.completedSegments.isNotEmpty()) {
            startAsrFromPreRoll()
            decodePartial(target)
        }

        emitVadSegments(vadUpdate, target)
        if (vadUpdate.completedSegments.isNotEmpty() && asrSession != null) {
            finalizeAsr(RealtimeAsrFinalizationReason.VAD_SPEECH_END, target)
            clearPreRoll()
        }
    }

    private fun appendPreRollIfIdle(chunk: StreamingPcmChunk) {
        if (asrSession != null) return
        preRoll.addLast(chunk)
        preRollSamples += chunk.sampleCount
        while (preRollSamples > config.vadPrerollSamples && preRoll.size > 1) {
            preRollSamples -= preRoll.removeFirst().sampleCount
        }
    }

    private suspend fun startAsrFromPreRoll() {
        check(asrSession == null)
        check(preRoll.isNotEmpty())
        val session = asrEngine.openSession()
        asrSession = session
        asrBaseSampleIndex = preRoll.first().startSampleIndex
        asrAcceptedEndSampleIndexExclusive = preRoll.first().startSampleIndex
        for (chunk in preRoll) {
            session.acceptSamples(chunk.samples)
            asrAcceptedEndSampleIndexExclusive = chunk.endSampleIndexExclusive
        }
        clearPreRoll()
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
    }

    private fun hypothesisEvent(
        hypothesis: AsrHypothesis,
        finalizationReason: RealtimeAsrFinalizationReason?,
    ): RealtimeAsrPipelineEvent.Hypothesis {
        return RealtimeAsrPipelineEvent.Hypothesis(
            sessionId = checkNotNull(activeSessionId),
            epochId = checkNotNull(activeEpochId),
            continuityIndex = continuityIndex,
            recognizerBaseSampleIndex = checkNotNull(asrBaseSampleIndex),
            acceptedEndSampleIndexExclusive = checkNotNull(asrAcceptedEndSampleIndexExclusive),
            hypothesis = hypothesis,
            finalizationReason = finalizationReason,
        )
    }

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
        val update = vad.finishInput()
        if (update.completedSegments.isNotEmpty() && asrSession == null && preRoll.isNotEmpty()) {
            startAsrFromPreRoll()
        }
        emitVadSegments(update, target)
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
        clearPreRoll()
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

    private fun clearPreRoll() {
        preRoll.clear()
        preRollSamples = 0
    }
}
