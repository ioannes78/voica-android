package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.model.ModelDescriptor
import java.io.Closeable

data class StreamingVadSpeechSegment(
    val startSampleOffset: Long,
    val endSampleOffsetExclusive: Long,
) {
    init {
        require(startSampleOffset >= 0L)
        require(endSampleOffsetExclusive > startSampleOffset)
    }
}

data class StreamingVadUpdate(
    val speechDetected: Boolean,
    val completedSegments: List<StreamingVadSpeechSegment> = emptyList(),
)

/** True-streaming VAD contract. Sample offsets are relative to the current VAD session/reset. */
interface StreamingVadEngine : Closeable {
    val model: ModelDescriptor

    suspend fun openSession(): StreamingVadSession
}

interface StreamingVadSession : Closeable {
    suspend fun acceptSamples(
        samples: ShortArray,
        offset: Int = 0,
        count: Int = samples.size - offset,
    ): StreamingVadUpdate

    suspend fun finishInput(): StreamingVadUpdate

    suspend fun reset()
}
