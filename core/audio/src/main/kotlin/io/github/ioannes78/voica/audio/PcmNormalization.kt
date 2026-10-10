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

    /**
     * Returns a bounded output capacity for a single streaming input block.
     *
     * The small guard accounts for the interpolation boundary carried across blocks. Hot-path
     * callers can keep one output buffer at least this large and reuse it for the whole stream.
     */
    fun requiredOutputCapacity(frameCount: Int): Int {
        require(frameCount >= 0)
        if (frameCount == 0) return 0
        return (
            frameCount.toLong() * targetSampleRateHz / sourceSampleRateHz + OUTPUT_GUARD_SAMPLES
        ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun processInterleaved(
        input: ShortArray,
        offset: Int = 0,
        frameCount: Int = (input.size - offset) / sourceChannelCount,
    ): ShortArray {
        val output = ShortArray(requiredOutputCapacity(frameCount))
        val written =
            processInterleavedInto(
                input = input,
                output = output,
                offset = offset,
                frameCount = frameCount,
            )
        return when {
            written == 0 -> ShortArray(0)
            written == output.size -> output
            else -> output.copyOf(written)
        }
    }

    /**
     * Allocation-free hot-path variant. The caller owns [output] and may reuse it on every block.
     * Returns the number of valid mono PCM16 samples written from index 0.
     */
    fun processInterleavedInto(
        input: ShortArray,
        output: ShortArray,
        offset: Int = 0,
        frameCount: Int = (input.size - offset) / sourceChannelCount,
    ): Int {
        require(offset >= 0)
        require(frameCount >= 0)
        require(offset + frameCount * sourceChannelCount <= input.size)
        require(output.size >= requiredOutputCapacity(frameCount)) {
            "normalizer output buffer too small: required=${requiredOutputCapacity(frameCount)} actual=${output.size}"
        }

        var cursor = offset
        var outputSize = 0
        var frame = 0
        while (frame < frameCount) {
            var sum = 0L
            var channel = 0
            while (channel < sourceChannelCount) {
                sum += input[cursor++].toLong()
                channel += 1
            }
            val currentMono =
                (sum / sourceChannelCount)
                    .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                    .toInt()
            sourceFrameIndex += 1

            val previous = previousMono
            if (previous == null) {
                previousMono = currentMono
                if (nextOutputIndex == 0L) {
                    output[outputSize++] = currentMono.toShort()
                    nextOutputIndex = 1L
                }
                frame += 1
                continue
            }

            val currentBoundary = sourceFrameIndex * targetSampleRateHz.toLong()
            while (nextOutputIndex * sourceSampleRateHz.toLong() <= currentBoundary) {
                val sourceNumerator = nextOutputIndex * sourceSampleRateHz.toLong()
                val baseIndex = sourceNumerator / targetSampleRateHz
                val remainder = sourceNumerator % targetSampleRateHz

                val sample =
                    when {
                        baseIndex == sourceFrameIndex && remainder == 0L -> currentMono.toLong()
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
                output[outputSize++] =
                    sample.coerceIn(
                        Short.MIN_VALUE.toLong(),
                        Short.MAX_VALUE.toLong(),
                    ).toShort()
                nextOutputIndex += 1
            }

            previousMono = currentMono
            frame += 1
        }

        return outputSize
    }

    val outputSampleCount: Long get() = nextOutputIndex

    private companion object {
        const val OUTPUT_GUARD_SAMPLES = 4L
    }
}
