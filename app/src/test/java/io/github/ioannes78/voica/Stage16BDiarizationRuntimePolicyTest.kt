package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage16BDiarizationRuntimePolicyTest {
    @Test
    fun recommendedAutoClusteringThresholdIsThePersistedSettingsDefault() {
        assertEquals(
            RECOMMENDED_DIARIZATION_CLUSTERING_THRESHOLD,
            LocalDiarizationSettings().clusteringThreshold,
        )
        assertEquals(0.70F, LocalDiarizationSettings().clusteringThreshold)
    }

    @Test
    fun autoSpeakerCountUsesExactUserClusteringThresholdWithoutHiddenFloor() {
        val config =
            SpeakerCountChoice.AUTO.toStage16BDiarizationConfig(
                LocalDiarizationSettings(
                    clusteringThreshold = 0.50F,
                    stitchingCosineThreshold = 0.75F,
                ),
            )

        assertEquals(0.50F, config.clusteringThreshold)
        assertEquals(0.75F, config.stitchingCosineThreshold)
        assertNull(config.expectedSpeakerCount)
    }

    @Test
    fun autoSpeakerCountPreservesHigherUserClusteringThreshold() {
        val config =
            SpeakerCountChoice.AUTO.toStage16BDiarizationConfig(
                LocalDiarizationSettings(clusteringThreshold = 0.80F),
            )

        assertEquals(0.80F, config.clusteringThreshold)
        assertNull(config.expectedSpeakerCount)
    }

    @Test
    fun fixedOneThroughFourSpeakerPresetsAreIndependentFromAutoClusteringThreshold() {
        listOf(
            SpeakerCountChoice.ONE to 1,
            SpeakerCountChoice.TWO to 2,
            SpeakerCountChoice.THREE to 3,
            SpeakerCountChoice.FOUR to 4,
        ).forEach { (choice, expected) ->
            val config =
                choice.toStage16BDiarizationConfig(
                    LocalDiarizationSettings(clusteringThreshold = 0.20F),
                )

            assertEquals(expected, config.expectedSpeakerCount)
            assertEquals(expected, config.minimumGlobalSpeakerCount)
            assertEquals(expected, config.maximumGlobalSpeakerCount)
            assertNull(config.clusteringThreshold)
        }
    }

    @Test
    fun fivePlusPresetKeepsItsOwnBoundedPolicyIndependentFromAutoRecommendation() {
        val config =
            SpeakerCountChoice.FIVE_PLUS.toStage16BDiarizationConfig(
                LocalDiarizationSettings(clusteringThreshold = 0.20F),
            )

        assertEquals(FIVE_PLUS_MINIMUM_SPEAKERS, config.expectedSpeakerCount)
        assertEquals(FIVE_PLUS_MINIMUM_SPEAKERS, config.minimumGlobalSpeakerCount)
        assertEquals(FIVE_PLUS_MAXIMUM_SPEAKERS, config.maximumGlobalSpeakerCount)
        assertEquals(FIVE_PLUS_INITIAL_CLUSTERING_THRESHOLD, config.clusteringThreshold)
    }

    @Test
    fun autoNeverInjectsFixedTwoSpeakerCount() {
        val config =
            SpeakerCountChoice.AUTO.toStage16BDiarizationConfig(
                LocalDiarizationSettings(clusteringThreshold = 0.70F),
            )

        assertNull(config.expectedSpeakerCount)
        assertNull(config.minimumGlobalSpeakerCount)
        assertNull(config.maximumGlobalSpeakerCount)
    }

    @Test
    fun autoPerformanceAllowsFourThreadsOnlyWhenUserDidNotOverridePolicy() {
        val auto =
            LocalSpeechSettings(
                performanceProfile = SpeechPerformanceProfile.AUTO,
            ).resolveStage16BDiarizationPerformance(logicalProcessors = 8)
        val balanced =
            LocalSpeechSettings(
                performanceProfile = SpeechPerformanceProfile.BALANCED,
            ).resolveStage16BDiarizationPerformance(logicalProcessors = 8)
        val explicit =
            LocalSpeechSettings(
                performanceProfile = SpeechPerformanceProfile.AUTO,
                requestedThreads = 3,
            ).resolveStage16BDiarizationPerformance(logicalProcessors = 8)

        assertEquals(4, auto.effectiveThreads)
        assertEquals(2, balanced.effectiveThreads)
        assertEquals(3, explicit.effectiveThreads)
    }

    @Test
    fun autoDiarizationThreadsRespectDeviceProcessorLimit() {
        val performance =
            LocalSpeechSettings(
                performanceProfile = SpeechPerformanceProfile.AUTO,
            ).resolveStage16BDiarizationPerformance(logicalProcessors = 2)

        assertEquals(2, performance.effectiveThreads)
    }
}
