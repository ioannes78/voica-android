package io.github.ioannes78.voica.fsmn

import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.Stage16BCandidateRuntimes
import io.github.ioannes78.voica.transcript.ExternalVadEngineProvider
import io.github.ioannes78.voica.transcript.VadEngine
import java.io.File

/** Candidate-only provider loaded reflectively by Debug/QA builds. */
class FsmnVadEngineProvider : ExternalVadEngineProvider {
    override fun open(
        model: ModelDescriptor,
        installedDirectory: File,
        numThreads: Int,
        maxSpeechDurationSeconds: Float,
    ): VadEngine {
        require(model.kind == ModelKind.VAD)
        require(model.runtimeId == Stage16BCandidateRuntimes.FSMN_VAD_ID)
        require(installedDirectory.isDirectory)
        return FsmnVadEngine(
            model = model,
            modelLocation =
                FsmnVadModelLocation(
                    modelPath = File(installedDirectory, MODEL_FILE).canonicalPath,
                    cmvnPath = File(installedDirectory, CMVN_FILE).canonicalPath,
                ),
            settings =
                FsmnVadSettings(
                    numThreads = numThreads,
                    maxSpeechDurationSeconds = maxSpeechDurationSeconds,
                ),
        )
    }

    private companion object {
        const val MODEL_FILE = "model_quant.onnx"
        const val CMVN_FILE = "vad.mvn"
    }
}
