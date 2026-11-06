package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.OfflineSummaryRunPhase
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.OfflineSummaryTaskPresentation
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.OfflineSummaryLongProductProgress
import io.github.ioannes78.voica.database.OfflineSummaryLongProductStage
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSummaryProductProgressPresentationTest {
    @Test
    fun structuredMapProgressWinsOverHumanMessageText() {
        val run = run(message = "正在合并第 9 层 9/9")
        val progress =
            OfflineSummaryLongProductProgress(
                stage = OfflineSummaryLongProductStage.MAPPING,
                current = 2,
                total = 5,
            )

        assertEquals("正在分段分析", offlineSummaryRuntimeTitle(run, progress))
        assertEquals("分段 2/5", offlineSummaryRuntimeDetail(run, progress))
    }

    @Test
    fun structuredReduceProgressShowsTreeLevelAndBoundedStep() {
        val run = run(message = null)
        val progress =
            OfflineSummaryLongProductProgress(
                stage = OfflineSummaryLongProductStage.REDUCING,
                current = 1,
                total = 3,
                level = 2,
            )

        assertEquals("正在层级合并", offlineSummaryRuntimeTitle(run, progress))
        assertEquals("第 2 层 · 1/3", offlineSummaryRuntimeDetail(run, progress))
    }

    @Test
    fun planningAndFinalValidationHaveExplicitStructuredTitles() {
        val run = run(message = "human-facing detail")

        assertEquals(
            "正在规划长文本",
            offlineSummaryRuntimeTitle(
                run,
                OfflineSummaryLongProductProgress(OfflineSummaryLongProductStage.PLANNING),
            ),
        )
        assertEquals(
            "正在生成最终总结",
            offlineSummaryRuntimeTitle(
                run.copy(phase = OfflineSummaryRunPhase.VALIDATING),
                OfflineSummaryLongProductProgress(OfflineSummaryLongProductStage.VALIDATING),
            ),
        )
    }

    @Test
    fun existingForegroundPresentationCarriesSameLongProgressText() {
        val presentation =
            OfflineSummaryTaskPresentation(
                recordingId = "recording",
                summaryId = "summary",
                phase = OfflineSummaryRunPhase.ANALYZING,
                message = "正在分析分段 2/5",
            )

        assertEquals("本地总结 · 正在总结 · 正在分析分段 2/5", presentation.label)
    }

    @Test
    fun resumableFailureNoticeCarriesPersistedCodeAndMessage() {
        val summary =
            AiSummaryEntity(
                id = "summary",
                recordingId = "recording",
                transcriptionId = "transcription",
                inputMode = "TRANSCRIPT_TEXT",
                mode = "SMART",
                model = "Gemma 4 E2B",
                promptVersion = 1,
                resultSchemaVersion = 1,
                contentType = null,
                classificationConfidence = null,
                structuredPayloadJson = null,
                displayText = null,
                status = AiSummaryStateValue.FAILED,
                createdAtMs = 1L,
                startedAtMs = 2L,
                updatedAtMs = 3L,
                completedAtMs = null,
                errorCode = "GENERATION_FAILED",
                sanitizedErrorMessage = "native generation failed",
                alignmentIdSnapshot = null,
                sourceLineageSnapshot = "lineage",
            )

        assertEquals(
            "失败诊断 · GENERATION_FAILED · native generation failed",
            offlineSummaryResumableFailureNotice(summary),
        )
    }

    private fun run(message: String?): OfflineSummaryRunState =
        OfflineSummaryRunState(
            recordingId = "recording",
            summaryId = "summary",
            phase = OfflineSummaryRunPhase.ANALYZING,
            message = message,
            estimatedPromptTokens = 3_000,
        )
}
