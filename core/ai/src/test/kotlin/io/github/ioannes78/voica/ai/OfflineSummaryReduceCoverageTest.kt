package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OfflineSummaryReduceCoverageTest {
    @Test
    fun reduceCoverageAcceptsEveryChildNodeWithoutRequiringFlatThirdBuckets() {
        val allowed =
            offlineSummaryGenerationSourceRefs(
                values = (1..6).map { "U" + it.toString().padStart(5, '0') },
                requiredCoverageGroups =
                    listOf(
                        setOf("U00001", "U00002", "U00003"),
                        setOf("U00004", "U00005", "U00006"),
                    ),
            )
        val raw =
            """{"schemaVersion":1,"synopsis":"两个子节点的重要信息均已保留。","items":[{"id":"i1","type":"KEY_POINT","text":"第一个节点的重要信息。","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"},{"id":"i2","type":"KEY_POINT","text":"第二个节点的重要信息。","sourceRefs":["U00004"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw = raw,
                allowedSourceRefs = allowed,
                allowedEvidenceRefs = emptySet(),
            )

        assertEquals(listOf("U00001", "U00004"), decoded.items.flatMap { it.sourceRefs })
    }

    @Test
    fun reduceCoverageRejectsWhenAnyChildNodeIsMissing() {
        val allowed =
            offlineSummaryGenerationSourceRefs(
                values = (1..6).map { "U" + it.toString().padStart(5, '0') },
                requiredCoverageGroups =
                    listOf(
                        setOf("U00001", "U00002", "U00003"),
                        setOf("U00004", "U00005", "U00006"),
                    ),
            )
        val raw =
            """{"schemaVersion":1,"synopsis":"只保留了第一个子节点。","items":[{"id":"i1","type":"KEY_POINT","text":"第一个节点的重要信息。","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}]}"""

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = allowed,
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, failure.code)
    }

    @Test
    fun reduceRendererCarriesOneCoverageGroupPerVisibleChildNode() {
        val first = validatedNode("N1", "U00001")
        val second = validatedNode("N2", "U00004")

        val rendered = OfflineSummaryReducePromptRenderer().render(listOf(first, second))
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs

        assertNotNull(generationRefs.requiredCoverageGroups)
        assertEquals(2, generationRefs.requiredCoverageGroups?.size)
        assertEquals(setOf("U00001"), generationRefs.requiredCoverageGroups?.get(0))
        assertEquals(setOf("U00004"), generationRefs.requiredCoverageGroups?.get(1))
    }

    private fun validatedNode(
        nodeId: String,
        sourceRef: String,
    ): OfflineSummaryValidatedIntermediateNode {
        val summary =
            OfflineSummaryIntermediate(
                schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                synopsis = "已验证摘要",
                items =
                    listOf(
                        OfflineSummaryIntermediateItem(
                            id = "i1",
                            type = OfflineSummaryIntermediateItemType.KEY_POINT,
                            text = "已验证的重要信息",
                            sourceRefs = listOf(sourceRef),
                            evidenceRefs = emptyList(),
                            epistemicStatus = AiEpistemicStatus.UNCONFIRMED,
                        ),
                    ),
            )
        return OfflineSummaryValidatedIntermediateNode(
            nodeId = nodeId,
            summary = summary,
            sourceRefs = setOf(sourceRef),
            evidenceRefs = emptySet(),
        )
    }
}
