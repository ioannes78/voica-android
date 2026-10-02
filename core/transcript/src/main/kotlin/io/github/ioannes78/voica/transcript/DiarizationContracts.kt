package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.model.ModelDescriptor
import java.io.Closeable

enum class DiarizationState {
    PREPARING,
    VAD_ANALYZING,
    DIARIZING,
    STITCHING,
    ALIGNING,
    PERSISTING,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED_RECOVERABLE,
    FAILED_PERMANENT,
}

enum class DiarizationPhase {
    PREPARING,
    VAD,
    DIARIZATION,
    STITCHING,
    ALIGNMENT,
    PERSISTING,
}

data class DiarizationConfig(
    val sampleRateHz: Int = CANONICAL_SAMPLE_RATE_HZ,
    val chunkSizeSamples: Long = 60L * CANONICAL_SAMPLE_RATE_HZ,
    val chunkOverlapSamples: Long = 10L * CANONICAL_SAMPLE_RATE_HZ,
    val vadContextPaddingSamples: Long = CANONICAL_SAMPLE_RATE_HZ / 2L,
    val expectedSpeakerCount: Int? = null,
    val clusteringThreshold: Float? = null,
) {
    init {
        require(sampleRateHz == CANONICAL_SAMPLE_RATE_HZ) {
            "Stage 9 diarization requires canonical 16 kHz PCM"
        }
        require(chunkSizeSamples > 0L)
        require(chunkOverlapSamples >= 0L)
        require(chunkOverlapSamples < chunkSizeSamples)
        require(vadContextPaddingSamples >= 0L)
        require(expectedSpeakerCount == null || expectedSpeakerCount > 0)
        require(clusteringThreshold == null || clusteringThreshold.isFinite())
    }

    companion object {
        const val CANONICAL_SAMPLE_RATE_HZ = 16_000
    }
}

data class DiarizationWindow(
    val startSampleIndex: Long,
    val samples: ShortArray,
    val sampleRateHz: Int = DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ,
) {
    init {
        require(startSampleIndex >= 0L)
        require(samples.isNotEmpty())
        require(sampleRateHz == DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ)
    }

    val endSampleIndexExclusive: Long
        get() = Math.addExact(startSampleIndex, samples.size.toLong())
}

data class DiarizationSpeakerTurn(
    val speakerIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val confidence: Float? = null,
    val overlap: Boolean = false,
) {
    init {
        require(speakerIndex >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(confidence == null || (confidence.isFinite() && confidence in 0f..1f))
    }

    val sampleCount: Long
        get() = endSampleIndexExclusive - startSampleIndex
}

data class DiarizationChunkResult(
    val speakerCount: Int,
    val turns: List<DiarizationSpeakerTurn>,
) {
    init {
        require(speakerCount >= 0)
        require(turns.all { it.speakerIndex < speakerCount })
        require(
            turns.zipWithNext().all { (left, right) ->
                left.startSampleIndex <= right.startSampleIndex
            },
        ) {
            "speaker turns must be ordered by absolute start sample"
        }
    }
}

data class DiarizationProgress(
    val phase: DiarizationPhase,
    val processedSamples: Long? = null,
    val totalSamples: Long? = null,
) {
    init {
        require(processedSamples == null || processedSamples >= 0L)
        require(totalSamples == null || totalSamples >= 0L)
        if (processedSamples != null && totalSamples != null) {
            require(processedSamples <= totalSamples)
        }
    }

    val fraction: Double?
        get() =
            if (processedSamples != null && totalSamples != null && totalSamples > 0L) {
                processedSamples.toDouble() / totalSamples.toDouble()
            } else {
                null
            }
}

fun interface DiarizationProgressListener {
    suspend fun onProgress(progress: DiarizationProgress)
}

interface DiarizationEngine : Closeable {
    val segmentationModel: ModelDescriptor
    val embeddingModel: ModelDescriptor

    suspend fun diarize(
        window: DiarizationWindow,
        config: DiarizationConfig,
        progressListener: DiarizationProgressListener? = null,
    ): DiarizationChunkResult
}
