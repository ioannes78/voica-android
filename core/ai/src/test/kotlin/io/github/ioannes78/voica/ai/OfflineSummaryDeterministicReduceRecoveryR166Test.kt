package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryDeterministicReduceRecoveryR166Test {
    @Test
    fun twoOfFourCoverageRecoversExactUpstreamAnchorSemanticsAndReferences() {
        val nodes = nodesWithElevenItems()
        val rendered = OfflineSummaryReducePromptRenderer().render(nodes)
        val anchors = offlineSummaryRetentionAnchorIndexes(nodes.flatMap { it.summary.items })
        assertEquals(4, anchors.size)

        val upstream = nodes.flatMap { it.summary.items }
        val unanchored = upstream.filterIndexed { index, _ -> index !in anchors }
        val insufficient =
            output(
                listOf(upstream[anchors[0]], upstream[anchors[1]]) +
                    unanchored.take(4),
            )
        val originalFailure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    insufficient,
                    rendered.allowedSourceRefs,
                    rendered.allowedEvidenceRefs,
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException
        assertEquals(
            OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE,
            originalFailure.code,
        )

        val recovered = requireNotNull(
            OfflineSummaryDeterministicReduceRecovery.recover(
                raw = insufficient,
                inputNodes = nodes,
                allowedSourceRefs = rendered.allowedSourceRefs,
                allowedEvidenceRefs = rendered.allowedEvidenceRefs,
            ),
        )
        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                recovered,
                rendered.allowedSourceRefs,
                rendered.allowedEvidenceRefs,
            )
        assertEquals(6, decoded.items.size)
        anchors.forEach { index ->
            val upstreamAnchor = upstream[index]
            assertTrue(decoded.items.any {
                it.text == upstreamAnchor.text &&
                    it.sourceRefs == upstreamAnchor.sourceRefs &&
                    it.evidenceRefs == upstreamAnchor.evidenceRefs
            })
        }
        assertEquals(decoded.items.size, decoded.items.map { it.id }.toSet().size)
        assertEquals(
            recovered,
            OfflineSummaryDeterministicReduceRecovery.recover(
                insufficient,
                nodes,
                rendered.allowedSourceRefs,
                rendered.allowedEvidenceRefs,
            ),
        )
        assertTrue(decoded.items.any { it.text.contains("研发投入") })
    }

    @Test
    fun structurallyInvalidOrUnknownRefOutputsCannotBeRepairedByCopyingAnchors() {
        val nodes = nodesWithElevenItems()
        val prompt = OfflineSummaryReducePromptRenderer().render(nodes)
        val invalid = output(listOf(nodes.first().summary.items.first())).replace("U00001", "U99999")
        assertNull(
            OfflineSummaryDeterministicReduceRecovery.recover(
                invalid,
                nodes,
                prompt.allowedSourceRefs,
                prompt.allowedEvidenceRefs,
            ),
        )
        assertNull(
            OfflineSummaryDeterministicReduceRecovery.recover(
                "{broken",
                nodes,
                prompt.allowedSourceRefs,
                prompt.allowedEvidenceRefs,
            ),
        )
    }

    @Test
    fun alreadyValidCoverageOrNonGenerationSetDoesNotTriggerRecovery() {
        val nodes = nodesWithElevenItems()
        val prompt = OfflineSummaryReducePromptRenderer().render(nodes)
        val upstream = nodes.flatMap { it.summary.items }
        val allAnchors = offlineSummaryRetentionAnchorIndexes(upstream).map(upstream::get)
        val valid = output(allAnchors)
        OfflineSummaryIntermediateCodec.decode(valid, prompt.allowedSourceRefs, prompt.allowedEvidenceRefs)
        assertNull(
            OfflineSummaryDeterministicReduceRecovery.recover(
                valid,
                nodes,
                prompt.allowedSourceRefs,
                prompt.allowedEvidenceRefs,
            ),
        )
        assertNull(
            OfflineSummaryDeterministicReduceRecovery.recover(
                valid,
                nodes,
                LinkedHashSet(prompt.allowedSourceRefs),
                prompt.allowedEvidenceRefs,
            ),
        )
        assertFalse(valid.isBlank())
    }

    private fun nodesWithElevenItems(): List<OfflineSummaryValidatedIntermediateNode> {
        val items =
            (1..11).map { i ->
                OfflineSummaryIntermediateItem(
                    id = "u$i",
                    type = if (i == 6) OfflineSummaryIntermediateItemType.FACT else OfflineSummaryIntermediateItemType.KEY_POINT,
                    text =
                        if (i == 6) "研发投入在十年间从百分之九上升到百分之十三"
                        else "第$i 个有来源的独立事实和应用案例",
                    sourceRefs = listOf("U" + i.toString().padStart(5, '0')),
                    evidenceRefs = listOf("S" + i.toString().padStart(5, '0')),
                    epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
                )
            }
        return listOf(
            node("N1", items.take(6)),
            node("N2", items.drop(6)),
        )
    }

    private fun node(
        id: String,
        items: List<OfflineSummaryIntermediateItem>,
    ): OfflineSummaryValidatedIntermediateNode =
        OfflineSummaryValidatedIntermediateNode(
            nodeId = id,
            summary = OfflineSummaryIntermediate(
                schemaVersion = 1,
                synopsis = "真实来源支持的一组有效事实",
                items = items,
            ),
            sourceRefs = items.flatMapTo(LinkedHashSet()) { it.sourceRefs },
            evidenceRefs = items.flatMapTo(LinkedHashSet()) { it.evidenceRefs },
        )

    private fun output(items: List<OfflineSummaryIntermediateItem>): String =
        OfflineSummaryIntermediateCodec.encode(
            OfflineSummaryIntermediate(
                schemaVersion = 1,
                synopsis = "包含实际资料支持的事实与应用分析",
                items = items.mapIndexed { index, item -> item.copy(id = "a${index + 1}") },
            ),
        )
}
