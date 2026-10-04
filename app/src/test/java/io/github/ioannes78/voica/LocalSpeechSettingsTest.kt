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
