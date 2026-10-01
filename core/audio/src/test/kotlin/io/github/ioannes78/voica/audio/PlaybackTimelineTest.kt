package io.github.ioannes78.voica.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackTimelineTest {
    @Test
    fun sampleAndTimeConversionsUseCanonicalIntegerTimeline() {
        val rate = CanonicalPcmProfile.SAMPLE_RATE_HZ

        assertEquals(0L, sampleIndexToTimeUs(0L, rate))
        assertEquals(62L, sampleIndexToTimeUs(1L, rate))
        assertEquals(999_937L, sampleIndexToTimeUs(15_999L, rate))
        assertEquals(1_000_000L, sampleIndexToTimeUs(16_000L, rate))

        assertEquals(0L, timeUsToSampleIndex(0L, rate))
        assertEquals(0L, timeUsToSampleIndex(62L, rate))
        assertEquals(1L, timeUsToSampleIndex(63L, rate))
        assertEquals(16_000L, timeUsToSampleIndex(1_000_000L, rate))
    }

    @Test
    fun twoHourTimelineDoesNotAccumulateMillisecondDrift() {
        val samples = 2L * 60L * 60L * CanonicalPcmProfile.SAMPLE_RATE_HZ
        val timeUs = sampleIndexToTimeUs(samples, CanonicalPcmProfile.SAMPLE_RATE_HZ)

        assertEquals(115_200_000L, samples)
        assertEquals(7_200_000_000L, timeUs)
        assertEquals(
            samples,
            timeUsToSampleIndex(timeUs, CanonicalPcmProfile.SAMPLE_RATE_HZ),
        )
    }

    @Test
    fun pcmByteAddressUsesRealDataOffsetAndClampsAtEof() {
        assertEquals(
            118L,
            sampleIndexToPcmByteOffset(
                sampleIndex = 10L,
                totalSampleCount = 100L,
                pcmDataOffsetBytes = 98L,
                bytesPerFrame = 2,
            ),
        )
        assertEquals(
            298L,
            sampleIndexToPcmByteOffset(
                sampleIndex = 1_000L,
                totalSampleCount = 100L,
                pcmDataOffsetBytes = 98L,
                bytesPerFrame = 2,
            ),
        )
        assertEquals(
            98L,
            sampleIndexToPcmByteOffset(
                sampleIndex = -5L,
                totalSampleCount = 100L,
                pcmDataOffsetBytes = 98L,
                bytesPerFrame = 2,
            ),
        )
    }

    @Test
    fun timelineRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            sampleIndexToTimeUs(-1L, CanonicalPcmProfile.SAMPLE_RATE_HZ)
        }
        assertThrows(IllegalArgumentException::class.java) {
            timeUsToSampleIndex(-1L, CanonicalPcmProfile.SAMPLE_RATE_HZ)
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleIndexToPcmByteOffset(
                sampleIndex = 0L,
                totalSampleCount = -1L,
                pcmDataOffsetBytes = 44L,
                bytesPerFrame = 2,
            )
        }
    }
}
