package io.github.ioannes78.voica.ui.transcript

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16XTranscriptLayoutStabilityContractTest {
    @Test
    fun `playback cue changes do not rebuild transcript text runs`() {
        val source = transcriptionCardsSource()
        val segmentCard = source.substringAfter("fun TranscriptSegmentCard(").substringBefore("internal fun productTranscriptionFailureMessage")

        assertTrue(segmentCard.contains("text = displayText"))
        assertFalse(segmentCard.contains("buildAnnotatedString"))
        assertFalse(segmentCard.contains("SpanStyle"))
        assertFalse(segmentCard.contains("fontWeight"))
        assertFalse(segmentCard.contains("fontSize"))
        assertFalse(segmentCard.contains("letterSpacing"))
        assertFalse(segmentCard.contains("baselineShift"))
    }

    @Test
    fun `active cue only drives paint layer and not pointer input identity`() {
        val source = transcriptionCardsSource()
        val segmentCard = source.substringAfter("fun TranscriptSegmentCard(").substringBefore("internal fun productTranscriptionFailureMessage")

        assertTrue(segmentCard.contains("currentWordHighlightRects("))
        assertTrue(segmentCard.contains("drawRoundRect("))
        assertTrue(segmentCard.contains("transcriptCurrentWordBg"))
        assertTrue(segmentCard.contains("pointerInput(segment.stableId, syncEnabled, onAddAnnotation)"))
        assertFalse(segmentCard.contains("pointerInput(segment.stableId, activeCueId"))
    }

    @Test
    fun `word highlight derives geometry from immutable text layout`() {
        val source = transcriptionCardsSource()
        val helper = source.substringAfter("private fun currentWordHighlightRects(").substringBefore("internal fun productTranscriptionFailureMessage")

        assertTrue(helper.contains("layout.getBoundingBox(offset)"))
        assertTrue(helper.contains("layout.getLineForOffset(offset)"))
        assertTrue(helper.contains("rectsByLine"))
        assertTrue(helper.contains("return rectsByLine.values.toList()"))
    }

    @Test
    fun `paragraph highlight remains weaker than current word highlight`() {
        val source = transcriptionCardsSource()
        val segmentCard = source.substringAfter("fun TranscriptSegmentCard(").substringBefore("internal fun productTranscriptionFailureMessage")

        assertTrue(segmentCard.contains("transcriptActiveParagraphBg.copy(alpha = 0.26f)"))
        assertTrue(segmentCard.contains("color = transcriptColors.transcriptCurrentWordBg"))
    }

    private fun transcriptionCardsSource(): String =
        appRoot()
            .resolve("src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt")
            .readText()

    private fun appRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        return if (userDir.name == "app") userDir else userDir.resolve("app")
    }
}
