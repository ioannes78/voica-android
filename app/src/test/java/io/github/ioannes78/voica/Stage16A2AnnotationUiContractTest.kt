package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A2AnnotationUiContractTest {
    @Test
    fun recordingDetailKeepsReadingCopySemanticsAndAddsFourthAnnotationTab() {
        val detail = appProductionSource("ui/library/RecordingDetailProductScreen.kt")
        val projection = appProductionSource("ui/transcript/TranscriptAnnotationProjection.kt")

        assertTrue(detail.contains("ProductDetailTab.ANNOTATIONS"))
        assertTrue(detail.contains("ProductDetailTab.ANNOTATIONS -> \"标注\""))
        assertTrue(detail.contains("TimelineAnnotationPanel("))
        assertTrue(detail.contains("RecordingDetailDestination.ANNOTATIONS"))
        assertTrue(
            "Stage 16A.2 must preserve the transcript reading-mode branch",
            detail.contains("transcriptViewMode == TranscriptViewMode.READING"),
        )
        assertTrue(detail.contains("TranscriptAnnotatedReading("))
        assertTrue(
            "Reading text stays selectable while annotation UI stays outside text selection",
            projection.contains("SelectionContainer") && projection.contains("DisableSelection"),
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
    fun waveformAddsCanonicalPointMarkersAndFineLongPressWithoutReplacingSeekGestures() {
        val playback = appProductionSource("ui/playback/PlaybackCard.kt")
        val detail = appProductionSource("ui/library/RecordingDetailProductScreen.kt")

        assertTrue(playback.contains("annotationSampleIndices: List<Long>"))
        assertTrue(playback.contains("markerProgress.forEach"))
        assertTrue(playback.contains("detectDragGesturesAfterLongPress("))
        assertTrue(playback.contains("fineAnnotationAdjustedRatio("))
        assertTrue(
            "waveform drag-to-seek must remain available",
            playback.contains("detectDragGestures("),
        )
        assertTrue(
            "tap-to-seek must remain available",
            playback.contains("detectTapGestures("),
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
