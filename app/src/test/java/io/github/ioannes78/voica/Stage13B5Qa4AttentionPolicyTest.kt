package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryInputModeValue
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRemoteDispatchStateValue
import io.github.ioannes78.voica.database.AiSummaryStateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class Stage13B5Qa4AttentionPolicyTest {
    @Test
    fun newerDurableSummarySuppressesOlderReplaceableFailedAttention() {
        val failed = summary("failed", AiSummaryStateValue.FAILED, 100L)
        val replacement = summary("replacement", AiSummaryStateValue.CREATED, 200L)

        assertNull(resolveQa4AiSummaryAttention(listOf(replacement, failed), null))
    }

    @Test
    fun ordinaryNewerSummaryDoesNotSuppressAmbiguousAttention() {
        val ambiguous = summary("ambiguous", AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT, 100L)
        val newer = summary("newer", AiSummaryStateValue.CREATED, 200L)

        assertSame(ambiguous, resolveQa4AiSummaryAttention(listOf(newer, ambiguous), null))
    }

    @Test
    fun explicitRetryChildSuppressesAmbiguousParent() {
        val ambiguous = summary("ambiguous", AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT, 100L)
        val retry =
            summary("retry", AiSummaryStateValue.CREATED, 200L)
                .copy(retryOfSummaryId = ambiguous.id)

        assertNull(resolveQa4AiSummaryAttention(listOf(retry, ambiguous), null))
    }

    @Test
    fun latestFailedAttentionRemainsVisibleWhenNoReplacementExists() {
        val failed = summary("failed", AiSummaryStateValue.FAILED, 300L)

        assertEquals("failed", resolveQa4AiSummaryAttention(listOf(failed), null)?.id)
    }

    private fun summary(
        id: String,
        status: String,
        createdAtMs: Long,
    ): AiSummaryEntity =
        AiSummaryEntity(
            id = id,
            recordingId = "recording",
            transcriptionId = "transcription",
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
            createdAtMs = createdAtMs,
            startedAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            completedAtMs = createdAtMs.takeIf { status !in AiSummaryStateValue.ACTIVE },
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
