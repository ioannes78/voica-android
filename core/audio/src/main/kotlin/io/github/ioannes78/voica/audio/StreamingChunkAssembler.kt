package io.github.ioannes78.voica.audio

/** Recognizer-facing PCM chunk with an exact epoch-local canonical sample range. */
data class StreamingPcmChunk(
    val sessionId: Long,
    val epochId: Long,
    val startSampleIndex: Long,
    val samples: ShortArray,
) {
    init {
        require(sessionId > 0L)
        require(epochId > 0L)
        require(startSampleIndex >= 0L)
        require(samples.isNotEmpty())
    }

    val sampleCount: Int
        get() = samples.size

    val endSampleIndexExclusive: Long
        get() = Math.addExact(startSampleIndex, samples.size.toLong())
}

data class StreamingChunkAssemblerSnapshot(
    val activeSessionId: Long?,
    val activeEpochId: Long?,
    val targetChunkSamples: Int,
    val bufferedSamples: Int,
    val expectedNextSampleIndex: Long?,
    val emittedChunks: Long,
    val emittedSamples: Long,
)

/**
 * Aggregates canonical frames into engine-sized chunks without changing the canonical timeline.
 *
 * Callers must explicitly start/reset epochs. The assembler rejects cross-epoch input and any
 * unexpected gap/overlap rather than silently stitching unrelated media.
 */
class StreamingChunkAssembler(
    val targetChunkSamples: Int,
) {
    init {
        require(targetChunkSamples > 0) { "targetChunkSamples must be positive" }
    }

    private val buffer = ShortArray(targetChunkSamples)
    private var bufferedSamples = 0
    private var chunkStartSampleIndex = 0L
    private var activeSessionId: Long? = null
    private var activeEpochId: Long? = null
    private var expectedNextSampleIndex: Long? = null
    private var emittedChunks = 0L
    private var emittedSamples = 0L

    fun startEpoch(sessionId: Long, epochId: Long) {
        require(sessionId > 0L)
        require(epochId > 0L)
        check(bufferedSamples == 0) { "flush or reset buffered PCM before starting a new epoch" }
        activeSessionId = sessionId
        activeEpochId = epochId
        expectedNextSampleIndex = null
    }

    fun accept(frame: RealtimeCanonicalPcmFrame): List<StreamingPcmChunk> {
        val sessionId = checkNotNull(activeSessionId) { "startEpoch must be called before accept" }
        val epochId = checkNotNull(activeEpochId) { "startEpoch must be called before accept" }
        require(frame.sessionId == sessionId) { "cross-session PCM requires an explicit reset" }
        require(frame.epochId == epochId) { "cross-epoch PCM requires an explicit reset" }

        val expected = expectedNextSampleIndex
        if (expected != null) {
            require(frame.startSampleIndex == expected) {
                "canonical PCM gap/overlap: expected=$expected actual=${frame.startSampleIndex}"
            }
        }

        val chunks = mutableListOf<StreamingPcmChunk>()
        var sourceOffset = 0
        while (sourceOffset < frame.sampleCount) {
            if (bufferedSamples == 0) {
                chunkStartSampleIndex = frame.startSampleIndex + sourceOffset
            }
            val copyCount = minOf(targetChunkSamples - bufferedSamples, frame.sampleCount - sourceOffset)
            frame.samples.copyInto(
                destination = buffer,
                destinationOffset = bufferedSamples,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyCount,
            )
            bufferedSamples += copyCount
            sourceOffset += copyCount
            expectedNextSampleIndex = frame.startSampleIndex + sourceOffset

            if (bufferedSamples == targetChunkSamples) {
                chunks += emitBufferedChunk(sessionId, epochId)
            }
        }
        return chunks
    }

    /** Emits a final short chunk for the current continuity epoch, if any PCM remains buffered. */
    fun flush(): StreamingPcmChunk? {
        if (bufferedSamples == 0) return null
        val sessionId = checkNotNull(activeSessionId)
        val epochId = checkNotNull(activeEpochId)
        return emitBufferedChunk(sessionId, epochId)
    }

    fun reset() {
        bufferedSamples = 0
        chunkStartSampleIndex = 0L
        activeSessionId = null
        activeEpochId = null
        expectedNextSampleIndex = null
    }

    fun snapshot(): StreamingChunkAssemblerSnapshot = StreamingChunkAssemblerSnapshot(
        activeSessionId = activeSessionId,
        activeEpochId = activeEpochId,
        targetChunkSamples = targetChunkSamples,
        bufferedSamples = bufferedSamples,
        expectedNextSampleIndex = expectedNextSampleIndex,
        emittedChunks = emittedChunks,
        emittedSamples = emittedSamples,
    )

    private fun emitBufferedChunk(sessionId: Long, epochId: Long): StreamingPcmChunk {
        check(bufferedSamples > 0)
        val samples = buffer.copyOf(bufferedSamples)
        val chunk = StreamingPcmChunk(
            sessionId = sessionId,
            epochId = epochId,
            startSampleIndex = chunkStartSampleIndex,
            samples = samples,
        )
        emittedChunks += 1
        emittedSamples += bufferedSamples
        bufferedSamples = 0
        return chunk
    }
}
