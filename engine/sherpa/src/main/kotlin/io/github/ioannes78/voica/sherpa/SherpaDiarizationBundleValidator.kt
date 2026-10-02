package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationWindow
import java.io.File

class SherpaDiarizationBundleValidator {
    suspend fun validate(
        segmentationModel: ModelDescriptor,
        segmentationDirectory: File,
        embeddingModel: ModelDescriptor,
        embeddingDirectory: File,
        smokeSamples: ShortArray = defaultSmokeSamples(),
    ) {
        require(smokeSamples.isNotEmpty())
        val engine =
            SherpaOfflineDiarizationEngine(
                segmentationModel = segmentationModel,
                segmentationModelDirectory = segmentationDirectory,
                embeddingModel = embeddingModel,
                embeddingModelDirectory = embeddingDirectory,
            )
        try {
            engine.diarize(
                window =
                    DiarizationWindow(
                        startSampleIndex = 0L,
                        samples = smokeSamples,
                    ),
                config = DiarizationConfig(),
            )
        } finally {
            engine.close()
        }
    }

    private companion object {
        fun defaultSmokeSamples(): ShortArray =
            ShortArray(5 * DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ) { index ->
                val phase = (index / 80) % 4
                when (phase) {
                    0 -> 1_200
                    1 -> 400
                    2 -> -1_200
                    else -> -400
                }.toShort()
            }
    }
}
