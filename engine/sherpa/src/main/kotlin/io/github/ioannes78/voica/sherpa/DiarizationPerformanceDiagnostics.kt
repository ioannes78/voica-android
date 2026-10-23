package io.github.ioannes78.voica.sherpa

import java.util.Locale

/** Actual runtime parameters used by the native diarization path for the latest QA run. */
data class DiarizationRuntimeConfigDiagnostic(
    val expectedSpeakerCount: Int?,
    val clusteringThreshold: Float,
    val stitchingCosineThreshold: Float,
    val numThreads: Int,
    val chunkSizeSamples: Long,
    val chunkOverlapSamples: Long,
    val sampleRateHz: Int,
    val pyannoteWindowShiftRatio: Float,
) {
    init {
        require(expectedSpeakerCount == null || expectedSpeakerCount > 0)
        require(clusteringThreshold.isFinite())
        require(stitchingCosineThreshold.isFinite())
        require(numThreads > 0)
        require(chunkSizeSamples > 0L)
        require(chunkOverlapSamples >= 0L)
        require(sampleRateHz > 0)
        require(pyannoteWindowShiftRatio > 0F)
    }
}

/** Lightweight QA observability for the real offline diarization path. */
data class DiarizationWindowPerformanceDiagnostic(
    val audioDurationMs: Long,
    val processMs: Long,
    val localSpeakerCount: Int,
    val turnCount: Int,
) {
    init {
        require(audioDurationMs >= 0L)
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
        require(audioDurationMs >= 0L)
        require(computeMs >= 0L)
    }
}

object DiarizationPerformanceDiagnostics {
    private const val MAX_WINDOWS = 64
    private const val MAX_EMBEDDINGS = 256
    private val lock = Any()
    private val windows = ArrayDeque<DiarizationWindowPerformanceDiagnostic>()
    private val embeddings = ArrayDeque<SpeakerEmbeddingPerformanceDiagnostic>()
    private var runtimeConfig: DiarizationRuntimeConfigDiagnostic? = null

    fun recordRuntimeConfig(item: DiarizationRuntimeConfigDiagnostic) {
        synchronized(lock) {
            runtimeConfig = item
        }
    }

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
            runtimeConfig = null
            windows.clear()
            embeddings.clear()
        }
    }

    fun formatForQa(): String =
        synchronized(lock) {
            if (runtimeConfig == null && windows.isEmpty() && embeddings.isEmpty()) {
                return@synchronized "No diarization performance diagnostics yet."
            }
            val windowAudioMs = windows.sumOf { it.audioDurationMs }
            val processMs = windows.sumOf { it.processMs }
            val embeddingAudioMs = embeddings.sumOf { it.audioDurationMs }
            val embeddingMs = embeddings.sumOf { it.computeMs }
            buildString {
                runtimeConfig?.let { config ->
                    append("runtime expectedSpeakerCount=")
                        .append(config.expectedSpeakerCount?.toString() ?: "auto")
                    append(" clusteringThreshold=")
                        .append(String.format(Locale.US, "%.2f", config.clusteringThreshold))
                    append(" stitchingCosineThreshold=")
                        .append(String.format(Locale.US, "%.2f", config.stitchingCosineThreshold))
                    append(" numThreads=").append(config.numThreads)
                    append(" chunkSize=")
                        .append(String.format(Locale.US, "%.3fs", config.chunkSizeSamples.toDouble() / config.sampleRateHz.toDouble()))
                    append(" chunkOverlap=")
                        .append(String.format(Locale.US, "%.3fs", config.chunkOverlapSamples.toDouble() / config.sampleRateHz.toDouble()))
                    append(" pyannoteWindowShiftRatio=")
                        .append(String.format(Locale.US, "%.2f", config.pyannoteWindowShiftRatio))
                    append('\n')
                }
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
