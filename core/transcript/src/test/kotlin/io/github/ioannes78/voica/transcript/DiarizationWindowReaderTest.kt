package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarizationWindowReaderTest {
    @Test
    fun overlappingWindowsReuseRetainedTailWithoutSeekingBackwards() = runBlocking {
        val source = PatternPcmSource(totalSampleCount = 120L * 16_000L)
        val config = DiarizationConfig(vadContextPaddingSamples = 0L)
        val ranges =
            planDiarizationWindows(
                speechSegments =
                    listOf(
                        SpeechSegment(
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = source.totalSampleCount,
                        ),
                    ),
                totalSampleCount = source.totalSampleCount,
                config = config,
            )
        val seen = mutableListOf<DiarizationWindow>()

        consumeDiarizationWindowsSequentially(source, ranges) { _, window ->
            seen += window
        }

        assertEquals(3, seen.size)
        assertEquals(0L, seen[0].startSampleIndex)
        assertEquals(50L * 16_000L, seen[1].startSampleIndex)
        assertEquals(100L * 16_000L, seen[2].startSampleIndex)
        assertEquals(
            seen[0].samples.copyOfRange(50 * 16_000, 60 * 16_000).toList(),
            seen[1].samples.copyOfRange(0, 10 * 16_000).toList(),
        )
        assertEquals(source.totalSampleCount, source.cursor)
        assertTrue(source.maxRequestedSamples <= 60 * 16_000)
    }

    @Test
    fun sparseWindowsConsumeSilenceWithoutAllocatingTheGap() = runBlocking {
        val total = 120L * 60L * 16_000L
        val source = PatternPcmSource(totalSampleCount = total)
        val ranges =
            listOf(
                DiarizationWindowRange(
                    startSampleIndex = 16_000L,
                    endSampleIndexExclusive = 32_000L,
                ),
                DiarizationWindowRange(
                    startSampleIndex = total - 32_000L,
                    endSampleIndexExclusive = total - 16_000L,
                ),
            )
        val sizes = mutableListOf<Int>()

        consumeDiarizationWindowsSequentially(
            source = source,
            ranges = ranges,
            readChunkSamples = 2_048,
        ) { _, window ->
            sizes += window.samples.size
        }

        assertEquals(listOf(16_000, 16_000), sizes)
        assertTrue(source.maxRequestedSamples <= 16_000)
    }

    @Test
    fun virtualThirtySixtyAndOneTwentyMinutePlansStayAtSixtySecondWindowMemory() =
        runBlocking {
            for (minutes in listOf(30L, 60L, 120L)) {
                val total = minutes * 60L * 16_000L
                val source = PatternPcmSource(totalSampleCount = total)
                val ranges =
                    planDiarizationWindows(
                        speechSegments = listOf(SpeechSegment(0L, total)),
                        totalSampleCount = total,
                        config = DiarizationConfig(vadContextPaddingSamples = 0L),
                    )
                var maxWindow = 0

                consumeDiarizationWindowsSequentially(source, ranges) { _, window ->
                    maxWindow = maxOf(maxWindow, window.samples.size)
                }

                assertTrue(maxWindow <= 60 * 16_000)
            }
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWindowPastCanonicalEnd() = runBlocking {
        val source = PatternPcmSource(totalSampleCount = 16_000L)
        consumeDiarizationWindowsSequentially(
            source = source,
            ranges = listOf(DiarizationWindowRange(0L, 16_001L)),
        ) { _, _ -> }
    }

    private class PatternPcmSource(
        override val totalSampleCount: Long,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        var cursor = 0L
        var maxRequestedSamples = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= totalSampleCount) return null
            require(maxSamples > 0)
            maxRequestedSamples = maxOf(maxRequestedSamples, maxSamples)
            val count =
                minOf(
                    maxSamples.toLong(),
                    totalSampleCount - cursor,
                ).toInt()
            val start = cursor
            repeat(count) { offset ->
                target[targetOffset + offset] =
                    ((cursor + offset) % Short.MAX_VALUE).toShort()
            }
            cursor += count
            return PcmReadResult(start, count)
        }

        override fun close() = Unit
    }
}
