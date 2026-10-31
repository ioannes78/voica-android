package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OfflineSummaryLongVisibleRefsTest {
    @Test
    fun reduceAndFinalAllowOnlyRefsVisibleInChildPayloads() {
        val node =
            OfflineSummaryValidatedIntermediateNode(
                nodeId = "summary:map:00001",
                summary =
                    OfflineSummaryIntermediate(
                        schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                        synopsis = "仅保留首尾两项重要信息。",
                        items =
                            listOf(
                                item(
                                    id = "first",
                                    text = "首段重要信息",
                                    sourceRef = "U00001",
                                    evidenceRef = "S00001",
                                ),
                                item(
                                    id = "last",
                                    text = "尾段重要信息",
                                    sourceRef = "U00008",
                                    evidenceRef = "S00008",
                                ),
                            ),
                    ),
                sourceRefs = (1..8).mapTo(LinkedHashSet()) { "U" + it.toString().padStart(5, '0') },
                evidenceRefs = (1..8).mapTo(LinkedHashSet()) { "S" + it.toString().padStart(5, '0') },
            )

        val reduce = OfflineSummaryReducePromptRenderer().render(listOf(node))
        val final = OfflineSummaryFinalPromptRenderer().render(listOf(node))

        val expectedSourceRefs = linkedSetOf("U00001", "U00008")
        val expectedEvidenceRefs = linkedSetOf("S00001", "S00008")
        assertEquals(expectedSourceRefs, reduce.allowedSourceRefs)
        assertEquals(expectedEvidenceRefs, reduce.allowedEvidenceRefs)
        assertEquals(expectedSourceRefs, final.allowedSourceRefs)
        assertEquals(expectedEvidenceRefs, final.allowedEvidenceRefs)
        assertFalse(reduce.allowedSourceRefs.contains("U00004"))
        assertFalse(reduce.allowedEvidenceRefs.contains("S00004"))
        assertFalse(reduce.text.contains("U00004"))
        assertFalse(final.text.contains("S00004"))
    }

    private fun item(
        id: String,
        text: String,
        sourceRef: String,
        evidenceRef: String,
    ): OfflineSummaryIntermediateItem =
        OfflineSummaryIntermediateItem(
            id = id,
            type = OfflineSummaryIntermediateItemType.KEY_POINT,
            text = text,
            sourceRefs = listOf(sourceRef),
            evidenceRefs = listOf(evidenceRef),
            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
        )
}
