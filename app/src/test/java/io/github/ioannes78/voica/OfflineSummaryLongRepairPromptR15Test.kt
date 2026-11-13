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
    fun coverageRepairKeepsInlineHardAnchorTargetsVisibleWithMultiAnchorFailure() {
        val original =
            """
            闻知 WENZHI 长文本 REDUCE
            若覆盖校验重试 error 指出 missing node(s)，必须恢复对应 [NODE=n][HARD_ANCHOR] 的原语义；第一次输出没有该语义就新增 item，items 已满则替换较低优先级非锚点，禁止只给无关 item 挂 sourceRefs。
            <INTERMEDIATE_DATA>
            [NODE=1][HARD_ANCHOR]I1|FACT|TRANSCRIPT_STATED|第一个语义|U00001|S00001
            [NODE=2][HARD_ANCHOR]I2|FACT|TRANSCRIPT_STATED|第二个语义|U00002|S00002
            [NODE=3][HARD_ANCHOR]I3|FACT|TRANSCRIPT_STATED|第三个必须恢复的语义|U00003|S00003
            [NODE=4][HARD_ANCHOR]I4|RISK|TRANSCRIPT_STATED|第四个必须恢复的语义|U00004|S00004
            </INTERMEDIATE_DATA>
            只返回完整 JSON 对象。
            """.trimIndent()
        val failure =
            offlineSummaryStructuredValidationFailure(
                code = "INSUFFICIENT_SOURCE_COVERAGE",
                message = "intermediate sourceRefs must represent at least 4/4 input nodes; covered 2; missing nodes 3,4",
            )

        val repaired = offlineSummaryLongRepairPrompt(original, failure)

        assertTrue(repaired.contains("missing nodes 3,4"))
        assertTrue(repaired.contains("[NODE=3][HARD_ANCHOR]"))
        assertTrue(repaired.contains("第三个必须恢复的语义"))
        assertTrue(repaired.contains("U00003"))
        assertTrue(repaired.contains("[NODE=4][HARD_ANCHOR]"))
        assertTrue(repaired.contains("第四个必须恢复的语义"))
        assertTrue(repaired.contains("U00004"))
        assertTrue(repaired.contains("第一次输出没有该语义就新增 item"))
        assertTrue(repaired.contains("替换较低优先级非锚点"))
        assertTrue(repaired.contains("禁止只给无关 item 挂 sourceRefs"))
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
    }
}
