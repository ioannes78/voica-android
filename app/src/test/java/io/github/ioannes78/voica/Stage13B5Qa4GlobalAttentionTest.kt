package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryInputModeValue
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRemoteDispatchStateValue
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionModeValue
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage13B5Qa4GlobalAttentionTest {
    @Test
    fun durableAttentionMapsToPersistentAppNotificationTargets() {
        val items =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = listOf(transcription(TranscriptionStateValue.INTERRUPTED)),
                aiSummaryAttention = listOf(summary(AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT)),
                recordings = emptyList(),
            )

        assertEquals(2, items.size)
        assertEquals("转写已中断", items[0].label)
        assertEquals(RecordingDetailDestination.TRANSCRIPT, items[0].destination)
        assertEquals("总结状态待确认", items[1].label)
        assertEquals(RecordingDetailDestination.SUMMARY, items[1].destination)
    }

    @Test
    fun failedSummaryUsesFailureLabel() {
        val item =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = emptyList(),
                aiSummaryAttention = listOf(summary(AiSummaryStateValue.FAILED)),
                recordings = emptyList(),
            ).single()

        assertEquals("总结失败", item.label)
    }

    @Test
    fun candidateProjectionIsSuppressedOnlyOnMatchingDetailPage() {
        val items =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = emptyList(),
                aiSummaryAttention = emptyList(),
                recordings = emptyList(),
                transcriptionCandidates = listOf(transcription(TranscriptionStateValue.COMPLETED)),
                aiSummaryCandidates = listOf(summary(AiSummaryStateValue.COMPLETED)),
            )

        assertEquals(2, items.size)
        assertEquals("新的转写结果已生成", items[0].label)
        assertEquals("新的总结结果已生成", items[1].label)

        val filtered =
            filterQa4GlobalAttentionItems(
                items = items,
                visibleDetail =
                    Qa4VisibleDetailContext(
                        recordingId = "recording",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                    ),
            )

        assertEquals(1, filtered.size)
        assertEquals(RecordingDetailDestination.SUMMARY, filtered.single().destination)
    }

    private fun transcription(state: String): TranscriptionEntity =
        TranscriptionEntity(
            id = "tx",
            recordingId = "recording",
            mode = TranscriptionModeValue.HIGH_QUALITY,
            state = state,
            sourceCanonicalAssetId = "canonical",
            sourceCanonicalSha256 = "a".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 16_000L,
            pipelineVersion = 2,
            runtimeId = "sherpa-onnx",
            runtimeVersion = "1",
            vadModelId = "vad",
            vadModelVersion = "1",
            firstPassAsrModelId = "asr",
            firstPassAsrModelVersion = "1",
            secondPassAsrModelId = null,
            secondPassAsrModelVersion = null,
            punctuationModelId = null,
            punctuationModelVersion = null,
            languageConfig = "auto",
            configSnapshot = "{}",
            modelManifestDigest = "b".repeat(64),
            createdAtMs = 1L,
            startedAtMs = 1L,
            updatedAtMs = 2L,
            completedAtMs = 2L,
            errorCode = "PROCESS_INTERRUPTED",
            errorMessage = "interrupted",
        )

    private fun summary(status: String): AiSummaryEntity =
        AiSummaryEntity(
            id = "summary",
            recordingId = "recording",
            transcriptionId = "tx",
            inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
            mode = AiSummaryModeValue.SMART,
            templateId = null,
            templateSnapshot = null,
            providerProfileId = "provider",
            providerNameSnapshot = "Provider",
            baseUrlSnapshot = "https://example.invalid",
            model = "model",
            promptVersion = 1,
            resultSchemaVersion = 1,
            contentType = null,
            classificationConfidence = null,
            structuredPayloadJson = null,
            displayText = null,
            status = status,
            createdAtMs = 1L,
            startedAtMs = 1L,
            updatedAtMs = 2L,
            completedAtMs = 2L,
            errorCode = null,
            sanitizedErrorMessage = null,
            requestConfigSnapshot = "{}",
            usageSnapshot = null,
            alignmentIdSnapshot = null,
            sourceLineageSnapshot = "{}",
            executionGeneration = 1L,
            ownerTaskId = 1,
            remoteDispatchState = AiSummaryRemoteDispatchStateValue.NONE,
            terminalAcknowledgedAtMs = null,
            retryOfSummaryId = null,
        )
}
