package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun plannerPrefersLegacyGroupingBeforeCompactFallbackForCheckpointCompatibility() {
        val estimator =
            object : OfflineSummaryTokenEstimator {
                override val id = "LEGACY_COMPAT_TEST"

                override fun estimateTokens(text: String): Int {
                    val nodeCount = Regex("\\[NODE=").findAll(text).count()
                    val legacyShape = text.contains("[id=")
                    return if (legacyShape) {
                        when (nodeCount) {
                            0 -> 0
                            1 -> 700
                            2 -> 1_700
                            else -> 2_600
                        }
                    } else {
                        400 + nodeCount * 100
                    }
                }
            }
        val nodes = (1..4).map(::validatedNode)

        val groups = OfflineSummaryReductionPlanner(estimator = estimator).group(nodes)

        assertEquals(listOf(2, 2), groups.map { it.nodes.size })
        assertEquals(nodes.map { it.nodeId }, groups.flatMap { it.nodes }.map { it.nodeId })
        assertTrue(groups.all { it.estimatedPromptTokens < 1_000 })
    }

    @Test
    fun compactReduceEncodingKeepsWorstCaseAdjacentPairInsideHardBudget() {
        val nodes = listOf(worstCaseValidatedNode(1), worstCaseValidatedNode(2))
        val rendered = OfflineSummaryReducePromptRenderer().render(nodes)
        val estimated = ConservativeOfflineSummaryTokenEstimator.estimateTokens(rendered.text)
        val groups = OfflineSummaryReductionPlanner().group(nodes)

        assertTrue(estimated <= OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().nodes.size)
        assertFalse(rendered.text.contains("\"schemaVersion\""))
        assertTrue(rendered.text.contains("schemaVersion 必须为 1"))
        assertTrue(rendered.text.contains("error=INVALID_VALUE"))
        assertTrue(rendered.text.contains("[NODE=1]"))
        assertTrue(rendered.text.contains("I6|FOLLOW_UP|UNCONFIRMED|"))
        assertTrue(rendered.text.contains("U00001:F999"))
        assertTrue(rendered.text.contains("S00001"))
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

    private fun worstCaseValidatedNode(index: Int): OfflineSummaryValidatedIntermediateNode {
        val sourceRefs =
            listOf(
                "U00001:F999",
                "U00002:F999",
                "U00003:F999",
            )
        val evidenceRefs = listOf("S00001", "S00002", "S00003")
        val items =
            (1..OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS).map { itemIndex ->
                OfflineSummaryIntermediateItem(
                    id = "n${index}i$itemIndex",
                    type = OfflineSummaryIntermediateItemType.FOLLOW_UP,
                    text = "重".repeat(OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEM_TEXT_CHARS),
                    sourceRefs = sourceRefs,
                    evidenceRefs = evidenceRefs,
                    epistemicStatus = AiEpistemicStatus.UNCONFIRMED,
                )
            }
        return OfflineSummaryValidatedIntermediateNode(
            nodeId = "worst-node-$index",
            summary =
                OfflineSummaryIntermediate(
                    schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                    synopsis = "概".repeat(OfflineSummaryLongContract.MAX_INTERMEDIATE_SYNOPSIS_CHARS),
                    items = items,
                ),
            sourceRefs = sourceRefs.toSet(),
            evidenceRefs = evidenceRefs.toSet(),
        )
    }
}
