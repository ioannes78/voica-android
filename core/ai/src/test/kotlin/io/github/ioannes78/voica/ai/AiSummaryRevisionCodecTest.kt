package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSummaryRevisionCodecTest {
    @Test
    fun editedAndAddedItemsKeepEvidenceSemanticsSeparate() {
        val original =
            AiSummaryResult(
                schemaVersion = 1,
                contentType = AiContentType.MEETING,
                classificationConfidence = 0.9,
                title = "会议",
                overview = "概览",
                sections =
                    listOf(
                        AiSummarySection(
                            id = "section-1",
                            type = AiSummarySectionType.DECISION,
                            label = "决策",
                            items =
                                listOf(
                                    AiSummaryItem(
                                        id = "item-1",
                                        text = "原始决定",
                                        evidenceRefs = listOf("S00001"),
                                        epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                                    ),
                                ),
                        ),
                    ),
            )

        var revision = AiSummaryRevisionCodec.fromOriginal(original)
        revision = AiSummaryRevisionEditor.editItem(revision, "item-1", "人工修订决定")
        revision =
            AiSummaryRevisionEditor.addItem(
                revision,
                sectionId = "section-1",
                text = "人工新增事项",
                idFactory = { "user-item" },
            )

        val decoded = AiSummaryRevisionCodec.decode(AiSummaryRevisionCodec.encode(revision))
        val edited = decoded.sections.single().items[0]
        val added = decoded.sections.single().items[1]

        assertEquals(AiSummaryRevisionProvenance.USER_EDITED, edited.provenance)
        assertEquals(listOf("S00001"), edited.sourceEvidenceRefs)
        assertEquals("item-1", edited.sourceItemId)
        assertEquals(AiSummaryRevisionProvenance.USER_ADDED, added.provenance)
        assertTrue(added.sourceEvidenceRefs.isEmpty())
        assertEquals(null, added.sourceItemId)
    }

    @Test
    fun sectionOrderCanBeChangedWithoutTouchingOriginalResult() {
        val source =
            AiSummaryRevisionDocument(
                title = "t",
                overview = "o",
                sections =
                    listOf(
                        AiSummaryRevisionSection("a", AiSummarySectionType.SUMMARY, "A", emptyList()),
                        AiSummaryRevisionSection("b", AiSummarySectionType.RISK, "B", emptyList()),
                    ),
            )

        val moved = AiSummaryRevisionEditor.moveSection(source, "b", -1)

        assertEquals(listOf("b", "a"), moved.sections.map { it.id })
        assertEquals(listOf("a", "b"), source.sections.map { it.id })
    }
}
