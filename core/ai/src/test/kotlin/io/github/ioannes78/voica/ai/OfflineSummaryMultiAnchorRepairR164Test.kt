package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSummaryMultiAnchorRepairR164Test {
    @Test
    fun explicitCoverageFailureReportsEveryMissingAnchor() {
        val allowedSourceRefs =
            offlineSummaryGenerationSourceRefs(
                values = listOf("U00001", "U00002", "U00003", "U00004"),
                requiredCoverageGroups =
                    listOf(
                        setOf("U00001"),
                        setOf("U00002"),
                        setOf("U00003"),
                        setOf("U00004"),
                    ),
            )
        val raw =
            """{"schemaVersion":1,"synopsis":"保留前两个锚点","items":[{"id":"i1","type":"FACT","text":"事实一","sourceRefs":["U00001"],"evidenceRefs":[],"epistemicStatus":"AI_SYNTHESIS"},{"id":"i2","type":"FACT","text":"事实二","sourceRefs":["U00002"],"evidenceRefs":[],"epistemicStatus":"AI_SYNTHESIS"}]}"""

        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = allowedSourceRefs,
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE, failure.code)
        assertTrue(failure.message.orEmpty().contains("covered 2"))
        assertTrue(failure.message.orEmpty().contains("missing nodes 3,4"))
    }
}
