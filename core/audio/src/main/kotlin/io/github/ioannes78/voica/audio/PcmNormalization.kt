package io.github.ioannes78.voica.audio

/**
 * Stateful streaming PCM16 normalizer.
 *
 * Input is interleaved signed PCM16. Channels are downmixed by arithmetic mean and
 * sample-rate conversion uses deterministic linear interpolation across adjacent source
 * frames. State is bounded and independent of recording duration.
 */
class StreamingPcm16Normalizer(
    private val sourceSampleRateHz: Int,
    private val sourceChannelCount: Int,
    private val targetSampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ,
) {
    private var sourceFrameIndex = -1L
    private var previousMono: Int? = null
    private var nextOutputIndex = 0L

    init {
        require(sourceSampleRateHz > 0)
        require(sourceChannelCount > 0)
        require(targetSampleRateHz > 0)
    }

    fun processInterleaved(
        input: ShortArray,
        offset: Int = 0,
        frameCount: Int = (input.size - offset) / sourceChannelCount,
    ): ShortArray {
        require(offset >= 0)
        require(frameCount >= 0)
        require(offset + frameCount * sourceChannelCount <= input.size)

        val estimated = (
            frameCount.toLong() * targetSampleRateHz / sourceSampleRateHz + 4L
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val output = ShortArrayBuilder(estimated.coerceAtLeast(4))

        var cursor = offset
        repeat(frameCount) {
            var sum = 0L
            repeat(sourceChannelCount) {
                sum += input[cursor++].toLong()
            }
            val currentMono = (sum / sourceChannelCount)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toInt()
            sourceFrameIndex += 1

            val previous = previousMono
            if (previous == null) {
                previousMono = currentMono
                if (nextOutputIndex == 0L) {
                    output.add(currentMono.toShort())
                    nextOutputIndex = 1L
                }
                return@repeat
            }

            val currentBoundary = sourceFrameIndex * targetSampleRateHz.toLong()
            while (nextOutputIndex * sourceSampleRateHz.toLong() <= currentBoundary) {
                val sourceNumerator = nextOutputIndex * sourceSampleRateHz.toLong()
                val baseIndex = sourceNumerator / targetSampleRateHz
                val remainder = sourceNumerator % targetSampleRateHz

                val sample = when {
                    baseIndex == sourceFrameIndex && remainder == 0L -> currentMono
                    baseIndex == sourceFrameIndex - 1L -> {
                        val delta = currentMono.toLong() - previous.toLong()
                        previous.toLong() +
                            (delta * remainder + targetSampleRateHz / 2L) /
                            targetSampleRateHz
                    }
                    else -> error(
                        "resampler state gap: base=$baseIndex current=$sourceFrameIndex",
                    )
                }
                output.add(
                    sample.coerceIn(
                        Short.MIN_VALUE.toLong(),
                        Short.MAX_VALUE.toLong(),
                    ).toShort(),
                )
                nextOutputIndex += 1
            }

            previousMono = currentMono
        }

        return output.toArray()
    }

    val outputSampleCount: Long get() = nextOutputIndex
}

private class ShortArrayBuilder(initialCapacity: Int) {
    private var values = ShortArray(initialCapacity)
    private var size = 0

    fun add(value: Short) {
        if (size == values.size) {
            values = values.copyOf((values.size * 2).coerceAtLeast(4))
        }
        values[size++] = value
    }

    fun toArray(): ShortArray = values.copyOf(size)
}
