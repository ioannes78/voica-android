package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.database.EffectiveTranscriptionRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryStalePolicyTest {
    @Test
    fun modelOriginalLineageMatchesModelOriginalEffectiveTranscript() {
        assertFalse(
            isSummaryStale(
                lineageTranscriptionId = "t1",
                lineageRevisionId = null,
                effective = EffectiveTranscriptionRef("t1", null),
            ),
        )
    }

    @Test
    fun matchingRevisionIsCurrent() {
        assertFalse(
            isSummaryStale(
                lineageTranscriptionId = "t1",
                lineageRevisionId = "r1",
                effective = EffectiveTranscriptionRef("t1", "r1"),
            ),
        )
    }

    @Test
    fun editingCurrentTranscriptMakesOlderSummaryStale() {
        assertTrue(
            isSummaryStale(
                lineageTranscriptionId = "t1",
                lineageRevisionId = null,
                effective = EffectiveTranscriptionRef("t1", "r1"),
            ),
        )
    }

    @Test
    fun adoptingDifferentTranscriptionMakesSummaryStale() {
        assertTrue(
            isSummaryStale(
                lineageTranscriptionId = "t1",
                lineageRevisionId = "r1",
                effective = EffectiveTranscriptionRef("t2", null),
            ),
        )
    }

    @Test
    fun missingEffectiveTranscriptIsStale() {
        assertTrue(
            isSummaryStale(
                lineageTranscriptionId = "t1",
                lineageRevisionId = null,
                effective = null,
            ),
        )
    }
}
