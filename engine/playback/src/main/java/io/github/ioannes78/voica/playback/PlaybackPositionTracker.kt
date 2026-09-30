package io.github.ioannes78.voica.playback

internal class PlaybackPositionTracker {
    private var anchorSourceSample = 0L
    private var anchorExtendedFrame = 0L
    private var lastRaw32 = 0L
    private var wrapCount = 0L
    private var useWideCounter = false
    private var initialized = false

    fun rebase(
        sourceSampleIndex: Long,
        sinkFramePosition: Long,
    ) {
        require(sourceSampleIndex >= 0L)
        require(sinkFramePosition >= 0L)
        anchorSourceSample = sourceSampleIndex
        initialized = true
        if (sinkFramePosition > UINT32_MASK) {
            useWideCounter = true
            anchorExtendedFrame = sinkFramePosition
            lastRaw32 = sinkFramePosition and UINT32_MASK
            wrapCount = sinkFramePosition ushr 32
        } else {
            useWideCounter = false
            lastRaw32 = sinkFramePosition and UINT32_MASK
            wrapCount = 0L
            anchorExtendedFrame = lastRaw32
        }
    }

    fun absoluteSample(
        sinkFramePosition: Long,
        totalSampleCount: Long,
    ): Long {
        require(totalSampleCount >= 0L)
        if (!initialized) {
            rebase(0L, sinkFramePosition.coerceAtLeast(0L))
        }

        val extended = extend(sinkFramePosition.coerceAtLeast(0L))
        val delta = (extended - anchorExtendedFrame).coerceAtLeast(0L)
        return (anchorSourceSample + delta).coerceIn(0L, totalSampleCount)
    }

    private fun extend(framePosition: Long): Long {
        if (framePosition > UINT32_MASK || useWideCounter) {
            useWideCounter = true
            return framePosition
        }

        val raw = framePosition and UINT32_MASK
        if (lastRaw32 - raw > UINT32_HALF_RANGE) {
            wrapCount += 1L
        }
        lastRaw32 = raw
        return (wrapCount shl 32) or raw
    }

    private companion object {
        const val UINT32_MASK = 0xFFFF_FFFFL
        const val UINT32_HALF_RANGE = 0x8000_0000L
    }
}
