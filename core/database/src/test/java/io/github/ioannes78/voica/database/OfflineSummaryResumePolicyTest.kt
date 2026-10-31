package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryResumePolicyTest {
    @Test
    fun exhaustedStructuredRepairIsTerminalForProductResume() {
        assertTrue(isTerminalOfflineSummaryResumeFailure(OfflineSummaryFailureCode.REPAIR_FAILED.name))
    }

    @Test
    fun generationFailureAndInterruptionRemainEligibleForNormalResumeChecks() {
        assertFalse(isTerminalOfflineSummaryResumeFailure(OfflineSummaryFailureCode.GENERATION_FAILED.name))
        assertFalse(isTerminalOfflineSummaryResumeFailure(OfflineSummaryFailureCode.INTERRUPTED.name))
        assertFalse(isTerminalOfflineSummaryResumeFailure(null))
    }
}
