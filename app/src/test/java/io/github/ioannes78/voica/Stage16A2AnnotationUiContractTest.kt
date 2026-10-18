package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
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

    @Test
    fun annotationPanelExposesLocalPointCrudAndPhotoInputs() {
        val source = appProductionSource("ui/annotation/TimelineAnnotationPanel.kt")

        assertTrue(source.contains("快速标记当前时间"))
        assertTrue(source.contains("文字笔记"))
        assertTrue(source.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(source.contains("ActivityResultContracts.GetContent()"))
        assertTrue(source.contains("移动到当前播放位置"))
        assertTrue(source.contains("删除标注"))
        assertTrue(source.contains("timeline_annotations/"))
        assertFalse(
            "Modifier.weight must resolve from RowScope/ColumnScope instead of the internal layout import",
            source.contains("import androidx.compose.foundation.layout.weight"),
        )
    }

    @Test
    fun waveformAddsCanonicalPointMarkersWithoutReplacingSeekGestures() {
        val playback = appProductionSource("ui/playback/PlaybackCard.kt")
        val detail = appProductionSource("ui/library/RecordingDetailProductScreen.kt")

        assertTrue(playback.contains("annotationSampleIndices: List<Long>"))
        assertTrue(playback.contains("markerProgress.forEach"))
        assertTrue(playback.contains("onLongPress = { offset ->"))
        assertTrue(
            "waveform drag-to-seek must remain available",
            playback.contains("detectDragGestures("),
        )
        assertTrue(
            "the separate round progress tap surface must remain available",
            playback.contains("detectTapGestures { offset ->"),
        )
        assertTrue(
            "only canonical recording anchors may be projected onto the finalized waveform",
            detail.contains("annotation.anchorDomain == TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING"),
        )
        assertTrue(detail.contains("annotationViewModel.createAtSample("))
        assertTrue(detail.contains("annotationSampleIndices = waveformAnnotationSamples"))
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
