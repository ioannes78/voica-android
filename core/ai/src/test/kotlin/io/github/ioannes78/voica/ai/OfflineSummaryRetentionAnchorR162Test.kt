package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryRetentionAnchorR162Test {
    @Test
    fun reduceRendererBoundsHardCoverageToFourAnchorsWhileKeepingAllSemanticRefsVisible() {
        val rendered = OfflineSummaryReducePromptRenderer().render(nodesWithElevenSemanticItems())
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs
        val anchors = requireNotNull(generationRefs.requiredCoverageGroups)

        assertEquals(11, rendered.inputNodeCount)
        assertEquals(11, generationRefs.size)
        assertEquals(4, anchors.size)
        assertTrue(rendered.text.contains("硬覆盖锚点 NODE="))
        assertTrue(rendered.text.contains("非锚点不是可随意删除项"))
    }

    @Test
    fun fourAnchorsRemainStrictEvenThoughR161CoveragePercentageIsEighty() {
        val rendered = OfflineSummaryReducePromptRenderer().render(nodesWithElevenSemanticItems())
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs
        val anchors = requireNotNull(generationRefs.requiredCoverageGroups)
        val coveredRefs = anchors.map { it.first() }
        val passing = intermediateJson(coveredRefs)

        OfflineSummaryIntermediateCodec.decode(
            raw = passing,
            allowedSourceRefs = rendered.allowedSourceRefs,
            allowedEvidenceRefs = rendered.allowedEvidenceRefs,
        )

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = intermediateJson(coveredRefs.dropLast(1)),
                    allowedSourceRefs = rendered.allowedSourceRefs,
                    allowedEvidenceRefs = rendered.allowedEvidenceRefs,
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, failure.code)
    }

    @Test
    fun quantitativeFactInsideMiddleThirdBecomesHardRetentionAnchor() {
        val rendered = OfflineSummaryReducePromptRenderer().render(nodesWithElevenSemanticItems())
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs
        val anchors = requireNotNull(generationRefs.requiredCoverageGroups)

        assertTrue(anchors.any { "U00006" in it })
    }

    private fun nodesWithElevenSemanticItems(): List<OfflineSummaryValidatedIntermediateNode> {
        val items =
            (1..11).map { index ->
                OfflineSummaryIntermediateItem(
                    id = "i$index",
                    type = if (index == 6) OfflineSummaryIntermediateItemType.FACT else OfflineSummaryIntermediateItemType.OTHER,
                    text = if (index == 6) "企业价值从百分之三十五提高到百分之六十五" else "普通但有效的信息 $index",
                    sourceRefs = listOf("U" + index.toString().padStart(5, '0')),
                    evidenceRefs = emptyList(),
                    epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                )
            }
        return listOf(
            validatedNode("N1", items.take(6)),
            validatedNode("N2", items.drop(6)),
        )
    }

    private fun validatedNode(
        nodeId: String,
        items: List<OfflineSummaryIntermediateItem>,
    ): OfflineSummaryValidatedIntermediateNode =
        OfflineSummaryValidatedIntermediateNode(
            nodeId = nodeId,
            summary =
                OfflineSummaryIntermediate(
                    schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                    synopsis = "包含多个独立且有效的信息要点",
                    items = items,
                ),
            sourceRefs = items.flatMapTo(LinkedHashSet()) { it.sourceRefs },
            evidenceRefs = emptySet(),
        )

    private fun intermediateJson(sourceRefs: List<String>): String {
        val items =
            sourceRefs.mapIndexed { index, ref ->
                """{"id":"a${index + 1}","type":"FACT","text":"保留锚点事实${index + 1}","sourceRefs":["$ref"],"evidenceRefs":[],"epistemicStatus":"TRANSCRIPT_STATED"}"""
            }.joinToString(",")
        return """{"schemaVersion":1,"synopsis":"保留关键锚点并继续压缩其他信息。","items":[$items]}"""
    }
}
