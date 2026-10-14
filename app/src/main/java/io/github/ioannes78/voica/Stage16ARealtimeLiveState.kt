package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutputSink
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutputSinkOfferResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Stage16ARealtimeUiPhase {
    IDLE,
    PREPARING,
    STARTING,
    ACTIVE,
    PAUSED,
    RESUMING,
    FINALIZING,
    INTERRUPTED,
    COMPLETED,
    FAILED,
}

data class Stage16ARealtimeFinalText(
    val sessionId: Long,
    val epochId: Long,
    val continuityIndex: Long,
    val text: String,
)

data class Stage16ARealtimeUiState(
    val phase: Stage16ARealtimeUiPhase = Stage16ARealtimeUiPhase.IDLE,
    val runId: String? = null,
    val modelId: String? = null,
    val modelLabel: String? = null,
    val finalized: List<Stage16ARealtimeFinalText> = emptyList(),
    /** Full stable prefix for the current recognizer continuity. */
    val stableText: String = "",
    /** Full latest native partial for the current recognizer continuity. */
    val partialText: String = "",
    /** Bounded, normalized canonical-PCM activity envelope for the product waveform. */
    val audioActivityLevels: List<Float> = emptyList(),
    val lifecycleMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    /** Only this suffix is allowed to visually mutate in the reader UI. */
    val mutablePartialTail: String
        get() =
            if (stableText.isNotEmpty() && partialText.startsWith(stableText)) {
                partialText.removePrefix(stableText)
            } else {
                partialText
            }
}

/**
 * Non-blocking product-state branch below Stage 16A inference.
 *
 * The sink deliberately stores only lightweight immutable text/lifecycle/audio-envelope state. It
 * never performs Room, file, model, BLE, or native work, so UI observation cannot backpressure the
 * realtime ASR runner. PARTIAL remains one replaceable tail; FINAL text is the only text appended to
 * history. PCM analysis itself is performed by [Stage16ARealtimeAudioActivitySink].
 */
class Stage16ARealtimeLiveStateStore :
    RealtimeTranscriptionOutputSink,
    Stage16ARealtimeAudioActivityConsumer {
    private val lock = Any()
    private val mutableState = MutableStateFlow(Stage16ARealtimeUiState())
    val state: StateFlow<Stage16ARealtimeUiState> = mutableState.asStateFlow()

    override fun offer(output: RealtimeTranscriptionOutput): RealtimeTranscriptionOutputSinkOfferResult {
        synchronized(lock) {
            mutableState.value = reduce(mutableState.value, output)
        }
        return RealtimeTranscriptionOutputSinkOfferResult.ACCEPTED
    }

    override fun onRealtimeAudioActivity(level: Float) = mutate { current ->
        if (current.phase != Stage16ARealtimeUiPhase.ACTIVE) {
            current
        } else {
            val safeLevel = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
            val nextLevels =
                if (current.audioActivityLevels.size >= AUDIO_ACTIVITY_WINDOW_SIZE) {
                    current.audioActivityLevels.drop(1) + safeLevel
                } else {
                    current.audioActivityLevels + safeLevel
                }
            current.copy(audioActivityLevels = nextLevels)
        }
    }

    fun preparing(runId: String, modelId: String, modelLabel: String) = mutate { current ->
        current.copy(
            phase = Stage16ARealtimeUiPhase.PREPARING,
            runId = runId,
            modelId = modelId,
            modelLabel = modelLabel,
            finalized = emptyList(),
            stableText = "",
            partialText = "",
            audioActivityLevels = emptyList(),
            lifecycleMessage = null,
            errorCode = null,
            errorMessage = null,
        )
    }

    fun starting() = mutate { it.copy(phase = Stage16ARealtimeUiPhase.STARTING) }

    fun paused() = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.PAUSED,
            stableText = "",
            partialText = "",
            audioActivityLevels = emptyList(),
            lifecycleMessage = "已暂停",
        )
    }

    fun resuming() = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.RESUMING,
            audioActivityLevels = emptyList(),
            lifecycleMessage = "正在继续实时转写…",
        )
    }

    fun finalizing() = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.FINALIZING,
            audioActivityLevels = emptyList(),
            lifecycleMessage = "正在完成最后内容…",
        )
    }

    fun interrupted(
        message: String,
        code: String? = null,
        errorMessage: String? = null,
    ) = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.INTERRUPTED,
            stableText = "",
            partialText = "",
            audioActivityLevels = emptyList(),
            lifecycleMessage = message,
            errorCode = code,
            errorMessage = errorMessage,
        )
    }

    fun completed() = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.COMPLETED,
            stableText = "",
            partialText = "",
            audioActivityLevels = emptyList(),
            lifecycleMessage = null,
            errorCode = null,
            errorMessage = null,
        )
    }

    fun completedDegraded(code: String, message: String) = mutate {
        it.copy(
            phase = Stage16ARealtimeUiPhase.COMPLETED,
            stableText = "",
            partialText = "",
            audioActivityLevels = emptyList(),
            lifecycleMessage = message,
            errorCode = code,
            // Keep this as lifecycle/degraded information, not a fatal UI error.
            errorMessage = null,
        )
    }

    /** Reports a degraded side branch without turning off recorder media or realtime inference. */
    fun degraded(code: String, message: String) = mutate {
        it.copy(
            lifecycleMessage = message,
            errorCode = code,
            errorMessage = message,
        )
    }

    fun failed(code: String, message: String) {
        if (Stage16AStopFinalizationPolicy.isDegradedCompletedStop(code)) {
            completedDegraded(
                code = code,
                message = Stage16AStopFinalizationPolicy.DEGRADED_MESSAGE,
            )
            return
        }
        mutate {
            it.copy(
                phase = Stage16ARealtimeUiPhase.FAILED,
                stableText = "",
                partialText = "",
                audioActivityLevels = emptyList(),
                lifecycleMessage = null,
                errorCode = code,
                errorMessage = message,
            )
        }
    }

    fun reset() = synchronized(lock) {
        mutableState.value = Stage16ARealtimeUiState()
    }

    private fun mutate(block: (Stage16ARealtimeUiState) -> Stage16ARealtimeUiState) {
        synchronized(lock) {
            mutableState.value = block(mutableState.value)
        }
    }

    private fun reduce(
        current: Stage16ARealtimeUiState,
        output: RealtimeTranscriptionOutput,
    ): Stage16ARealtimeUiState =
        when (output) {
            is RealtimeTranscriptionOutput.Hypothesis -> reduceHypothesis(current, output)
            is RealtimeTranscriptionOutput.Lifecycle -> reduceLifecycle(current, output.event)
        }

    private fun reduceHypothesis(
        current: Stage16ARealtimeUiState,
        output: RealtimeTranscriptionOutput.Hypothesis,
    ): Stage16ARealtimeUiState =
        when (output.stability) {
            AsrHypothesisStability.PARTIAL ->
                current.copy(
                    partialText = output.displayText,
                    errorCode = null,
                    errorMessage = null,
                )

            AsrHypothesisStability.STABLE ->
                current.copy(
                    stableText = output.displayText,
                    errorCode = null,
                    errorMessage = null,
                )

            AsrHypothesisStability.FINAL -> {
                val text = output.displayText.trim()
                current.copy(
                    finalized =
                        if (text.isBlank()) {
                            current.finalized
                        } else {
                            current.finalized +
                                Stage16ARealtimeFinalText(
                                    sessionId = output.sessionId,
                                    epochId = output.source.epochId,
                                    continuityIndex = output.source.continuityIndex,
                                    text = text,
                                )
                        },
                    stableText = "",
                    partialText = "",
                    errorCode = null,
                    errorMessage = null,
                )
            }
        }

    private fun reduceLifecycle(
        current: Stage16ARealtimeUiState,
        event: RealtimeAsrPipelineEvent,
    ): Stage16ARealtimeUiState =
        when (event) {
            is RealtimeAsrPipelineEvent.SessionStarted ->
                current.copy(
                    phase = Stage16ARealtimeUiPhase.ACTIVE,
                    stableText = "",
                    partialText = "",
                    audioActivityLevels = emptyList(),
                    lifecycleMessage =
                        if (current.phase == Stage16ARealtimeUiPhase.INTERRUPTED) {
                            "已恢复实时转写"
                        } else {
                            null
                        },
                    errorCode = null,
                    errorMessage = null,
                )

            is RealtimeAsrPipelineEvent.ContinuityStarted ->
                current.copy(
                    phase =
                        if (
                            current.phase == Stage16ARealtimeUiPhase.PAUSED ||
                            current.phase == Stage16ARealtimeUiPhase.RESUMING
                        ) {
                            Stage16ARealtimeUiPhase.ACTIVE
                        } else {
                            current.phase
                        },
                    stableText = "",
                    partialText = "",
                    audioActivityLevels = emptyList(),
                )

            is RealtimeAsrPipelineEvent.Discontinuity ->
                current.copy(
                    stableText = "",
                    partialText = "",
                    audioActivityLevels = emptyList(),
                    lifecycleMessage = "实时音频发生间断，已从当前音频继续",
                )

            is RealtimeAsrPipelineEvent.SessionEnded ->
                when (event.reason) {
                    RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED ->
                        current.copy(
                            phase = Stage16ARealtimeUiPhase.INTERRUPTED,
                            stableText = "",
                            partialText = "",
                            audioActivityLevels = emptyList(),
                            lifecycleMessage = "连接已中断 · 设备仍可能继续录音",
                        )

                    RealtimeCanonicalSessionEndReason.STOP,
                    RealtimeCanonicalSessionEndReason.DEVICE_RECORDING_ENDED,
                    ->
                        current.copy(
                            phase = Stage16ARealtimeUiPhase.FINALIZING,
                            stableText = "",
                            partialText = "",
                            audioActivityLevels = emptyList(),
                            lifecycleMessage = "正在完成最后内容…",
                        )

                    RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED,
                    RealtimeCanonicalSessionEndReason.SUPERSEDED_BY_NEW_SESSION,
                    ->
                        current.copy(
                            phase = Stage16ARealtimeUiPhase.INTERRUPTED,
                            stableText = "",
                            partialText = "",
                            audioActivityLevels = emptyList(),
                            lifecycleMessage = "实时转写已中断",
                        )
                }

            is RealtimeAsrPipelineEvent.VadSegment -> current
            is RealtimeAsrPipelineEvent.Hypothesis ->
                error("hypothesis must arrive as RealtimeTranscriptionOutput.Hypothesis")
        }

    private companion object {
        const val AUDIO_ACTIVITY_WINDOW_SIZE = 28
    }
}
