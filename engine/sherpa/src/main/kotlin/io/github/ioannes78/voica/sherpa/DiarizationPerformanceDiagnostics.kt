package io.github.ioannes78.voica.sherpa

import java.util.Locale

/** Lightweight QA observability for the real offline diarization path. */
data class DiarizationWindowPerformanceDiagnostic(
    val audioDurationMs: Long,
    val processMs: Long,
    val localSpeakerCount: Int,
    val turnCount: Int,
) {
    init {
        require(audioDurationMs > 0L)
        require(processMs >= 0L)
        require(localSpeakerCount >= 0)
        require(turnCount >= 0)
    }
}

data class SpeakerEmbeddingPerformanceDiagnostic(
    val audioDurationMs: Long,
    val computeMs: Long,
) {
    init {
        require(audioDurationMs > 0L)
        require(computeMs >= 0L)
    }
}

object DiarizationPerformanceDiagnostics {
    private const val MAX_WINDOWS = 64
    private const val MAX_EMBEDDINGS = 256
    private val lock = Any()
    private val windows = ArrayDeque<DiarizationWindowPerformanceDiagnostic>()
    private val embeddings = ArrayDeque<SpeakerEmbeddingPerformanceDiagnostic>()

    fun recordWindow(item: DiarizationWindowPerformanceDiagnostic) {
        synchronized(lock) {
            while (windows.size >= MAX_WINDOWS) windows.removeFirst()
            windows.addLast(item)
        }
    }

    fun recordEmbedding(item: SpeakerEmbeddingPerformanceDiagnostic) {
        synchronized(lock) {
            while (embeddings.size >= MAX_EMBEDDINGS) embeddings.removeFirst()
            embeddings.addLast(item)
        }
    }

    fun clear() {
        synchronized(lock) {
            windows.clear()
            embeddings.clear()
        }
    }

    fun formatForQa(): String =
        synchronized(lock) {
            if (windows.isEmpty() && embeddings.isEmpty()) {
                return@synchronized "No diarization performance diagnostics yet."
            }
            val windowAudioMs = windows.sumOf { it.audioDurationMs }
            val processMs = windows.sumOf { it.processMs }
            val embeddingAudioMs = embeddings.sumOf { it.audioDurationMs }
            val embeddingMs = embeddings.sumOf { it.computeMs }
            buildString {
                append("summary windows=").append(windows.size)
                append(" localSpeakerCounts=")
                    .append(windows.joinToString(prefix = "[", postfix = "]") { it.localSpeakerCount.toString() })
                append(" windowAudioMs=").append(windowAudioMs)
                append(" diarizationProcessMs=").append(processMs)
                append(" embeddingCalls=").append(embeddings.size)
                append(" embeddingAudioMs=").append(embeddingAudioMs)
                append(" embeddingComputeMs=").append(embeddingMs)
                val totalMeasuredMs = processMs + embeddingMs
                append(" measuredComputeMs=").append(totalMeasuredMs)
                if (windowAudioMs > 0L) {
                    append(" measuredComputeRtf=")
                        .append(String.format(Locale.US, "%.4f", totalMeasuredMs.toDouble() / windowAudioMs.toDouble()))
                }
                append('\n')
                windows.forEachIndexed { index, item ->
                    append("window#").append(index + 1)
                    append(" audioMs=").append(item.audioDurationMs)
                    append(" processMs=").append(item.processMs)
                    append(" localSpeakers=").append(item.localSpeakerCount)
                    append(" turns=").append(item.turnCount)
                    append('\n')
                }
            }.trimEnd()
        }
}
