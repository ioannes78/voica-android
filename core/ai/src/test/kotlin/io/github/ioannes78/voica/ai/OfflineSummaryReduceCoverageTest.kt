package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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


    @Test
    fun reduceCoverageUsesActualNoncontiguousSemanticNodeIdsInRepairDiagnostics() {
        // Each child intermediate has at most six items, but REDUCE sees eleven semantic NODEs.
        val nodes =
            (1..11).chunked(6).mapIndexed { chunkIndex, indexes ->
                val items =
                    indexes.map { index ->
                        OfflineSummaryIntermediateItem(
                            id = "i$index",
                            type = OfflineSummaryIntermediateItemType.FACT,
                            text = "第$index 个独立的重要增长事实为 ${index * 10}%。",
                            sourceRefs = listOf("U" + index.toString().padStart(5, '0')),
                            evidenceRefs = emptyList(),
                            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                        )
                    }
                OfflineSummaryValidatedIntermediateNode(
                    nodeId = "child$chunkIndex",
                    summary =
                        OfflineSummaryIntermediate(
                            schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                            synopsis = "已经验证的中间事实概要",
                            items = items,
                        ),
                    sourceRefs = items.flatMapTo(LinkedHashSet()) { it.sourceRefs },
                    evidenceRefs = emptySet(),
                )
            }

        val rendered = OfflineSummaryReducePromptRenderer().render(nodes)
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs
        val groups = generationRefs.requiredCoverageGroups ?: error("Missing coverage groups")
        val semanticNodeIds = generationRefs.requiredCoverageNodeIndexes ?: error("Missing node identity mapping")

        assertEquals(11, rendered.inputNodeCount)
        assertEquals(4, groups.size)
        assertEquals(4, semanticNodeIds.size)
        assertTrue(semanticNodeIds.any { it > 4 })
        semanticNodeIds.forEach { index ->
            assertTrue(rendered.text.contains("[NODE=$index][HARD_ANCHOR]"))
        }

        val firstRef = groups[0].first()
        val secondRef = groups[1].first()
        val raw =
            """{"schemaVersion":1,"synopsis":"这里只保留了两个硬锚点。","items":[{"id":"i1","type":"FACT","text":"第一个已覆盖的事实。","sourceRefs":["$firstRef"],"evidenceRefs":[],"epistemicStatus":"TRANSCRIPT_STATED"},{"id":"i2","type":"FACT","text":"第二个已覆盖的事实。","sourceRefs":["$secondRef"],"evidenceRefs":[],"epistemicStatus":"TRANSCRIPT_STATED"}]}"""
        val error =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = rendered.allowedSourceRefs,
                    allowedEvidenceRefs = rendered.allowedEvidenceRefs,
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, error.code)
        assertTrue(error.message.orEmpty().contains("covered 2; missing nodes ${semanticNodeIds.drop(2).joinToString(",")}"))
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
