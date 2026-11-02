package io.github.ioannes78.voica.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineSummaryIntermediateRefCanonicalizationR163Test {
    @Test
    fun duplicateSourceAndEvidenceRefsAreCanonicalizedWithoutRepair() {
        val decoded =
            OfflineSummaryIntermediateCodec.decode(
                raw =
                    """{"schemaVersion":1,"synopsis":"测试摘要内容","items":[{"id":"i1","type":"FACT","text":"关键事实","sourceRefs":["U00001","U00001","U00002"],"evidenceRefs":["S00001","S00001"],"epistemicStatus":"TRANSCRIPT_STATED"}]}""",
                allowedSourceRefs = setOf("U00001", "U00002"),
                allowedEvidenceRefs = setOf("S00001"),
            )

        assertEquals(listOf("U00001", "U00002"), decoded.items.single().sourceRefs)
        assertEquals(listOf("S00001"), decoded.items.single().evidenceRefs)
    }

    @Test
    fun emptySourceRefsRemainRejected() {
        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw =
                        """{"schemaVersion":1,"synopsis":"测试摘要内容","items":[{"id":"i1","type":"FACT","text":"关键事实","sourceRefs":[],"evidenceRefs":[],"epistemicStatus":"AI_SYNTHESIS"}]}""",
                    allowedSourceRefs = setOf("U00001"),
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INVALID_VALUE, failure.code)
    }

    @Test
    fun moreThanThreeDistinctSourceRefsRemainRejected() {
        val failure =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw =
                        """{"schemaVersion":1,"synopsis":"测试摘要内容","items":[{"id":"i1","type":"FACT","text":"关键事实","sourceRefs":["U00001","U00002","U00003","U00004"],"evidenceRefs":[],"epistemicStatus":"AI_SYNTHESIS"}]}""",
                    allowedSourceRefs = setOf("U00001", "U00002", "U00003", "U00004"),
                    allowedEvidenceRefs = emptySet(),
                )
            }.exceptionOrNull() as OfflineSummaryIntermediateException

        assertEquals(OfflineSummaryIntermediateErrorCode.INVALID_VALUE, failure.code)
    }
}
