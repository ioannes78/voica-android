package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummarySemanticRetentionR16Test {
    @Test
    fun reduceRendererKeepsSmallSemanticSetAsHardCoverageGroups() {
        val first =
            validatedNode(
                "N1",
                listOf(
                    item("i1", "开发者七年增长六倍", "U00001", "S00001"),
                    item("i2", "企业更关注增收而非降本", "U00002", "S00002"),
                ),
            )
        val second =
            validatedNode(
                "N2",
                listOf(
                    item("i1", "新药研发时间可缩短三到八成", "U00003", "S00003"),
                ),
            )

        val rendered = OfflineSummaryReducePromptRenderer().render(listOf(first, second))
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs

        assertEquals(3, rendered.inputNodeCount)
        assertEquals(3, generationRefs.requiredCoverageGroups?.size)
        assertEquals(setOf("U00001"), generationRefs.requiredCoverageGroups?.get(0))
        assertEquals(setOf("U00002"), generationRefs.requiredCoverageGroups?.get(1))
        assertEquals(setOf("U00003"), generationRefs.requiredCoverageGroups?.get(2))
        assertTrue(rendered.text.contains("[NODE=1][HARD_ANCHOR]"))
        assertTrue(rendered.text.contains("[NODE=2][HARD_ANCHOR]"))
        assertTrue(rendered.text.contains("[NODE=3][HARD_ANCHOR]"))
        assertTrue(rendered.text.contains("第一次输出没有该语义就新增 item"))
        assertTrue(rendered.text.contains("items 已满则替换较低优先级非锚点"))
        assertTrue(rendered.text.contains("禁止只给无关 item 挂 sourceRefs"))
    }

    @Test
    fun reduceValidationRejectsDroppingSecondSemanticItemFromSmallChildSet() {
        val first =
            validatedNode(
                "N1",
                listOf(
                    item("i1", "开发者七年增长六倍", "U00001", "S00001"),
                    item("i2", "企业更关注增收而非降本", "U00002", "S00002"),
                ),
            )
        val second =
            validatedNode(
                "N2",
                listOf(
                    item("i1", "能源消耗是主要挑战", "U00003", "S00003"),
                ),
            )
        val rendered = OfflineSummaryReducePromptRenderer().render(listOf(first, second))
        val raw =
            """{"schemaVersion":1,"synopsis":"只保留了两个要点。","items":[{"id":"a","type":"FACT","text":"开发者增长明显。","sourceRefs":["U00001"],"evidenceRefs":["S00001"],"epistemicStatus":"TRANSCRIPT_STATED"},{"id":"b","type":"RISK","text":"能源消耗是挑战。","sourceRefs":["U00003"],"evidenceRefs":["S00003"],"epistemicStatus":"TRANSCRIPT_STATED"}]}"""

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = rendered.allowedSourceRefs,
                    allowedEvidenceRefs = rendered.allowedEvidenceRefs,
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, failure.code)
        assertTrue(failure.message.orEmpty().contains("missing nodes 2"))
    }

    @Test
    fun reduceValidationAllowsNonAnchorOmissionAcrossSevenNodeGroup() {
        val first =
            validatedNode(
                "N1",
                (1..6).map { index ->
                    item(
                        id = "i$index",
                        text = "第${index}个独立语义要点",
                        sourceRef = "U" + index.toString().padStart(5, '0'),
                        evidenceRef = "S" + index.toString().padStart(5, '0'),
                    )
                },
            )
        val second =
            validatedNode(
                "N2",
                listOf(item("i7", "第七个独立语义要点", "U00007", "S00007")),
            )
        val rendered = OfflineSummaryReducePromptRenderer().render(listOf(first, second))
        val generationRefs = rendered.allowedSourceRefs as OfflineSummaryGenerationSourceRefs
        val anchorRefs = requireNotNull(generationRefs.requiredCoverageGroups).map { it.first() }
        val raw = intermediateJson(anchorRefs)

        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw = raw,
                allowedSourceRefs = rendered.allowedSourceRefs,
                allowedEvidenceRefs = rendered.allowedEvidenceRefs,
            )

        assertEquals(4, anchorRefs.size)
        assertEquals(4, decoded.items.size)
        assertEquals(4, Regex(Regex.escape("[HARD_ANCHOR]")).findAll(rendered.text).count())
        assertTrue(rendered.inputNodeCount > decoded.items.size)
    }

    @Test
    fun finalRendererKeepsEveryIntermediateItemVisibleAsSemanticNode() {
        val node =
            validatedNode(
                "N1",
                listOf(
                    item("i1", "开发者七年增长六倍", "U00001", "S00001"),
                    item("i2", "标普公司提及AI比例接近一半", "U00002", "S00002"),
                    item("i3", "全球数据中心用电约增长三倍", "U00003", "S00003"),
                ),
            )

        val rendered = OfflineSummaryFinalPromptRenderer().render(listOf(node))

        assertEquals(3, rendered.inputNodeCount)
        assertTrue(rendered.text.contains("[NODE=1]"))
        assertTrue(rendered.text.contains("[NODE=2]"))
        assertTrue(rendered.text.contains("[NODE=3]"))
        assertTrue(rendered.text.contains("开发者七年增长六倍"))
        assertTrue(rendered.text.contains("标普公司提及AI比例接近一半"))
        assertTrue(rendered.text.contains("全球数据中心用电约增长三倍"))
        assertTrue(rendered.text.contains("不要刻意极简"))
    }

    private fun intermediateJson(sourceRefs: List<String>): String {
        val items =
            sourceRefs.mapIndexed { index, ref ->
                """{"id":"a${index + 1}","type":"FACT","text":"保留锚点事实${index + 1}","sourceRefs":["$ref"],"evidenceRefs":[],"epistemicStatus":"UNCONFIRMED"}"""
            }.joinToString(",")
        return """{"schemaVersion":1,"synopsis":"保留硬覆盖锚点并继续压缩其他信息。","items":[$items]}"""
    }

    private fun item(
        id: String,
        text: String,
        sourceRef: String,
        evidenceRef: String,
    ): OfflineSummaryIntermediateItem =
        OfflineSummaryIntermediateItem(
            id = id,
            type = OfflineSummaryIntermediateItemType.FACT,
            text = text,
            sourceRefs = listOf(sourceRef),
            evidenceRefs = listOf(evidenceRef),
            epistemicStatus = AiEpistemicStatus.TRANSCRIPT_STATED,
        )

    private fun validatedNode(
        nodeId: String,
        items: List<OfflineSummaryIntermediateItem>,
    ): OfflineSummaryValidatedIntermediateNode =
        OfflineSummaryValidatedIntermediateNode(
            nodeId = nodeId,
            summary =
                OfflineSummaryIntermediate(
                    schemaVersion = OfflineSummaryLongContract.INTERMEDIATE_SCHEMA_VERSION,
                    synopsis = "包含多个必须保留的独立要点",
                    items = items,
                ),
            sourceRefs = items.flatMapTo(LinkedHashSet()) { it.sourceRefs },
            evidenceRefs = items.flatMapTo(LinkedHashSet()) { it.evidenceRefs },
        )
}
