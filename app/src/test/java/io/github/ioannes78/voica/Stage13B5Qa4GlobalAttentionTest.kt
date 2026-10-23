package io.github.ioannes78.voica

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
}
