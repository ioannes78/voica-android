package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSource

suspend fun consumeDiarizationWindowsSequentially(
    source: PcmSource,
    ranges: List<DiarizationWindowRange>,
    readChunkSamples: Int = 4_096,
    consumer: suspend (index: Int, window: DiarizationWindow) -> Unit,
) {
    require(readChunkSamples > 0)
    require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
    require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
    require(source.totalSampleCount >= 0L)

    if (ranges.isEmpty()) return

    var previousStart = -1L
    ranges.forEach { range ->
        require(range.startSampleIndex >= previousStart) {
            "diarization windows must be ordered by absolute start sample"
        }
        require(range.endSampleIndexExclusive <= source.totalSampleCount) {
            "diarization window exceeds canonical PCM length"
        }
        previousStart = range.startSampleIndex
    }

    val scratch = ShortArray(readChunkSamples)
    var cursor = 0L
    var retainedStart = 0L
    var retained = ShortArray(0)

    ranges.forEachIndexed { index, range ->
        val sampleCount = Math.toIntExact(range.sampleCount)
        val samples = ShortArray(sampleCount)
        var filled = 0

        if (range.startSampleIndex < cursor) {
            val overlapCount = Math.toIntExact(cursor - range.startSampleIndex)
            require(overlapCount <= sampleCount) {
                "overlapping diarization window is fully behind PCM cursor"
            }
            val retainedEnd =
                Math.addExact(retainedStart, retained.size.toLong())
            require(
                range.startSampleIndex >= retainedStart &&
                    cursor <= retainedEnd,
            ) {
                "required overlap is no longer retained"
            }
            val retainedOffset =
                Math.toIntExact(range.startSampleIndex - retainedStart)
            retained.copyInto(
                destination = samples,
                destinationOffset = 0,
                startIndex = retainedOffset,
                endIndex = retainedOffset + overlapCount,
            )
            filled = overlapCount
        } else if (range.startSampleIndex > cursor) {
            cursor =
                skipSequentially(
                    source = source,
                    cursor = cursor,
                    targetSampleIndex = range.startSampleIndex,
                    scratch = scratch,
                )
        }

        while (filled < samples.size) {
            val read =
                source.read(
                    target = samples,
                    targetOffset = filled,
                    maxSamples = samples.size - filled,
                ) ?: error("canonical PCM ended before diarization window")
            require(read.startSampleIndex == cursor) {
                "PCM source is not sequential: expected $cursor, got ${read.startSampleIndex}"
            }
            require(read.sampleCount in 1..(samples.size - filled))
            filled += read.sampleCount
            cursor = Math.addExact(cursor, read.sampleCount.toLong())
        }

        require(cursor >= range.endSampleIndexExclusive)
        consumer(
            index,
            DiarizationWindow(
                startSampleIndex = range.startSampleIndex,
                samples = samples,
                sampleRateHz = source.sampleRateHz,
            ),
        )

        val next = ranges.getOrNull(index + 1)
        if (next != null && next.startSampleIndex < cursor) {
            require(next.endSampleIndexExclusive > cursor) {
                "overlapping diarization windows must advance the PCM cursor"
            }
            require(next.startSampleIndex >= range.startSampleIndex) {
                "next diarization window starts before current window"
            }
            val offset =
                Math.toIntExact(next.startSampleIndex - range.startSampleIndex)
            retained = samples.copyOfRange(offset, samples.size)
            retainedStart = next.startSampleIndex
        } else {
            retained = ShortArray(0)
            retainedStart = cursor
        }
    }
}

private suspend fun skipSequentially(
    source: PcmSource,
    cursor: Long,
    targetSampleIndex: Long,
    scratch: ShortArray,
): Long {
    require(targetSampleIndex >= cursor)
    var current = cursor
    while (current < targetSampleIndex) {
        val requested =
            minOf(
                scratch.size.toLong(),
                targetSampleIndex - current,
            ).toInt()
        val read =
            source.read(
                target = scratch,
                targetOffset = 0,
                maxSamples = requested,
            ) ?: error("canonical PCM ended while skipping silence")
        require(read.startSampleIndex == current) {
            "PCM source is not sequential while skipping: expected $current, got ${read.startSampleIndex}"
        }
        require(read.sampleCount in 1..requested)
        current = Math.addExact(current, read.sampleCount.toLong())
    }
    return current
}
