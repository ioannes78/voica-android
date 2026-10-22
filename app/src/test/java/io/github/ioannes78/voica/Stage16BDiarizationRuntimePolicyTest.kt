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
        assertEquals(0.60F, LocalDiarizationSettings().clusteringThreshold)
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
                LocalDiarizationSettings(clusteringThreshold = 0.68F),
            )

        assertEquals(0.68F, config.clusteringThreshold)
        assertNull(config.expectedSpeakerCount)
    }

    @Test
    fun explicitTwoSpeakerPresetIsIndependentFromAutoClusteringThreshold() {
        val config =
            SpeakerCountChoice.TWO.toStage16BDiarizationConfig(
                LocalDiarizationSettings(clusteringThreshold = 0.20F),
            )

        assertEquals(2, config.expectedSpeakerCount)
        assertEquals(2, config.minimumGlobalSpeakerCount)
        assertEquals(2, config.maximumGlobalSpeakerCount)
        assertNull(config.clusteringThreshold)
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
