package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AiSummaryStaleProjectionTest {
    @Test
    fun staleRunningTaskWithoutDurableRowCannotReviveCancelledTask() {
        val state =
            projectAiSummaryRunState(
                durableTasks = emptyList(),
                transientState =
                    AiSummaryRunState.Running(
                        summaryId = "summary-cancelled",
                        recordingId = "recording-1",
                        transcriptionId = "transcription-1",
                        phase = AiSummaryEnginePhase.ANALYZING,
                    ),
            )

        assertEquals(AiSummaryRunState.Idle, state)
    }
}
