package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.StreamingAudioEvent

sealed interface RealtimeTranscriptionOutput {
    val sessionId: Long

    data class Lifecycle(
        val event: RealtimeAsrPipelineEvent,
    ) : RealtimeTranscriptionOutput {
        override val sessionId: Long
            get() = event.sessionId

        init {
            require(event !is RealtimeAsrPipelineEvent.Hypothesis) {
                "hypotheses must be emitted as RealtimeTranscriptionOutput.Hypothesis"
            }
        }
    }

    data class Hypothesis(
        val source: RealtimeAsrPipelineEvent.Hypothesis,
        val rawText: String,
        val displayHypothesis: AsrHypothesis,
        val canonicalTokens: List<TranscriptToken>,
        val punctuationAttempted: Boolean,
        val externalPunctuationApplied: Boolean,
        val externalPunctuationFailed: Boolean,
    ) : RealtimeTranscriptionOutput {
        override val sessionId: Long
            get() = source.sessionId

        val displayText: String
            get() = displayHypothesis.text

        val stability: AsrHypothesisStability
            get() = displayHypothesis.stability
    }
}

/**
 * Stage 16A product-neutral text pipeline.
 *
 * It connects the true-streaming processor to the stabilizer and punctuation policy while retaining
 * canonical timing. PARTIAL/STABLE/FINAL semantics are therefore identical for recorder-card UI,
 * durable persistence, and future microphone/cloud consumers. Database and Compose concerns remain
 * outside this module.
 */
class RealtimeTranscriptionPipeline(
    private val processor: RealtimeStreamingAsrProcessor,
    private val stabilizer: RealtimeHypothesisStabilizer = RealtimeHypothesisStabilizer(),
    private val punctuationProcessor: RealtimePunctuationProcessor,
) {
    suspend fun accept(event: StreamingAudioEvent): List<RealtimeTranscriptionOutput> =
        process(processor.accept(event))

    /** Finalizes the current recognizer continuity as soon as a recorder PAUSE is confirmed. */
    suspend fun onPauseCommitted(): List<RealtimeTranscriptionOutput> =
        process(processor.onPauseCommitted())

    private suspend fun process(
        events: List<RealtimeAsrPipelineEvent>,
    ): List<RealtimeTranscriptionOutput> = buildList {
        for (event in events) {
            val stabilizedEvents = stabilizer.accept(event)
            for (stabilized in stabilizedEvents) {
                if (stabilized is RealtimeAsrPipelineEvent.Hypothesis) {
                    val punctuation = punctuationProcessor.process(stabilized)
                    add(
                        RealtimeTranscriptionOutput.Hypothesis(
                            source = stabilized,
                            rawText = punctuation.rawText,
                            displayHypothesis = punctuation.displayHypothesis,
                            canonicalTokens = stabilized.canonicalTokens(),
                            punctuationAttempted = punctuation.attempted,
                            externalPunctuationApplied = punctuation.externalPunctuationApplied,
                            externalPunctuationFailed = punctuation.externalPunctuationFailed,
                        ),
                    )
                } else {
                    add(RealtimeTranscriptionOutput.Lifecycle(stabilized))
                }
            }
        }
    }
}
