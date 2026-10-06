package io.github.ioannes78.voica

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChannelPromotionMigrationTest {
    @Test
    fun promotedStage13aCandidateOverridesAreRecognized() {
        assertTrue(
            isPromotedStage13aDebugManifestUrl(
                "https://github.com/ioannes78/voica-model-channel/releases/download/candidate-stage13a-all-r1/production.json",
            ),
        )
        assertTrue(
            isPromotedStage13aDebugManifestUrl(
                "https://github.com/ioannes78/voica-model-channel/releases/download/candidate-stage13a-streaming-asr-r1/production.json",
            ),
        )
    }

    @Test
    fun futureCandidateOverrideIsPreserved() {
        assertFalse(
            isPromotedStage13aDebugManifestUrl(
                "https://github.com/ioannes78/voica-model-channel/releases/download/candidate-stage13c-r1/production.json",
            ),
        )
    }
}
