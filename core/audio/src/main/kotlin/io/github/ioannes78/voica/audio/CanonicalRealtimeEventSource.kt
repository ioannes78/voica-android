package io.github.ioannes78.voica.audio

/**
 * Stage 16-facing source abstraction for canonical realtime media events.
 *
 * Implementations must preserve Stage 15 event ordering. Consumers must not infer media time from
 * wall clock, BLE arrival time, protocol sequence, or transport generation.
 */
interface CanonicalRealtimeEventSource {
    suspend fun receive(): RealtimeCanonicalStreamEvent?
}

/**
 * Thin adapter around the frozen Stage 15 canonical source.
 *
 * Keeping the adapter separate avoids changing Stage 15 transport/media behavior while allowing a
 * future canonical source (for example Stage 16A.1 phone microphone capture) to reuse the same
 * Stage 16 pipeline.
 */
class Stage15CanonicalRealtimeEventSourceAdapter(
    private val delegate: RealtimeCanonicalAudioSource,
) : CanonicalRealtimeEventSource {
    override suspend fun receive(): RealtimeCanonicalStreamEvent? = delegate.receive()
}
