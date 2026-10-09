package io.github.ioannes78.voica.ui.transcript

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage13C7TranscriptLifecycleContractTest {
    @Test
    fun completedTranscriptIsPublishedBeforeSpeakerLookup() {
        val source = productionSource()
        val loadDocument = source.substringAfter("private suspend fun loadDocument(")
            .substringBefore("private fun requestSpeakerEnrichment(")

        val basePublish = loadDocument.indexOf("mutableDocument.value = baseDocument")
        val enrichmentStart = loadDocument.indexOf("requestSpeakerEnrichment(transcription.id)")

        assertTrue("base transcript must be published", basePublish >= 0)
        assertTrue("speaker enrichment must start after base publication", enrichmentStart > basePublish)
        assertFalse(
            "speaker repository lookup must not gate base transcript publication",
            loadDocument.substring(0, basePublish).contains("observeRuns("),
        )
    }

    @Test
    fun speakerCompletionRefreshesEnrichmentWithoutReloadingBaseDocument() {
        val source = productionSource()
        val diarizationCompleted =
            source.substringAfter("is DiarizationRunState.Completed -> {")
                .substringBefore("is DiarizationRunState.Failed -> {")
        val alignmentCompleted =
            source.substringAfter("is SpeakerAlignmentRunState.Completed -> {")
                .substringBefore("is SpeakerAlignmentRunState.Failed -> {")

        assertTrue(diarizationCompleted.contains("requestSpeakerEnrichment"))
        assertFalse(diarizationCompleted.contains("requestDocumentLoad"))
        assertTrue(alignmentCompleted.contains("requestSpeakerEnrichment"))
        assertFalse(alignmentCompleted.contains("requestDocumentLoad"))
    }

    @Test
    fun speakerFailureAndCancellationDoNotClearCompletedTranscript() {
        val source = productionSource()
        val diarizationTerminal =
            source.substringAfter("is DiarizationRunState.Failed -> {")
                .substringBefore("DiarizationRunState.Idle,")
        val alignmentTerminal =
            source.substringAfter("is SpeakerAlignmentRunState.Failed -> {")
                .substringBefore("SpeakerAlignmentRunState.Idle,")

        assertFalse(diarizationTerminal.contains("mutableDocument.value = null"))
        assertFalse(alignmentTerminal.contains("mutableDocument.value = null"))
        assertTrue(diarizationTerminal.contains("转写已完成"))
    }

    private fun productionSource(): String {
        val relative =
            "src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionViewModel.kt"
        val candidates =
            listOf(
                File(relative),
                File("app/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("TranscriptionViewModel.kt not found from ${File(".").absolutePath}")
    }
}
