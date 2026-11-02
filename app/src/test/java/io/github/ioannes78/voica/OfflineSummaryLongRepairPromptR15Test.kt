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
