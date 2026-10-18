package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A2AnnotationUiContractTest {
    @Test
    fun recordingDetailKeepsExistingReadingModeAndAddsFourthAnnotationTab() {
        val source = appProductionSource("ui/library/RecordingDetailProductScreen.kt")

        assertTrue(source.contains("ProductDetailTab.ANNOTATIONS"))
        assertTrue(source.contains("ProductDetailTab.ANNOTATIONS -> \"标注\""))
        assertTrue(source.contains("TimelineAnnotationPanel("))
        assertTrue(source.contains("RecordingDetailDestination.ANNOTATIONS"))
        assertTrue(
            "Stage 16A.2 must not remove the continuous reading/copy surface",
            source.contains("TranscriptContinuousReading("),
        )
        assertTrue(
            "Stage 16A.2 must preserve the existing transcript reading-mode branch",
            source.contains("transcriptViewMode == TranscriptViewMode.READING"),
        )
    }

    private fun appProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app production source not found: $relative")
    }
}
