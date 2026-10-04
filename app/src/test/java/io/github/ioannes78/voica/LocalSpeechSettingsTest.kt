package io.github.ioannes78.voica

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSpeechSettingsTest {
    @Test
    fun autoPrefersNewChineseTransducerThenCtcThenLegacySmall() {
        assertEquals(
            listOf(
                Stage13ARealtimeModelIds.CHINESE_LARGE_TRANSDUCER,
                Stage13ARealtimeModelIds.CHINESE_LARGE_CTC,
                Stage13ARealtimeModelIds.SMALL_BILINGUAL,
            ),
            RealtimeAsrModelChoice.AUTO.preferredModelIds(),
        )
    }

    @Test
    fun explicitRealtimeChoicesNeverContainFallbackModels() {
        assertEquals(
            listOf(Stage13ARealtimeModelIds.SMALL_BILINGUAL),
            RealtimeAsrModelChoice.SMALL_BILINGUAL.preferredModelIds(),
        )
        assertEquals(
            listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_TRANSDUCER),
            RealtimeAsrModelChoice.CHINESE_LARGE_TRANSDUCER.preferredModelIds(),
        )
        assertEquals(
            listOf(Stage13ARealtimeModelIds.CHINESE_LARGE_CTC),
            RealtimeAsrModelChoice.CHINESE_LARGE_CTC.preferredModelIds(),
        )
    }

    @Test
    fun explicitOfflineQualityChoicesNeverFallbackAcrossTiers() {
        assertEquals(
            listOf(Stage13AOfflineModelIds.SENSEVOICE),
            OfflineAsrQualityChoice.AUTO.preferredModelIds(),
        )
        assertEquals(
            listOf(Stage13AOfflineModelIds.SENSEVOICE),
            OfflineAsrQualityChoice.BALANCED.preferredModelIds(),
        )
        assertEquals(
            listOf(Stage13AOfflineModelIds.FIRERED_ASR2),
            OfflineAsrQualityChoice.HIGH_QUALITY.preferredModelIds(),
        )
        assertEquals(
            listOf(Stage13AOfflineModelIds.QWEN3_ASR),
            OfflineAsrQualityChoice.ULTRA.preferredModelIds(),
        )
    }

    @Test
    fun performanceResolverKeepsAutoBackwardCompatibleAtTwoThreads() {
        val resolved =
            LocalSpeechSettings(
                performanceProfile = SpeechPerformanceProfile.AUTO,
            ).resolvePerformance(logicalProcessors = 8)

        assertEquals(2, resolved.effectiveThreads)
        assertEquals(8, resolved.logicalProcessors)
    }

    @Test
    fun performanceResolverCapsManualThreadsToDeviceAndEightThreadSafetyLimit() {
        assertEquals(
            4,
            LocalSpeechSettings(requestedThreads = 8)
                .resolvePerformance(logicalProcessors = 4)
                .effectiveThreads,
        )
        assertEquals(
            8,
            LocalSpeechSettings(requestedThreads = 64)
                .resolvePerformance(logicalProcessors = 16)
                .effectiveThreads,
        )
    }

    @Test
    fun vadDefaultsMatchExistingSherpaBaseline() {
        val vad = LocalVadSettings()

        assertEquals(0.5F, vad.threshold)
        assertEquals(0.25F, vad.minSilenceDurationSeconds)
        assertEquals(0.25F, vad.minSpeechDurationSeconds)
        assertEquals(30F, vad.maxSpeechDurationSeconds)
    }

    @Test
    fun explicitSpeakerCountPresetsResolveToExactNativeAndGlobalCounts() {
        listOf(
            SpeakerCountChoice.ONE to 1,
            SpeakerCountChoice.TWO to 2,
            SpeakerCountChoice.THREE to 3,
            SpeakerCountChoice.FOUR to 4,
        ).forEach { (choice, expectedCount) ->
            val config = choice.toDiarizationConfig()
            assertEquals(expectedCount, config.expectedSpeakerCount)
            assertEquals(expectedCount, config.minimumGlobalSpeakerCount)
            assertEquals(expectedCount, config.maximumGlobalSpeakerCount)
        }
    }

    @Test
    fun fivePlusStartsAtFiveAndAllowsBoundedGlobalGrowth() {
        val config = SpeakerCountChoice.FIVE_PLUS.toDiarizationConfig()

        assertEquals(FIVE_PLUS_MINIMUM_SPEAKERS, config.expectedSpeakerCount)
        assertEquals(FIVE_PLUS_MINIMUM_SPEAKERS, config.minimumGlobalSpeakerCount)
        assertEquals(FIVE_PLUS_MAXIMUM_SPEAKERS, config.maximumGlobalSpeakerCount)
        assertEquals(FIVE_PLUS_INITIAL_CLUSTERING_THRESHOLD, config.clusteringThreshold)
    }

    @Test
    fun performanceProfilesResolveToRealThreadCounts() {
        assertEquals(
            1,
            LocalSpeechSettings(performanceProfile = SpeechPerformanceProfile.POWER_SAVER)
                .resolvePerformance(logicalProcessors = 8)
                .effectiveThreads,
        )
        assertEquals(
            2,
            LocalSpeechSettings(performanceProfile = SpeechPerformanceProfile.BALANCED)
                .resolvePerformance(logicalProcessors = 8)
                .effectiveThreads,
        )
        assertEquals(
            4,
            LocalSpeechSettings(performanceProfile = SpeechPerformanceProfile.PERFORMANCE)
                .resolvePerformance(logicalProcessors = 8)
                .effectiveThreads,
        )
    }
}
