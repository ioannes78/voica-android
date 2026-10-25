package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.localllm.AndroidLocalLlmResourceProbe
import io.github.ioannes78.voica.localllm.LiteRtLmRuntimeContract
import io.github.ioannes78.voica.localllm.LiteRtLocalLlmRuntime
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmExecutionController
import io.github.ioannes78.voica.localllm.LocalLlmFailure
import io.github.ioannes78.voica.localllm.LocalLlmFailureCode
import io.github.ioannes78.voica.localllm.LocalLlmFailurePhase
import io.github.ioannes78.voica.localllm.LocalLlmGenerationRequest
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import io.github.ioannes78.voica.localllm.LocalLlmResourceDecision
import io.github.ioannes78.voica.localllm.LocalLlmResourceGate
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeCoordinator
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeState
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.flow.StateFlow

/** Application-facing Local LLM boundary shared by all future Stage 17 product features. */
class AppLocalLlmRuntime internal constructor(
    context: Context,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val coordinator: LocalLlmRuntimeCoordinator =
        LocalLlmRuntimeCoordinator(LiteRtLocalLlmRuntime()),
    private val resourceGate: LocalLlmResourceGate = LocalLlmResourceGate(),
    private val resourceProbe: AndroidLocalLlmResourceProbe =
        AndroidLocalLlmResourceProbe(context.applicationContext),
) {
    private val executionController = LocalLlmExecutionController(coordinator)

    val state: StateFlow<LocalLlmRuntimeState> = coordinator.state

    suspend fun generate(
        prompt: String,
        config: LocalLlmRuntimeConfig = LocalLlmRuntimeConfig(),
        resourceDecisionOverride: LocalLlmResourceDecision? = null,
    ): LocalLlmGenerationResult {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        val active =
            modelManager.activeModel(STAGE17_GEMMA_4_E2B_CANDIDATE_ID)
                ?: throw modelFailure(
                    code = LocalLlmFailureCode.MODEL_NOT_INSTALLED,
                    message = "Gemma 4 E2B is not installed and active",
                    policy = config.backendPolicy,
                )
        val resolved = resolveManagedLocalLlmModel(active, config.backendPolicy)
        val lease =
            modelUseRegistry.acquire(
                modelId = active.descriptor.modelId,
                version = active.descriptor.version,
                revision = active.descriptor.revision,
            )
        try {
            val request =
                LocalLlmGenerationRequest(
                    prompt = prompt,
                    modelIdentity = resolved.identity,
                    config = config,
                )
            val decision = resourceDecisionOverride ?: resourceGate.evaluate(resourceProbe.snapshot())
            return executionController.generate(
                modelPath = resolved.modelFile.absolutePath,
                request = request,
                resourceDecision = decision,
            )
        } finally {
            lease.close()
        }
    }

    private fun modelFailure(
        code: LocalLlmFailureCode,
        message: String,
        policy: LocalLlmBackendPolicy,
    ): LocalLlmRuntimeException =
        LocalLlmRuntimeException(
            LocalLlmFailure(
                code = code,
                phase = LocalLlmFailurePhase.MODEL_RESOLUTION,
                message = message,
                requestedPolicy = policy,
            ),
        )
}

internal data class ResolvedManagedLocalLlmModel(
    val identity: LocalLlmModelIdentity,
    val modelFile: File,
)

internal fun resolveManagedLocalLlmModel(
    active: ActiveModel,
    policy: LocalLlmBackendPolicy,
): ResolvedManagedLocalLlmModel {
    val descriptor = active.descriptor
    fun fail(
        code: LocalLlmFailureCode,
        message: String,
    ): Nothing =
        throw LocalLlmRuntimeException(
            LocalLlmFailure(
                code = code,
                phase = LocalLlmFailurePhase.MODEL_RESOLUTION,
                message = message,
                requestedPolicy = policy,
            ),
        )

    if (descriptor.kind != ModelKind.LOCAL_LLM ||
        descriptor.runtimeId != LiteRtLmRuntimeContract.RUNTIME_ID
    ) {
        fail(LocalLlmFailureCode.MODEL_INCOMPATIBLE, "active model is not a LiteRT-LM LOCAL_LLM")
    }
    val installedDirectory =
        active.installedDirectory
            ?: fail(LocalLlmFailureCode.MODEL_NOT_INSTALLED, "managed Local LLM has no installed directory")
    val fileDescriptor =
        descriptor.files.singleOrNull {
            it.relativePath.endsWith(LiteRtLmRuntimeContract.MODEL_EXTENSION, ignoreCase = true)
        } ?: fail(
            LocalLlmFailureCode.MODEL_CORRUPT,
            "managed Local LLM must contain exactly one .litertlm artifact",
        )
    val root = installedDirectory.canonicalFile
    val modelFile = File(root, fileDescriptor.relativePath).canonicalFile
    if (!modelFile.path.startsWith(root.path + File.separator) ||
        !modelFile.isFile ||
        modelFile.length() != fileDescriptor.sizeBytes
    ) {
        fail(LocalLlmFailureCode.MODEL_CORRUPT, "managed Local LLM artifact is missing or has invalid size")
    }

    return ResolvedManagedLocalLlmModel(
        identity =
            LocalLlmModelIdentity(
                modelId = descriptor.modelId,
                modelFamily = descriptor.displayName,
                artifact = fileDescriptor.relativePath,
                revision = descriptor.version + ":r" + descriptor.revision,
                sha256 = fileDescriptor.sha256,
                runtimeId = descriptor.runtimeId,
                runtimeVersion = LiteRtLmRuntimeContract.RUNTIME_VERSION,
            ),
        modelFile = modelFile,
    )
}

/**
 * One application/container-scoped runtime per process without adding another native Engine owner.
 * The WeakHashMap is keyed by the existing AppContainer, so Activity recreation reuses the same
 * coordinator while a replaced application/container cannot leak forever.
 */
private object AppLocalLlmRuntimeRegistry {
    private val byContainer = WeakHashMap<AppContainer, AppLocalLlmRuntime>()

    @Synchronized
    fun get(
        container: AppContainer,
        context: Context,
    ): AppLocalLlmRuntime =
        byContainer.getOrPut(container) {
            AppLocalLlmRuntime(
                context = context.applicationContext,
                modelManager = container.modelManager,
                modelUseRegistry = container.modelUseRegistry,
            )
        }
}

internal fun AppContainer.localLlmRuntime(context: Context): AppLocalLlmRuntime =
    AppLocalLlmRuntimeRegistry.get(this, context)
