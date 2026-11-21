package io.github.ioannes78.voica.localllm

import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import java.io.File
import java.util.concurrent.CancellationException

object LiteRtLmRuntimeContract {
    const val RUNTIME_ID = "litert-lm"
    const val RUNTIME_VERSION = "0.18.0"
    const val MODEL_EXTENSION = ".litertlm"
}

enum class LiteRtLmModelValidationStage {
    NATIVE_INIT,
}

class LiteRtLmModelValidationException(
    val stage: LiteRtLmModelValidationStage,
    cause: Throwable,
) : IllegalStateException(
    stage.name + ": " + (cause.message ?: cause::class.java.simpleName),
    cause,
)

/** Runtime validation for managed LiteRT-LM artifacts before ModelManager activation. */
class LiteRtLmModelCandidateValidator(
    private val runtimeValidate: suspend (String) -> Unit = { modelPath ->
        LiteRtLocalLlmRuntime().validateModel(modelPath)
    },
) : ModelCandidateValidator {
    override suspend fun validate(
        descriptor: ModelDescriptor,
        installedDirectory: File,
    ) {
        require(descriptor.kind == ModelKind.LOCAL_LLM) {
            "LiteRT-LM validator requires a LOCAL_LLM descriptor"
        }
        require(descriptor.runtimeId == LiteRtLmRuntimeContract.RUNTIME_ID) {
            "LiteRT-LM validator requires runtimeId=${LiteRtLmRuntimeContract.RUNTIME_ID}"
        }
        require(installedDirectory.isDirectory) {
            "LiteRT-LM candidate directory is missing"
        }

        val relativePath =
            descriptor.files
                .map { it.relativePath }
                .singleOrNull {
                    it.endsWith(LiteRtLmRuntimeContract.MODEL_EXTENSION, ignoreCase = true)
                }
                ?: error("LiteRT-LM candidate must contain exactly one .litertlm artifact")
        val root = installedDirectory.canonicalFile
        val modelFile = File(root, relativePath).canonicalFile
        require(modelFile.path.startsWith(root.path + File.separator)) {
            "LiteRT-LM artifact escapes candidate directory"
        }
        require(modelFile.isFile) {
            "LiteRT-LM artifact is missing: $relativePath"
        }

        try {
            runtimeValidate(modelFile.absolutePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (error: LiteRtLmModelValidationException) {
            throw error
        } catch (error: Throwable) {
            throw LiteRtLmModelValidationException(
                LiteRtLmModelValidationStage.NATIVE_INIT,
                error,
            )
        }
    }
}
