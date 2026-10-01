package io.github.ioannes78.voica.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptContractsTest {
    @Test
    fun progressUsesOnlyRealDenominator() {
        assertEquals(
            0.25,
            TranscriptionProgress(
                phase = TranscriptionPhase.FIRST_PASS,
                processedUnits = 250,
                totalUnits = 1_000,
            ).fraction!!,
            0.000001,
        )
        assertNull(
            TranscriptionProgress(
                phase = TranscriptionPhase.FIRST_PASS,
                processedUnits = 250,
                totalUnits = null,
            ).fraction,
        )
    }

    @Test
    fun relativeSecondsMapOntoCanonicalAbsoluteTimeline() {
        assertEquals(
            48_000L,
            relativeSecondsToAbsoluteSampleIndex(
                segmentStartSampleIndex = 32_000L,
                relativeSeconds = 1.0,
                sampleRateHz = 16_000,
                segmentEndSampleIndexExclusive = 80_000L,
            ),
        )
    }

    @Test
    fun relativeTimeIsClampedToSegmentEnd() {
        assertEquals(
            80_000L,
            relativeSecondsToAbsoluteSampleIndex(
                segmentStartSampleIndex = 32_000L,
                relativeSeconds = 99.0,
                sampleRateHz = 16_000,
                segmentEndSampleIndexExclusive = 80_000L,
            ),
        )
    }

    @Test
    fun tokenMappingPreservesAbsoluteSampleCoordinates() {
        val mapped =
            mapRelativeTokensToAbsolute(
                tokens =
                    listOf(
                        RelativeTimedToken("今", 0L, 1_600L),
                        RelativeTimedToken("天", 1_600L, 3_200L),
                    ),
                segment = SpeechSegment(32_000L, 64_000L),
            )

        assertEquals(32_000L, mapped[0].startSampleIndex)
        assertEquals(33_600L, mapped[0].endSampleIndexExclusive)
        assertEquals(33_600L, mapped[1].startSampleIndex)
        assertEquals(35_200L, mapped[1].endSampleIndexExclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tokenMappingRejectsNonMonotonicTimes() {
        mapRelativeTokensToAbsolute(
            tokens =
                listOf(
                    RelativeTimedToken("A", 2_000L),
                    RelativeTimedToken("B", 1_000L),
                ),
            segment = SpeechSegment(0L, 10_000L),
        )
    }
}
