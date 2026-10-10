package io.github.ioannes78.voica.audio

data class CanonicalConversionTiming(
    val decodeOrReadNanos: Long,
    val normalizeNanos: Long,
    val writer: CanonicalWavTiming,
    val pipelineNanos: Long,
) {
    val measuredNanos: Long
        get() =
            decodeOrReadNanos +
                normalizeNanos +
                writer.writeNanos +
                writer.syncNanos +
                writer.moveNanos +
                writer.outputHashNanos
}
