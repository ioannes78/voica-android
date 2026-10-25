package io.github.ioannes78.voica.localllm

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class LocalLlmRuntimeException(
    val failure: LocalLlmFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.message, cause)

/**
 * Process-local ownership boundary for heavy LiteRT-LM inference.
 *
 * Backend selection and resource policy are intentionally supplied by higher layers. This class is
 * responsible only for ensuring that one heavy attempt owns the runtime at a time and that all
 * success, cancellation and failure paths return ownership to IDLE.
 */
class LocalLlmRuntimeCoordinator(
    private val attempt:
        suspend (
            modelPath: String,
            request: LocalLlmGenerationRequest,
            backendResolution: LocalLlmBackendResolution,
            onPhase: (LocalLlmRuntimePhase) -> Unit,
        ) -> LocalLlmGenerationResult,
) {
    constructor(runtime: LiteRtLocalLlmRuntime) : this(
        attempt = { modelPath, request, backendResolution, onPhase ->
            runtime.generate(
                modelPath = modelPath,
                request = request,
                backendResolution = backendResolution,
                onPhase = onPhase,
            )
        },
    )

    private val ownerMutex = Mutex()
    private val _state = MutableStateFlow(LocalLlmRuntimeState.IDLE)
    val state: StateFlow<LocalLlmRuntimeState> = _state.asStateFlow()

    suspend fun generate(
        modelPath: String,
        request: LocalLlmGenerationRequest,
        backendResolution: LocalLlmBackendResolution,
    ): LocalLlmGenerationResult {
        if (!ownerMutex.tryLock()) {
            throw LocalLlmRuntimeException(
                LocalLlmFailure(
                    code = LocalLlmFailureCode.BUSY,
                    phase = LocalLlmFailurePhase.ACQUIRING,
                    message = "local LLM runtime is already owned by another heavy inference",
                    requestedPolicy = request.config.backendPolicy,
                    resolvedBackend = backendResolution.resolvedBackend,
                ),
            )
        }

        var lastPhase = LocalLlmFailurePhase.ACQUIRING
        _state.value = LocalLlmRuntimeState.ACQUIRING
        return try {
            attempt(
                modelPath,
                request,
                backendResolution,
            ) { phase ->
                lastPhase = phase.toFailurePhase()
                _state.value = phase.toCoordinatorState()
            }
        } catch (cancelled: CancellationException) {
            _state.value = LocalLlmRuntimeState.CANCELLING
            throw cancelled
        } catch (known: LocalLlmRuntimeException) {
            _state.value = LocalLlmRuntimeState.FAILED
            throw known
        } catch (error: Throwable) {
            _state.value = LocalLlmRuntimeState.FAILED
            throw LocalLlmRuntimeException(
                failure =
                    LocalLlmFailure(
                        code = lastPhase.defaultFailureCode(),
                        phase = lastPhase,
                        message = error.message ?: error::class.java.name,
                        requestedPolicy = request.config.backendPolicy,
                        resolvedBackend = backendResolution.resolvedBackend,
                        fallbackAttempted = backendResolution.fallbackAttempted,
                        causeClassName = error::class.java.name,
                    ),
                cause = error,
            )
        } finally {
            _state.value = LocalLlmRuntimeState.RELEASING
            ownerMutex.unlock()
            _state.value = LocalLlmRuntimeState.IDLE
        }
    }
}

private fun LocalLlmRuntimePhase.toCoordinatorState(): LocalLlmRuntimeState =
    when (this) {
        LocalLlmRuntimePhase.ENGINE_CONSTRUCTING,
        LocalLlmRuntimePhase.ENGINE_INITIALIZING,
        LocalLlmRuntimePhase.ENGINE_INITIALIZED,
        LocalLlmRuntimePhase.CONVERSATION_CREATING,
        -> LocalLlmRuntimeState.INITIALIZING

        LocalLlmRuntimePhase.CONVERSATION_READY -> LocalLlmRuntimeState.READY
        LocalLlmRuntimePhase.GENERATING -> LocalLlmRuntimeState.GENERATING
        LocalLlmRuntimePhase.GENERATED,
        LocalLlmRuntimePhase.ENGINE_CLOSED,
        -> LocalLlmRuntimeState.RELEASING
    }

private fun LocalLlmRuntimePhase.toFailurePhase(): LocalLlmFailurePhase =
    when (this) {
        LocalLlmRuntimePhase.ENGINE_CONSTRUCTING -> LocalLlmFailurePhase.ENGINE_CONSTRUCTING
        LocalLlmRuntimePhase.ENGINE_INITIALIZING,
        LocalLlmRuntimePhase.ENGINE_INITIALIZED,
        -> LocalLlmFailurePhase.ENGINE_INITIALIZING

        LocalLlmRuntimePhase.CONVERSATION_CREATING,
        LocalLlmRuntimePhase.CONVERSATION_READY,
        -> LocalLlmFailurePhase.CONVERSATION_CREATING

        LocalLlmRuntimePhase.GENERATING,
        LocalLlmRuntimePhase.GENERATED,
        -> LocalLlmFailurePhase.GENERATING

        LocalLlmRuntimePhase.ENGINE_CLOSED -> LocalLlmFailurePhase.RELEASING
    }

private fun LocalLlmFailurePhase.defaultFailureCode(): LocalLlmFailureCode =
    when (this) {
        LocalLlmFailurePhase.ENGINE_CONSTRUCTING,
        LocalLlmFailurePhase.ENGINE_INITIALIZING,
        -> LocalLlmFailureCode.ENGINE_INIT_FAILED

        LocalLlmFailurePhase.CONVERSATION_CREATING ->
            LocalLlmFailureCode.CONVERSATION_CREATE_FAILED

        LocalLlmFailurePhase.GENERATING -> LocalLlmFailureCode.GENERATION_FAILED
        else -> LocalLlmFailureCode.UNKNOWN
    }
