package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryInputModeValue
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRemoteDispatchStateValue
import io.github.ioannes78.voica.database.AiSummaryStateValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AiSummaryDurableStateTest {
    @Test
    fun activeRoomTaskRestoresRunningStateAfterProcessRestart() {
        val state =
            projectAiSummaryRunState(
                durableTasks = listOf(summary(status = AiSummaryStateValue.REDUCING)),
                transientState = AiSummaryRunState.Idle,
            )

        val running = state as AiSummaryRunState.Running
        assertEquals(SUMMARY_ID, running.summaryId)
        assertEquals(RECORDING_ID, running.recordingId)
        assertEquals(AiSummaryEnginePhase.REDUCING, running.phase)
    }

    @Test
    fun matchingTransientRunningKeepsFineGrainedProgress() {
        val transient =
            AiSummaryRunState.Running(
                summaryId = SUMMARY_ID,
                recordingId = RECORDING_ID,
                transcriptionId = TRANSCRIPTION_ID,
                phase = AiSummaryEnginePhase.MAPPING,
                completedUnits = 3,
                totalUnits = 8,
            )

        val state =
            projectAiSummaryRunState(
                durableTasks = listOf(summary(status = AiSummaryStateValue.MAPPING)),
                transientState = transient,
            )

        assertSame(transient, state)
    }

    @Test
    fun durableTerminalOverridesStaleRunningState() {
        val state =
            projectAiSummaryRunState(
                durableTasks =
                    listOf(
                        summary(
                            status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                            errorCode = "REMOTE_RESULT_UNKNOWN",
                            message = "上一次请求状态无法确认，需要手动重试。",
                        ),
                    ),
                transientState =
                    AiSummaryRunState.Running(
                        summaryId = SUMMARY_ID,
                        recordingId = RECORDING_ID,
                        transcriptionId = TRANSCRIPTION_ID,
                        phase = AiSummaryEnginePhase.ANALYZING,
                    ),
            )

        val failed = state as AiSummaryRunState.Failed
        assertEquals(SUMMARY_ID, failed.summaryId)
        assertEquals("REMOTE_RESULT_UNKNOWN", failed.errorCode)
    }

    @Test
    fun acknowledgedTerminalCannotBeRevivedByStaleTransientState() {
        val completed =
            projectAiSummaryRunState(
                durableTasks = emptyList(),
                transientState =
                    AiSummaryRunState.Completed(
                        summaryId = SUMMARY_ID,
                        recordingId = RECORDING_ID,
                        transcriptionId = TRANSCRIPTION_ID,
                    ),
            )
        val failed =
            projectAiSummaryRunState(
                durableTasks = emptyList(),
                transientState =
                    AiSummaryRunState.Failed(
                        summaryId = SUMMARY_ID,
                        recordingId = RECORDING_ID,
                        transcriptionId = TRANSCRIPTION_ID,
                        errorCode = "FAILED",
                        message = "failed",
                    ),
            )

        assertEquals(AiSummaryRunState.Idle, completed)
        assertEquals(AiSummaryRunState.Idle, failed)
    }

    @Test
    fun preCreationRunningStateIsVisibleWhileNoDurableRowExists() {
        val transient =
            AiSummaryRunState.Running(
                summaryId = null,
                recordingId = RECORDING_ID,
                transcriptionId = TRANSCRIPTION_ID,
                phase = AiSummaryEnginePhase.PREPARING,
            )

        assertSame(
            transient,
            projectAiSummaryRunState(
                durableTasks = emptyList(),
                transientState = transient,
            ),
        )
    }

    @Test
    fun activeTaskWinsOverOlderUnacknowledgedTerminalTask() {
        val active = summary(id = "active", status = AiSummaryStateValue.ANALYZING, updatedAtMs = 30L)
        val completed = summary(id = "completed", status = AiSummaryStateValue.COMPLETED, updatedAtMs = 40L)

        val state =
            projectAiSummaryRunState(
                durableTasks = listOf(completed, active),
                transientState = AiSummaryRunState.Idle,
            )

        val running = state as AiSummaryRunState.Running
        assertEquals("active", running.summaryId)
    }

    @Test
    fun ambiguousManualRetryClonesOriginalFrozenLineageAndConfiguration() {
        val original =
            summary(
                id = "ambiguous-old",
                status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                errorCode = "REMOTE_RESULT_UNKNOWN",
                message = "上一次请求状态无法确认，需要手动重试。",
            ).copy(
                mode = AiSummaryModeValue.CUSTOM,
                templateId = "template-old",
                templateSnapshot = "{\"id\":\"template-old\",\"focus\":\"old\"}",
                providerProfileId = "provider-old",
                providerNameSnapshot = "Provider Old",
                baseUrlSnapshot = "https://old.example.invalid/v1",
                model = "model-old",
                promptVersion = 7,
                resultSchemaVersion = 3,
                requestConfigSnapshot =
                    "{\"transcriptionRevisionId\":\"revision-old\",\"inputContentDigest\":\"digest-old\"}",
                alignmentIdSnapshot = "alignment-old",
                sourceLineageSnapshot =
                    "{\"transcriptionRevisionId\":\"revision-old\",\"inputContentDigest\":\"digest-old\"}",
            )
        val immutableBeforeRetry = original.copy()

        val retry = frozenRetryRequest(original)

        assertEquals(original.recordingId, retry.recordingId)
        assertEquals(original.transcriptionId, retry.transcriptionId)
        assertEquals(original.mode, retry.mode)
        assertEquals(original.templateId, retry.templateId)
        assertEquals(original.templateSnapshot, retry.templateSnapshot)
        assertEquals(original.providerProfileId, retry.providerProfileId)
        assertEquals(original.providerNameSnapshot, retry.providerNameSnapshot)
        assertEquals(original.baseUrlSnapshot, retry.baseUrlSnapshot)
        assertEquals(original.model, retry.model)
        assertEquals(original.promptVersion, retry.promptVersion)
        assertEquals(original.resultSchemaVersion, retry.resultSchemaVersion)
        assertEquals(original.requestConfigSnapshot, retry.requestConfigSnapshot)
        assertEquals(original.alignmentIdSnapshot, retry.alignmentIdSnapshot)
        assertEquals(original.sourceLineageSnapshot, retry.sourceLineageSnapshot)
        assertEquals(original.id, retry.retryOfSummaryId)
        assertEquals(immutableBeforeRetry, original)
    }

    private fun summary(
        id: String = SUMMARY_ID,
        status: String,
        errorCode: String? = null,
        message: String? = null,
        updatedAtMs: Long = 20L,
    ): AiSummaryEntity =
        AiSummaryEntity(
            id = id,
            recordingId = RECORDING_ID,
            transcriptionId = TRANSCRIPTION_ID,
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
            contentType = if (status == AiSummaryStateValue.COMPLETED) "GENERAL" else null,
            classificationConfidence = null,
            structuredPayloadJson = if (status == AiSummaryStateValue.COMPLETED) "{}" else null,
            displayText = if (status == AiSummaryStateValue.COMPLETED) "完成" else null,
            status = status,
            createdAtMs = 10L,
            startedAtMs = 11L,
            updatedAtMs = updatedAtMs,
            completedAtMs =
                if (status in setOf(
                        AiSummaryStateValue.COMPLETED,
                        AiSummaryStateValue.FAILED,
                        AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                    )
                ) {
                    updatedAtMs
                } else {
                    null
                },
            errorCode = errorCode,
            sanitizedErrorMessage = message,
            requestConfigSnapshot = "{}",
            usageSnapshot = null,
            alignmentIdSnapshot = null,
            sourceLineageSnapshot = "{}",
            executionGeneration = 1L,
            remoteDispatchState = AiSummaryRemoteDispatchStateValue.NONE,
            remoteRequestId = null,
            remoteStepKind = null,
            remoteStepKey = null,
            remoteCallOrdinal = 0,
            remoteStartedAtMs = null,
            terminalAcknowledgedAtMs = null,
            retryOfSummaryId = null,
        )

    private companion object {
        const val SUMMARY_ID = "summary-1"
        const val RECORDING_ID = "recording-1"
        const val TRANSCRIPTION_ID = "transcription-1"
    }
}
