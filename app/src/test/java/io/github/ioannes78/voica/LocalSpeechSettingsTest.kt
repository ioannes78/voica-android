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
}
