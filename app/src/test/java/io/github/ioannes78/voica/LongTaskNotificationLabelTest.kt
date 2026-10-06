package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgress
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class LongTaskNotificationLabelTest {
    @Test
    fun transcriptionLabelUsesProductModelAndRealProgress() {
        val running =
            TranscriptionRunState.Running(
                recordingId = "recording-a",
                transcriptionId = "transcription-a",
                mode = TranscriptionMode.HIGH_QUALITY,
                modelId = "qwen3-asr-0.6b-int8",
                progress =
                    TranscriptionProgress(
                        phase = TranscriptionPhase.SECOND_PASS,
                        processedUnits = 25L,
                        totalUnits = 100L,
                    ),
            )

        assertEquals(
            "转写中 · Qwen3-ASR · 正在识别 · 25%",
            buildTranscriptionLabel(running, 25),
        )
    }

    @Test
    fun diarizationAlignmentHasUserFacingChineseLabel() {
        val running =
            DiarizationRunState.Running(
                recordingId = "recording-a",
                runId = "run-a",
                progress =
                    DiarizationProgress(
                        phase = DiarizationPhase.ALIGNMENT,
                        processedSamples = 50L,
                        totalSamples = 100L,
                    ),
            )

        assertEquals(
            "说话人分离 · 正在对齐转写 · 50%",
            buildDiarizationLabel(running, 50),
        )
    }

    @Test
    fun aiSummaryMappingUsesTruthfulUnitCounts() {
        val running =
            AiSummaryRunState.Running(
                summaryId = "summary-a",
                recordingId = "recording-a",
                transcriptionId = "transcription-a",
                phase = AiSummaryEnginePhase.MAPPING,
                completedUnits = 3,
                totalUnits = 8,
            )

        assertEquals(
            "AI 总结 · 正在生成 3/8",
            buildAiSummaryLabel(running),
        )
    }
}
