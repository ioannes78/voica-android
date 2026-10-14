package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.transcript.AsrHypothesis
import io.github.ioannes78.voica.transcript.AsrHypothesisStability
import io.github.ioannes78.voica.transcript.PunctuationCapability
import io.github.ioannes78.voica.transcript.RealtimeAsrPipelineEvent
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimeLiveStateStoreTest {
    @Test
    fun `stable prefix leaves only mutable suffix and final becomes history`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing("run-a", "small", "Small")
        store.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)))

        store.offer(hypothesis("你好世界", AsrHypothesisStability.PARTIAL))
        store.offer(hypothesis("你好", AsrHypothesisStability.STABLE))

        assertEquals(Stage16ARealtimeUiPhase.ACTIVE, store.state.value.phase)
        assertEquals("你好", store.state.value.stableText)
        assertEquals("世界", store.state.value.mutablePartialTail)
        assertTrue(store.state.value.finalized.isEmpty())

        store.offer(hypothesis("你好，世界。", AsrHypothesisStability.FINAL))

        assertEquals("", store.state.value.stableText)
        assertEquals("", store.state.value.partialText)
        assertEquals(listOf("你好，世界。"), store.state.value.finalized.map { it.text })
    }

    @Test
    fun `transport close preserves final history and next session reports recovery`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing("run-a", "small", "Small")
        store.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)))
        store.offer(hypothesis("第一段", AsrHypothesisStability.FINAL))
        store.offer(
            lifecycle(
                RealtimeAsrPipelineEvent.SessionEnded(
                    sessionId = 1,
                    reason = RealtimeCanonicalSessionEndReason.TRANSPORT_CLOSED,
                    drainTimedOut = false,
                ),
            ),
        )

        assertEquals(Stage16ARealtimeUiPhase.INTERRUPTED, store.state.value.phase)
        assertEquals("连接已中断 · 设备仍可能继续录音", store.state.value.lifecycleMessage)
        assertEquals(listOf("第一段"), store.state.value.finalized.map { it.text })

        store.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(2)))

        assertEquals(Stage16ARealtimeUiPhase.ACTIVE, store.state.value.phase)
        assertEquals("已恢复实时转写", store.state.value.lifecycleMessage)
        assertEquals(listOf("第一段"), store.state.value.finalized.map { it.text })
    }

    @Test
    fun `new continuity clears only mutable tail`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing("run-a", "small", "Small")
        store.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)))
        store.offer(hypothesis("已经完成", AsrHypothesisStability.FINAL))
        store.offer(hypothesis("临时文字", AsrHypothesisStability.PARTIAL))
        store.resuming()

        store.offer(
            lifecycle(
                RealtimeAsrPipelineEvent.ContinuityStarted(
                    sessionId = 1,
                    epochId = 2,
                    continuityIndex = 2,
                    startSampleIndex = null,
                    epochReason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY,
                ),
            ),
        )

        assertEquals(Stage16ARealtimeUiPhase.ACTIVE, store.state.value.phase)
        assertEquals("", store.state.value.partialText)
        assertEquals(listOf("已经完成"), store.state.value.finalized.map { it.text })
    }

    @Test
    fun `audio activity is bounded clamped and cleared by pause`() {
        val store = Stage16ARealtimeLiveStateStore()
        store.preparing("run-a", "small", "Small")
        store.offer(lifecycle(RealtimeAsrPipelineEvent.SessionStarted(1)))

        store.onRealtimeAudioActivity(Float.NaN)
        store.onRealtimeAudioActivity(2f)
        repeat(32) { index ->
            store.onRealtimeAudioActivity(index / 32f)
        }

        val activeLevels = store.state.value.audioActivityLevels
        assertEquals(28, activeLevels.size)
        assertTrue(activeLevels.all { it in 0f..1f })

        store.paused()
        assertTrue(store.state.value.audioActivityLevels.isEmpty())
        store.onRealtimeAudioActivity(0.8f)
        assertTrue(store.state.value.audioActivityLevels.isEmpty())
    }

    private fun lifecycle(event: RealtimeAsrPipelineEvent): RealtimeTranscriptionOutput =
        RealtimeTranscriptionOutput.Lifecycle(event)

    private fun hypothesis(
        text: String,
        stability: AsrHypothesisStability,
    ): RealtimeTranscriptionOutput {
        val isFinal = stability == AsrHypothesisStability.FINAL
        val asr =
            AsrHypothesis(
                text = text,
                punctuationCapability = PunctuationCapability.NONE,
                isFinal = isFinal,
                stability = stability,
            )
        return RealtimeTranscriptionOutput.Hypothesis(
            source =
                RealtimeAsrPipelineEvent.Hypothesis(
                    sessionId = 1,
                    epochId = 1,
                    continuityIndex = 1,
                    recognizerBaseSampleIndex = 0,
                    acceptedEndSampleIndexExclusive = 16_000,
                    hypothesis = asr,
                    finalizationReason =
                        if (isFinal) {
                            io.github.ioannes78.voica.transcript.RealtimeAsrFinalizationReason.VAD_SPEECH_END
                        } else {
                            null
                        },
                ),
            rawText = text,
            displayHypothesis = asr,
            canonicalTokens = emptyList(),
            punctuationAttempted = false,
            externalPunctuationApplied = false,
            externalPunctuationFailed = false,
        )
    }
}
