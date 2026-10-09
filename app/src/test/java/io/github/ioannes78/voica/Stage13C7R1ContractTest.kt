package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.DiarizationAttentionItem
import io.github.ioannes78.voica.database.DiarizationAttentionKind
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage13C7R1ContractTest {
    @Test
    fun normalRuntimeDoesNotAttachDiarizationProfilersOrBoundaryExporter() {
        val source = productionSource("VoicaApplication.kt")

        assertTrue(
            source.contains(
                "engineProvider = stage9VadReusingDiarizationEngineProvider",
            ),
        )
        assertFalse(source.contains("Stage13CBoundaryProfilingProvider("))
        assertFalse(source.contains("diarizationBenchmarkRunner.attach(diarizationCoordinator)"))
        assertFalse(source.contains("DiarizationBenchmarkRunner("))
    }

    @Test
    fun transcribedSpeakerSheetAllowsSameModeRerun() {
        val source = productionSource("ui/library/RecordingDetailProductScreen.kt")
        val sheet =
            source.substringAfter("private fun SpeakerCountBottomSheet(")
                .substringBefore("private fun speakerCountDescription")

        assertTrue(sheet.contains("if (transcribed) {"))
        assertFalse(sheet.contains("transcribed && selectedMode != currentMode"))
        assertTrue(sheet.contains("重新识别"))
    }

    @Test
    fun durableSpeakerAttentionUsesChineseProductLabelsAndTranscriptDestination() {
        val run =
            DiarizationAttentionItem(
                kind = DiarizationAttentionKind.DIARIZATION,
                id = "dia-1",
                recordingId = "recording-a",
                state = DiarizationStateValue.INTERRUPTED,
                completedAtMs = 1L,
                errorCode = "PROCESS_INTERRUPTED",
                errorMessage = null,
            )
        val alignment =
            run.copy(
                kind = DiarizationAttentionKind.ALIGNMENT,
                id = "alignment-1",
                state = TranscriptSpeakerAlignmentStateValue.FAILED_RECOVERABLE,
            )

        assertEquals("说话人识别已中断", run.productLabel())
        assertEquals("说话人处理失败", alignment.productLabel())
        val items = buildDiarizationGlobalAttentionItems(listOf(run, alignment), emptyList())
        assertTrue(items.all { it.destination == RecordingDetailDestination.TRANSCRIPT })
        assertTrue(items.all { it.completionKind == null && it.completionTaskId == null })
    }

    private fun productionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("production source not found: $relative")
    }
}
