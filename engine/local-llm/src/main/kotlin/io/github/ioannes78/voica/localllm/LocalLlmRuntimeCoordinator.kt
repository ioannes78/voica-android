package io.github.ioannes78.voica.localllm

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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
 * Backend selection and resource policy are intentionally supplied by higher layers. This class
 * guarantees one heavy attempt at a time. R4-A also makes uncertain native release sticky for the
 * life of this process: once RELEASE_UNCONFIRMED is observed, no second CPU/GPU Engine may start.
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
    private val poisoned = AtomicBoolean(false)
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
        rejectIfPoisoned(request, backendResolution)

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

        // A prior owner can poison the runtime immediately before unlocking. Re-check after taking
        // the mutex so a racing caller never crosses the native ownership boundary.
        if (poisoned.get()) {
            ownerMutex.unlock()
            rejectIfPoisoned(request, backendResolution)
        }

        var lastPhase = LocalLlmFailurePhase.ACQUIRING
        var releaseUnconfirmed = false
        _generationProgress.value = LocalLlmGenerationProgress()
        _state.value = LocalLlmRuntimeState.ACQUIRING
        return try {
            attempt(
                modelPath,
                request,
                backendResolution,
            ) phaseCallback@{ phase ->
                // RELEASE_UNCONFIRMED is sticky for the life of this process. A late native
                // callback from a quarantined session must never move POISONED back to GENERATING.
                if (poisoned.get() && phase != LocalLlmRuntimePhase.RELEASE_UNCONFIRMED) {
                    return@phaseCallback
                }
                lastPhase = phase.toFailurePhase()
                if (phase == LocalLlmRuntimePhase.RELEASE_UNCONFIRMED) {
                    releaseUnconfirmed = true
                    poisoned.set(true)
                }
                updateGenerationProgress(phase)
                val progress = _generationProgress.value
                LocalLlmRuntimeDiagnosticObserverRegistry.emit(
                    LocalLlmRuntimeDiagnosticPhase(
                        phase = phase,
                        requestedPolicy = request.config.backendPolicy,
                        resolvedBackend = backendResolution.resolvedBackend,
                        schemaEnabled = request.config.responseJsonSchema != null,
                        schemaSizeChars = request.config.responseJsonSchema?.length ?: 0,
                        emissionCount = progress.emissionCount,
                    ),
                )
                _state.value = phase.toCoordinatorState()
            }
        } catch (cancelled: CancellationException) {
            if (!releaseUnconfirmed) {
                _state.value = LocalLlmRuntimeState.CANCELLING
            }
            throw cancelled
        } catch (known: LocalLlmRuntimeException) {
            if (!releaseUnconfirmed) {
                _state.value = LocalLlmRuntimeState.FAILED
            }
            throw known
        } catch (error: Throwable) {
            if (!releaseUnconfirmed) {
                _state.value = LocalLlmRuntimeState.FAILED
            }
            throw LocalLlmRuntimeException(
                failure =
                    LocalLlmFailure(
                        code =
                            if (releaseUnconfirmed) {
                                LocalLlmFailureCode.RUNTIME_POISONED
                            } else {
                                lastPhase.defaultFailureCode()
                            },
                        phase = lastPhase,
                        message =
                            if (releaseUnconfirmed) {
                                "LiteRT-LM native ownership release could not be confirmed"
                            } else {
                                error.message ?: error::class.java.name
                            },
                        requestedPolicy = request.config.backendPolicy,
                        resolvedBackend = backendResolution.resolvedBackend,
                        fallbackAttempted = backendResolution.fallbackAttempted,
                        causeClassName = error::class.java.name,
                    ),
                cause = error,
            )
        } finally {
            _generationProgress.value = _generationProgress.value.copy(active = false)
            if (releaseUnconfirmed || poisoned.get()) {
                poisoned.set(true)
                _state.value = LocalLlmRuntimeState.POISONED
            } else {
                _state.value = LocalLlmRuntimeState.RELEASING
            }

            ownerMutex.unlock()

            _state.value =
                if (poisoned.get()) {
                    LocalLlmRuntimeState.POISONED
                } else {
                    LocalLlmRuntimeState.IDLE
                }
        }
    }

    private fun rejectIfPoisoned(
        request: LocalLlmGenerationRequest,
        backendResolution: LocalLlmBackendResolution,
    ) {
        if (!poisoned.get()) return
        _state.value = LocalLlmRuntimeState.POISONED
        throw LocalLlmRuntimeException(
            LocalLlmFailure(
                code = LocalLlmFailureCode.RUNTIME_POISONED,
                phase = LocalLlmFailurePhase.ACQUIRING,
                message =
                    "local LLM runtime is poisoned because prior native ownership was not released; " +
                        "process restart is required before another heavy inference",
                requestedPolicy = request.config.backendPolicy,
                resolvedBackend = backendResolution.resolvedBackend,
            ),
        )
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
            LocalLlmRuntimePhase.CANCEL_REQUESTED,
            LocalLlmRuntimePhase.NATIVE_CANCEL_TERMINAL_RECEIVED,
            LocalLlmRuntimePhase.ENGINE_CLOSED,
            LocalLlmRuntimePhase.RELEASE_UNCONFIRMED,
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

        LocalLlmRuntimePhase.CANCEL_REQUESTED,
        LocalLlmRuntimePhase.NATIVE_CANCEL_SIGNAL_SENT,
        LocalLlmRuntimePhase.NATIVE_CANCEL_TERMINAL_RECEIVED,
        -> LocalLlmRuntimeState.CANCELLING

        LocalLlmRuntimePhase.GENERATED,
        LocalLlmRuntimePhase.CONVERSATION_CLOSING,
        LocalLlmRuntimePhase.CONVERSATION_CLOSED,
        LocalLlmRuntimePhase.ENGINE_CLOSING,
        LocalLlmRuntimePhase.ENGINE_CLOSED,
        -> LocalLlmRuntimeState.RELEASING

        LocalLlmRuntimePhase.RELEASE_UNCONFIRMED -> LocalLlmRuntimeState.POISONED
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
        LocalLlmRuntimePhase.CANCEL_REQUESTED,
        LocalLlmRuntimePhase.NATIVE_CANCEL_SIGNAL_SENT,
        LocalLlmRuntimePhase.NATIVE_CANCEL_TERMINAL_RECEIVED,
        -> LocalLlmFailurePhase.GENERATING

        LocalLlmRuntimePhase.CONVERSATION_CLOSING,
        LocalLlmRuntimePhase.CONVERSATION_CLOSED,
        LocalLlmRuntimePhase.ENGINE_CLOSING,
        LocalLlmRuntimePhase.ENGINE_CLOSED,
        LocalLlmRuntimePhase.RELEASE_UNCONFIRMED,
        -> LocalLlmFailurePhase.RELEASING
    }

private fun LocalLlmFailurePhase.defaultFailureCode(): LocalLlmFailureCode =
    when (this) {
        LocalLlmFailurePhase.ENGINE_CONSTRUCTING,
        LocalLlmFailurePhase.ENGINE_INITIALIZING,
        -> LocalLlmFailureCode.ENGINE_INIT_FAILED

        LocalLlmFailurePhase.CONVERSATION_CREATING ->
            LocalLlmFailureCode.CONVERSATION_CREATE_FAILED

        LocalLlmFailurePhase.GENERATING -> LocalLlmFailureCode.GENERATION_FAILED
        LocalLlmFailurePhase.RELEASING -> LocalLlmFailureCode.RUNTIME_POISONED
        else -> LocalLlmFailureCode.UNKNOWN
    }
