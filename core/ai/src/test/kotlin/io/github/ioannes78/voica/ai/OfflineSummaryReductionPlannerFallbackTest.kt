package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryReductionPlannerFallbackTest {
    @Test
    fun hardBudgetFallbackCombinesAdjacentNodesWhenSoftTargetWouldStall() {
        val estimator =
            object : OfflineSummaryTokenEstimator {
                override val id = "NODE_COUNT_TEST"

                override fun estimateTokens(text: String): Int {
                    val nodeCount = Regex("\\[NODE=").findAll(text).count()
                    return when (nodeCount) {
                        0 -> 0
                        1 -> 1_500
                        2 -> 2_300
                        else -> 2_700
                    }
                }
            }
        val nodes = (1..5).map(::validatedNode)

        val groups = OfflineSummaryReductionPlanner(estimator = estimator).group(nodes)

        assertEquals(listOf(2, 2, 1), groups.map { it.nodes.size })
        assertEquals(nodes.map { it.nodeId }, groups.flatMap { it.nodes }.map { it.nodeId })
        assertTrue(groups.size < nodes.size)
        assertTrue(
            groups.all {
                it.estimatedPromptTokens <=
                    OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS
            },
        )
    }

    private fun validatedNode(index: Int): OfflineSummaryValidatedIntermediateNode {
        val sourceRef = "U" + index.toString().padStart(5, '0')
        val evidenceRef = "S" + index.toString().padStart(5, '0')
        return OfflineSummaryValidatedIntermediateNode(
            nodeId = "N$index",
            summary =
                OfflineSummaryIntermediate(
                    schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                    synopsis = "第 $index 个已验证摘要",
                    items =
                        listOf(
                            OfflineSummaryIntermediateItem(
                                id = "i$index",
                                type = OfflineSummaryIntermediateItemType.FACT,
                                text = "第 $index 个已验证事实",
                                sourceRefs = listOf(sourceRef),
                                evidenceRefs = listOf(evidenceRef),
                                epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                            ),
                        ),
                ),
            sourceRefs = setOf(sourceRef),
            evidenceRefs = setOf(evidenceRef),
        )
    }
}
