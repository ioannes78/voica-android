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
 * Non-content generation heartbeat. It intentionally stores timing/count metadata only and never
 * stores generated text, so background diagnostics can detect a native stall without leaking model
 * output into logs or process-global status surfaces.
 */
data class LocalLlmGenerationProgress(
    val active: Boolean = false,
    val startedAtNanos: Long? = null,
    val firstEmissionAtNanos: Long? = null,
    val lastEmissionAtNanos: Long? = null,
    val emissionCount: Long = 0L,
)

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
    private val _generationProgress = MutableStateFlow(LocalLlmGenerationProgress())
    val state: StateFlow<LocalLlmRuntimeState> = _state.asStateFlow()
    val generationProgress: StateFlow<LocalLlmGenerationProgress> =
        _generationProgress.asStateFlow()

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
        _generationProgress.value = LocalLlmGenerationProgress()
        _state.value = LocalLlmRuntimeState.ACQUIRING
        return try {
            attempt(
                modelPath,
                request,
                backendResolution,
            ) { phase ->
                lastPhase = phase.toFailurePhase()
                updateGenerationProgress(phase)
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
            _generationProgress.value = _generationProgress.value.copy(active = false)
            _state.value = LocalLlmRuntimeState.RELEASING
            ownerMutex.unlock()
            _state.value = LocalLlmRuntimeState.IDLE
        }
    }

    private fun updateGenerationProgress(phase: LocalLlmRuntimePhase) {
        when (phase) {
            LocalLlmRuntimePhase.GENERATING -> {
                val now = System.nanoTime()
                _generationProgress.value =
                    LocalLlmGenerationProgress(
                        active = true,
                        startedAtNanos = now,
                    )
            }

            LocalLlmRuntimePhase.GENERATION_PROGRESS -> {
                val now = System.nanoTime()
                val current = _generationProgress.value
                _generationProgress.value =
                    current.copy(
                        active = true,
                        startedAtNanos = current.startedAtNanos ?: now,
                        firstEmissionAtNanos = current.firstEmissionAtNanos ?: now,
                        lastEmissionAtNanos = now,
                        emissionCount = current.emissionCount + 1L,
                    )
            }

            LocalLlmRuntimePhase.GENERATED,
            LocalLlmRuntimePhase.ENGINE_CLOSED,
            -> _generationProgress.value = _generationProgress.value.copy(active = false)

            else -> Unit
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
        LocalLlmRuntimePhase.GENERATING,
        LocalLlmRuntimePhase.GENERATION_PROGRESS,
        -> LocalLlmRuntimeState.GENERATING

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
        LocalLlmRuntimePhase.GENERATION_PROGRESS,
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
