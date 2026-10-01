package io.github.ioannes78.voica.playback

internal class PlaybackPositionTracker {
    private var anchorSourceSample = 0L
    private var anchorExtendedFrame = 0L
    private var lastExtendedFrame = 0L
    private var wrapCount = 0L
    private var initialized = false

    fun rebase(
        sourceSampleIndex: Long,
        sinkFramePosition: Long,
    ) {
        require(sourceSampleIndex >= 0L)
        require(sinkFramePosition >= 0L)

        val normalized = sinkFramePosition
        anchorSourceSample = sourceSampleIndex
        anchorExtendedFrame = normalized
        lastExtendedFrame = normalized
        wrapCount = normalized ushr 32
        initialized = true
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
        if (framePosition > UINT32_MASK) {
            lastExtendedFrame = framePosition
            wrapCount = framePosition ushr 32
            return framePosition
        }

        val raw = framePosition and UINT32_MASK
        var candidate = (wrapCount shl 32) or raw
        if (lastExtendedFrame - candidate > UINT32_HALF_RANGE) {
            candidate += UINT32_RANGE
        } else if (
            candidate - lastExtendedFrame > UINT32_HALF_RANGE &&
            candidate >= UINT32_RANGE
        ) {
            candidate -= UINT32_RANGE
        }

        wrapCount = candidate ushr 32
        lastExtendedFrame = candidate
        return candidate
    }

    private companion object {
        const val UINT32_MASK = 0xFFFF_FFFFL
        const val UINT32_HALF_RANGE = 0x8000_0000L
        const val UINT32_RANGE = 0x1_0000_0000L
    }
}
