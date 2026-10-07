package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryInputModeValue
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRemoteDispatchStateValue
import io.github.ioannes78.voica.database.AiSummaryStateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AiSummaryAttentionPolicyTest {
    @Test
    fun unresolvedAmbiguousIsAttention() {
        val ambiguous = summary(id = "a", status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT)

        assertSame(ambiguous, resolveAiSummaryAttention(listOf(ambiguous), null))
    }

    @Test
    fun acknowledgedAmbiguousIsNotAttention() {
        val ambiguous =
            summary(
                id = "a",
                status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                acknowledgedAtMs = 200L,
            )

        assertNull(resolveAiSummaryAttention(listOf(ambiguous), null))
    }

    @Test
    fun retryChildSuppressesAmbiguousParent() {
        val ambiguous = summary(id = "a", status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT)
        val retry =
            summary(
                id = "retry",
                status = AiSummaryStateValue.CREATED,
                createdAtMs = 200L,
                retryOfSummaryId = "a",
            )

        assertNull(resolveAiSummaryAttention(listOf(retry, ambiguous), null))
    }

    @Test
    fun newerCompletedDoesNotSuppressWithoutExplicitAdoption() {
        val ambiguous = summary(id = "a", status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT)
        val completed =
            summary(
                id = "b",
                status = AiSummaryStateValue.COMPLETED,
                createdAtMs = 200L,
            )

        assertSame(ambiguous, resolveAiSummaryAttention(listOf(completed, ambiguous), null))
    }

    @Test
    fun explicitlyAdoptedNewerCompletedSuppressesOlderAttention() {
        val ambiguous = summary(id = "a", status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT)
        val completed =
            summary(
                id = "b",
                status = AiSummaryStateValue.COMPLETED,
                createdAtMs = 200L,
            )

        assertNull(resolveAiSummaryAttention(listOf(completed, ambiguous), "b"))
    }

    @Test
    fun adoptedOlderCompletedDoesNotSuppressLaterFailure() {
        val completed =
            summary(
                id = "old",
                status = AiSummaryStateValue.COMPLETED,
                createdAtMs = 100L,
            )
        val failure =
            summary(
                id = "failure",
                status = AiSummaryStateValue.FAILED,
                createdAtMs = 300L,
            )

        assertSame(failure, resolveAiSummaryAttention(listOf(failure, completed), "old"))
    }

    @Test
    fun providerHttpErrorIsProductizedWithSecondaryDiagnostic() {
        val failure =
            summary(
                id = "http-402",
                status = AiSummaryStateValue.FAILED,
                providerName = "Google Gemini API / AI Studio",
                model = "gemini-3.5-flash-lite",
                errorCode = "PROVIDER_HTTP_ERROR",
                errorMessage = "provider HTTP 402",
            )

        assertEquals("AI 总结生成失败", aiSummaryAttentionTitle(failure))
        assertEquals("AI 服务请求失败，请检查服务或模型配置后重试。", aiSummaryAttentionMessage(failure))
        assertEquals(
            "Google Gemini API / AI Studio · gemini-3.5-flash-lite · HTTP 402",
            aiSummaryAttentionDiagnostic(failure),
        )
    }

    private fun summary(
        id: String,
        status: String,
        createdAtMs: Long = 100L,
        acknowledgedAtMs: Long? = null,
        retryOfSummaryId: String? = null,
        providerName: String = "Provider",
        model: String = "model",
        errorCode: String? = null,
        errorMessage: String? = null,
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
            providerNameSnapshot = providerName,
            baseUrlSnapshot = "https://example.invalid",
            model = model,
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
            errorCode = errorCode,
            sanitizedErrorMessage = errorMessage,
            requestConfigSnapshot = "{}",
            usageSnapshot = null,
            alignmentIdSnapshot = null,
            sourceLineageSnapshot = "{}",
            executionGeneration = 1L,
            ownerTaskId = 1,
            remoteDispatchState = AiSummaryRemoteDispatchStateValue.NONE,
            terminalAcknowledgedAtMs = acknowledgedAtMs,
            retryOfSummaryId = retryOfSummaryId,
        )
}
