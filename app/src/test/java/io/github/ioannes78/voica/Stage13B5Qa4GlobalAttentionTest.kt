package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryInputModeValue
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.TranscriptionEntity
import io.github.ioannes78.voica.database.TranscriptionModeValue
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage13B5Qa4GlobalAttentionTest {
    @Test
    fun durableTranscriptionAttentionMapsToPersistentAppNotificationTarget() {
        val items =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = listOf(transcription(TranscriptionStateValue.INTERRUPTED)),
                recordings = emptyList(),
            )

        assertEquals(1, items.size)
        assertEquals("转写已中断", items.single().label)
        assertEquals(RecordingDetailDestination.TRANSCRIPT, items.single().destination)
    }

    @Test
    fun transcriptionCandidateIsSuppressedOnlyOnMatchingDetailPageAndRestoredAfterLeaving() {
        val items =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = emptyList(),
                recordings = emptyList(),
                transcriptionCandidates = listOf(transcription(TranscriptionStateValue.COMPLETED)),
            )

        assertEquals(1, items.size)
        assertEquals("新的转写结果已生成", items.single().label)

        val filtered =
            filterQa4GlobalAttentionItems(
                items = items,
                visibleDetail =
                    Qa4VisibleDetailContext(
                        recordingId = "recording",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                    ),
            )

        assertEquals(emptyList<Qa4GlobalAttentionItem>(), filtered)

        val restored =
            filterQa4GlobalAttentionItems(
                items = items,
                visibleDetail = null,
            )
        assertEquals(items, restored)
    }

    @Test
    fun summaryCandidateHasDurableAppAttentionUntilItsSummaryPageIsActuallyVisible() {
        val candidate = summary()
        val items =
            buildQa4GlobalAttentionItems(
                transcriptionAttention = emptyList(),
                recordings = emptyList(),
                summaryCandidates = listOf(candidate),
            )

        assertEquals(1, items.size)
        assertEquals("新的总结结果已生成", items.single().label)
        assertEquals(RecordingDetailDestination.SUMMARY, items.single().destination)

        assertEquals(
            listOf(candidate),
            filterVisibleSummaryCandidates(
                candidates = listOf(candidate),
                visibleDetail =
                    Qa4VisibleDetailContext(
                        recordingId = "recording",
                        destination = RecordingDetailDestination.TRANSCRIPT,
                    ),
            ),
        )
        assertEquals(
            emptyList<AiSummaryEntity>(),
            filterVisibleSummaryCandidates(
                candidates = listOf(candidate),
                visibleDetail =
                    Qa4VisibleDetailContext(
                        recordingId = "recording",
                        destination = RecordingDetailDestination.SUMMARY,
                    ),
            ),
        )
        assertEquals(
            listOf(candidate),
            filterVisibleSummaryCandidates(
                candidates = listOf(candidate),
                visibleDetail = null,
            ),
        )
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

    private fun summary(): AiSummaryEntity =
        AiSummaryEntity(
            id = "summary-candidate",
            recordingId = "recording",
            transcriptionId = "tx",
            inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
            mode = AiSummaryModeValue.SMART,
            model = "Gemma 4 E2B · 本地",
            promptVersion = 170204,
            resultSchemaVersion = 1,
            contentType = null,
            classificationConfidence = null,
            structuredPayloadJson = "{}",
            displayText = "candidate",
            status = AiSummaryStateValue.COMPLETED,
            createdAtMs = 3L,
            startedAtMs = 3L,
            updatedAtMs = 4L,
            completedAtMs = 4L,
            errorCode = null,
            sanitizedErrorMessage = null,
            alignmentIdSnapshot = null,
            sourceLineageSnapshot = "{}",
        )
}
