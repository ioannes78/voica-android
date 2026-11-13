package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.OfflineSummaryLongContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryLongFinalizationPolicyTest {
    @Test
    fun `reduction stall finalizes when final prompt fits hard budget`() {
        assertTrue(
            shouldFinalizeOnReductionStall(
                finalEstimate = 2_200,
                currentNodeCount = 3,
                reducedGroupCount = 3,
            ),
        )
        assertTrue(
            shouldFinalizeOnReductionStall(
                finalEstimate = OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS,
                currentNodeCount = 2,
                reducedGroupCount = 2,
            ),
        )
    }

    @Test
    fun `reduction stall does not bypass hard budget or normal reduction`() {
        assertFalse(
            shouldFinalizeOnReductionStall(
                finalEstimate = OfflineSummaryLongContract.HARD_MAX_ESTIMATED_PROMPT_TOKENS + 1,
                currentNodeCount = 3,
                reducedGroupCount = 3,
            ),
        )
        assertFalse(
            shouldFinalizeOnReductionStall(
                finalEstimate = 2_200,
                currentNodeCount = 3,
                reducedGroupCount = 2,
            ),
        )
        assertFalse(
            shouldFinalizeOnReductionStall(
                finalEstimate = 2_200,
                currentNodeCount = 1,
                reducedGroupCount = 1,
            ),
        )
    }
}
