package io.github.ioannes78.voica

import android.content.res.AssetManager
import io.github.ioannes78.voica.fsmn.FsmnVadEngine
import io.github.ioannes78.voica.fsmn.FsmnVadModelLocation
import io.github.ioannes78.voica.fsmn.FsmnVadSettings
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.transcript.VadEngineFactory
import java.io.File

/**
 * Stage 16B production Local File VAD policy.
 *
 * Local file transcription uses FSMN first and falls back to the existing Silero production VAD
 * only when FSMN is not active/available. TEN remains candidate-only until its deployment-license
 * applicability is resolved and/or Stage 16C.1 reevaluates realtime VADs.
 */
internal object Stage16BLocalFileVadPolicy {
    const val PRIMARY_MODEL_ID = STAGE16B_FSMN_VAD_CANDIDATE_ID
    const val FALLBACK_MODEL_ID = Stage8ModelIds.VAD

    val orderedModelIds: List<String> = listOf(PRIMARY_MODEL_ID, FALLBACK_MODEL_ID)
}

/**
 * A narrow ModelManager view used only by TranscriptionCoordinator.
 *
 * The legacy coordinator requests Stage8ModelIds.VAD. This adapter resolves that request to the
 * Stage 16B local-file policy without changing the underlying global model manager, so Stage 16A
 * realtime keeps using its existing Silero binding.
 */
internal class Stage16BLocalFileVadModelManager(
    private val delegate: ModelManager,
) : ModelManager by delegate {
    override suspend fun activeModel(modelId: String): ActiveModel? {
        if (modelId != Stage8ModelIds.VAD) return delegate.activeModel(modelId)

        for (candidateId in Stage16BLocalFileVadPolicy.orderedModelIds) {
            val active = delegate.activeModel(candidateId)
            if (active != null) return active
        }
        throw MissingTranscriptionModelsException(
            Stage16BLocalFileVadPolicy.orderedModelIds,
        )
    }
}

/**
 * Local-file VAD engine adapter. Non-FSMN factories stay delegated to the frozen Stage 8 provider;
 * that includes the existing Silero fallback implementation.
 */
internal class Stage16BLocalFileTranscriptionEngineProvider(
    private val delegate: Stage8TranscriptionEngineProvider,
    private val assetManager: AssetManager,
) : Stage8TranscriptionEngineProvider by delegate {
    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory =
        when (model.descriptor.modelId) {
            Stage16BLocalFileVadPolicy.PRIMARY_MODEL_ID ->
                VadEngineFactory {
                    val directory =
                        model.installedDirectory
                            ?: error("FSMN VAD must be an installed managed model")
                    FsmnVadEngine(
                        model = model.descriptor,
                        modelLocation =
                            FsmnVadModelLocation(
                                modelPath = File(directory, FSMN_MODEL_FILE).canonicalPath,
                                cmvnPath = File(directory, FSMN_CMVN_FILE).canonicalPath,
                            ),
                        settings =
                            FsmnVadSettings(
                                numThreads = numThreads,
                                maxSpeechDurationSeconds = vadSettings.maxSpeechDurationSeconds,
                            ),
                    )
                }

            else -> delegate.vadFactory(model, numThreads, vadSettings)
        }

    private companion object {
        const val FSMN_MODEL_FILE = "model_quant.onnx"
        const val FSMN_CMVN_FILE = "vad.mvn"
    }
}
