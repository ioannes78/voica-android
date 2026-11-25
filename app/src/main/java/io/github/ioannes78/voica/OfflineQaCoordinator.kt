package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.ai.*
import io.github.ioannes78.voica.database.OfflineQaEvidenceResolver
import io.github.ioannes78.voica.database.OfflineQaSourceException
import io.github.ioannes78.voica.localllm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface OfflineQaOutcome {
    data class Answer(val result: OfflineQaResult) : OfflineQaOutcome
    data class Failed(val code: OfflineQaFailureCode) : OfflineQaOutcome
}

/** Caller-owned suspend task. No UI, persistence, native owner or cloud transport. */
internal class OfflineQaCoordinator(
    private val admission: OfflineAiTaskAdmission,
    private val requireCurrent: suspend (OfflineQaSourceIdentity) -> OfflineQaSnapshot,
    private val verifyModel: suspend () -> LocalLlmModelIdentity,
    private val generate: suspend (String, LocalLlmRuntimeConfig) -> LocalLlmGenerationResult,
    private val runtimeState: () -> LocalLlmRuntimeState,
    private val criticalTaskReason: () -> String?,
    private val retrieval: OfflineQaRetrieval = OfflineQaRetrieval(),
    private val packer: OfflineQaContextPacker = OfflineQaContextPacker(),
) {
    suspend fun ask(
        request: OfflineQaRequest,
        history: List<OfflineQaHistoryTurn> = emptyList(),
    ): OfflineQaOutcome {
        val lease = admission.tryAcquire(OfflineAiTaskAdmission.Kind.QA, request.source.recordingId)
            ?: return OfflineQaOutcome.Failed(OfflineQaFailureCode.RUNTIME_BUSY)
        try {
            currentCoroutineContext().ensureActive()
            checkAdmission()
            val snapshot = requireCurrent(request.source)
            if (snapshot.source != request.source || history.any { it.result.context.request.source != request.source }) {
                return OfflineQaOutcome.Failed(OfflineQaFailureCode.SOURCE_CHANGED)
            }
            val context = retrieval.retrieve(request, snapshot, history)
            val packed = packer.pack(snapshot, context, history)
            val result = when (packed) {
                OfflineQaPackingResult.NoCandidates ->
                    OfflineQaResult(context, OfflineQaStatus.INSUFFICIENT_EVIDENCE, emptyList())
                OfflineQaPackingResult.BudgetExceeded ->
                    return OfflineQaOutcome.Failed(OfflineQaFailureCode.INPUT_TOO_LARGE)
                is OfflineQaPackingResult.Ready -> {
                    val model = verifyModel()
                    Stage172OfflineSummaryModelContract.requireMatches(model)
                    requireCurrentIdentity(request.source)
                    currentCoroutineContext().ensureActive()
                    checkAdmission()
                    val generated = generate(packed.prompt, LocalLlmRuntimeConfig(
                        backendPolicy = LocalLlmBackendPolicy.AUTO,
                        maxNumTokens = OfflineQaPromptContract.RUNTIME_MAX_TOKENS,
                        maxOutputTokens = OfflineQaPromptContract.MAX_OUTPUT_TOKENS,
                        speculativeDecoding = false,
                        responseJsonSchema = packed.responseJsonSchema,
                    ))
                    checkRuntimeIdle()
                    if (generated.modelIdentity != model ||
                        generated.backendResolution.resolvedBackend != LocalLlmBackend.CPU ||
                        generated.backendResolution.fallbackAttempted
                    ) return OfflineQaOutcome.Failed(OfflineQaFailureCode.MODEL_NOT_READY)
                    OfflineQaResponseCodec.decode(generated.text, packed.context)
                }
            }
            requireCurrentIdentity(request.source)
            currentCoroutineContext().ensureActive()
            return OfflineQaOutcome.Answer(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OfflineQaSourceException) {
            return OfflineQaOutcome.Failed(error.code)
        } catch (error: OfflineQaOutputException) {
            return OfflineQaOutcome.Failed(
                if (error.code == OfflineQaOutputError.INVALID_EVIDENCE || error.code == OfflineQaOutputError.NUMERIC_MISMATCH)
                    OfflineQaFailureCode.INVALID_EVIDENCE else OfflineQaFailureCode.INVALID_OUTPUT,
            )
        } catch (error: OfflineSummaryCoordinatorException) {
            return OfflineQaOutcome.Failed(OfflineQaFailureCode.MODEL_NOT_READY)
        } catch (error: LocalLlmRuntimeException) {
            val code = if (runtimeState() == LocalLlmRuntimeState.POISONED) OfflineQaFailureCode.RUNTIME_POISONED else when (error.failure.code) {
                LocalLlmFailureCode.BUSY -> OfflineQaFailureCode.RUNTIME_BUSY
                LocalLlmFailureCode.MODEL_NOT_INSTALLED -> OfflineQaFailureCode.MODEL_NOT_INSTALLED
                LocalLlmFailureCode.MODEL_CORRUPT, LocalLlmFailureCode.MODEL_INCOMPATIBLE -> OfflineQaFailureCode.MODEL_NOT_READY
                LocalLlmFailureCode.INSUFFICIENT_MEMORY, LocalLlmFailureCode.THERMAL_BLOCKED,
                LocalLlmFailureCode.BACKEND_UNSUPPORTED -> OfflineQaFailureCode.RESOURCE_BLOCKED
                LocalLlmFailureCode.CANCELLED -> OfflineQaFailureCode.CANCELLED
                else -> OfflineQaFailureCode.GENERATION_FAILED
            }
            return OfflineQaOutcome.Failed(code)
        } catch (error: AdmissionFailure) {
            return OfflineQaOutcome.Failed(error.code)
        } catch (_: Exception) {
            return OfflineQaOutcome.Failed(OfflineQaFailureCode.GENERATION_FAILED)
        } finally {
            admission.release(lease)
        }
    }

    private suspend fun requireCurrentIdentity(expected: OfflineQaSourceIdentity) {
        if (requireCurrent(expected).source != expected) throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
    }

    private fun checkAdmission() {
        checkRuntimeIdle()
        if (criticalTaskReason() != null) throw AdmissionFailure(OfflineQaFailureCode.RESOURCE_BLOCKED)
    }

    private fun checkRuntimeIdle() {
        when (runtimeState()) {
            LocalLlmRuntimeState.IDLE -> Unit
            LocalLlmRuntimeState.POISONED -> throw AdmissionFailure(OfflineQaFailureCode.RUNTIME_POISONED)
            else -> throw AdmissionFailure(OfflineQaFailureCode.RUNTIME_BUSY)
        }
    }

    private class AdmissionFailure(val code: OfflineQaFailureCode) : IllegalStateException()
}

internal fun createOfflineQaCoordinator(
    context: Context,
    container: AppContainer,
    resolver: OfflineQaEvidenceResolver,
): OfflineQaCoordinator {
    val runtime = container.localLlmRuntime(context)
    return OfflineQaCoordinator(
        admission = container.offlineAiTaskAdmission,
        requireCurrent = resolver::requireCurrent,
        verifyModel = {
            val active = container.modelManager.activeModel(Stage172OfflineSummaryModelContract.MODEL_ID)
                ?: throw OfflineQaSourceException(OfflineQaFailureCode.MODEL_NOT_INSTALLED)
            resolveManagedLocalLlmModel(active, LocalLlmBackendPolicy.AUTO).identity
        },
        generate = { prompt, config -> runtime.generate(prompt, config) },
        runtimeState = { runtime.state.value },
        criticalTaskReason = { container.offlineSummaryCriticalTaskReason() },
    )
}
