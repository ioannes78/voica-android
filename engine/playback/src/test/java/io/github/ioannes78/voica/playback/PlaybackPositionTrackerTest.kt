package io.github.ioannes78.voica.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPositionTrackerTest {
    @Test
    fun mapsSinkFramesOntoAbsoluteSourceSamples() {
        val tracker = PlaybackPositionTracker()
        tracker.rebase(sourceSampleIndex = 32_000L, sinkFramePosition = 1_000L)

        assertEquals(
            32_000L,
            tracker.absoluteSample(1_000L, totalSampleCount = 100_000L),
        )
        assertEquals(
            48_000L,
            tracker.absoluteSample(17_000L, totalSampleCount = 100_000L),
        )
    }

    @Test
    fun rebaseAfterSeekAllowsPlaybackHeadReset() {
        val tracker = PlaybackPositionTracker()
        tracker.rebase(sourceSampleIndex = 10_000L, sinkFramePosition = 90_000L)
        assertEquals(
            11_000L,
            tracker.absoluteSample(91_000L, totalSampleCount = 200_000L),
        )

        tracker.rebase(sourceSampleIndex = 150_000L, sinkFramePosition = 0L)
        assertEquals(
            150_500L,
            tracker.absoluteSample(500L, totalSampleCount = 200_000L),
        )
    }

    @Test
    fun extends32BitPlaybackHeadWrap() {
        val tracker = PlaybackPositionTracker()
        tracker.rebase(
            sourceSampleIndex = 0L,
            sinkFramePosition = 0xFFFF_FFF0L,
        )
        assertEquals(
            32L,
            tracker.absoluteSample(0x10L, totalSampleCount = 1_000_000L),
        )
    }

    @Test
    fun clampsPresentedPositionToDuration() {
        val tracker = PlaybackPositionTracker()
        tracker.rebase(sourceSampleIndex = 90L, sinkFramePosition = 1_000L)
        assertEquals(
            100L,
            tracker.absoluteSample(2_000L, totalSampleCount = 100L),
        )
    }
}
