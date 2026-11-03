package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryLongRepairPromptR15Test {
    @Test
    fun structuredValidationFailurePreservesMissingNodeDetail() {
        val failure =
            offlineSummaryStructuredValidationFailure(
                code = "INSUFFICIENT_SOURCE_COVERAGE",
                message = "intermediate sourceRefs must represent every input node;\nmissing node 2",
            )

        assertTrue(failure.startsWith("INSUFFICIENT_SOURCE_COVERAGE:"))
        assertTrue(failure.contains("missing node 2"))
        assertFalse(failure.contains("\n"))
    }

    @Test
    fun coverageRepairPromptCarriesPreciseMissingNodeDirective() {
        val original = "REDUCE_INPUT_WITH_FROZEN_NODE_GROUPING"
        val failure =
            offlineSummaryStructuredValidationFailure(
                code = "INSUFFICIENT_SOURCE_COVERAGE",
                message = "intermediate sourceRefs must represent every input node; missing node 3",
            )

        val repaired = offlineSummaryLongRepairPrompt(original, failure)

        assertTrue(repaired.startsWith(original))
        assertTrue(repaired.contains("missing node 3"))
        assertTrue(repaired.contains("只从该 NODE 输入已有 sourceRefs 中复制至少一个合法引用"))
        assertTrue(repaired.contains("不得为了通过覆盖校验给无关条目硬绑来源"))
    }

    @Test
    fun coverageRepairPromptPreservesEveryMissingAnchorFromOneValidationFailure() {
        val original = "REDUCE_INPUT_WITH_FOUR_HARD_ANCHORS"
        val failure =
            offlineSummaryStructuredValidationFailure(
                code = "INSUFFICIENT_SOURCE_COVERAGE",
                message = "intermediate sourceRefs must represent at least 4/4 input nodes; covered 2; missing nodes 3,4",
            )

        val repaired = offlineSummaryLongRepairPrompt(original, failure)

        assertTrue(repaired.startsWith(original))
        assertTrue(repaired.contains("covered 2"))
        assertTrue(repaired.contains("missing nodes 3,4"))
        assertTrue(repaired.contains("覆盖修复要求"))
    }

    @Test
    fun coverageRepairReplaysOnlyMissingNodePayloadsAndAllowsSemanticReplacement() {
        val original =
            """
            闻知 WENZHI 长文本 REDUCE
            <INTERMEDIATE_DATA>
            [NODE=1]I1|FACT|TRANSCRIPT_STATED|第一个语义|U00001|S00001
            [NODE=2]I2|FACT|TRANSCRIPT_STATED|第二个语义|U00002|S00002
            [NODE=3]I3|FACT|TRANSCRIPT_STATED|第三个必须恢复的语义|U00003|S00003
            [NODE=4]I4|RISK|TRANSCRIPT_STATED|第四个必须恢复的语义|U00004|S00004
            </INTERMEDIATE_DATA>
            只返回完整 JSON 对象。
            """.trimIndent()
        val failure =
            offlineSummaryStructuredValidationFailure(
                code = "INSUFFICIENT_SOURCE_COVERAGE",
                message = "intermediate sourceRefs must represent at least 4/4 input nodes; covered 2; missing nodes 3,4",
            )

        val repaired = offlineSummaryLongRepairPrompt(original, failure)
        val targets =
            repaired
                .substringAfter("<COVERAGE_REPAIR_TARGETS>")
                .substringBefore("</COVERAGE_REPAIR_TARGETS>")

        assertTrue(targets.contains("[NODE=3]"))
        assertTrue(targets.contains("第三个必须恢复的语义"))
        assertTrue(targets.contains("U00003"))
        assertTrue(targets.contains("[NODE=4]"))
        assertTrue(targets.contains("第四个必须恢复的语义"))
        assertTrue(targets.contains("U00004"))
        assertFalse(targets.contains("[NODE=1]"))
        assertFalse(targets.contains("[NODE=2]"))
        assertTrue(repaired.contains("必须新增 item"))
        assertTrue(repaired.contains("替换一个较低优先级且非目标条目"))
        assertTrue(repaired.contains("禁止只给无关 item 追加 sourceRefs"))
    }

    @Test
    fun nonCoverageRepairDoesNotInjectCoverageSpecificInstruction() {
        val repaired =
            offlineSummaryLongRepairPrompt(
                originalPrompt = "MAP_INPUT",
                validationError = "INVALID_SYNOPSIS: synopsis must contain natural-language content",
            )

        assertTrue(repaired.contains("INVALID_SYNOPSIS"))
        assertFalse(repaired.contains("覆盖修复要求"))
        assertFalse(repaired.contains("COVERAGE_REPAIR_TARGETS"))
    }
}
