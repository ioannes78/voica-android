package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.OfflineSummaryBudgetExceededException
import io.github.ioannes78.voica.ai.OfflineSummaryDirectBudgetGate
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateException
import io.github.ioannes78.voica.ai.OfflineSummaryIntermediateResponseFormat
import io.github.ioannes78.voica.ai.OfflineSummaryLongPlanningException
import io.github.ioannes78.voica.ai.OfflineSummaryLongRouter
import io.github.ioannes78.voica.ai.OfflineSummaryPromptRenderer
import io.github.ioannes78.voica.ai.OfflineSummaryRepairPromptRenderer
import io.github.ioannes78.voica.ai.OfflineSummaryResponseFormat
import io.github.ioannes78.voica.ai.OfflineSummaryRoute
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.SummaryStructuredOutputErrorCode
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.database.OfflineSummaryEvidenceMapper
import io.github.ioannes78.voica.database.OfflineSummaryEvidenceMappingException
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceErrorCode
import io.github.ioannes78.voica.database.OfflineSummaryLongPersistenceException
import io.github.ioannes78.voica.database.OfflineSummaryPersistenceRepository
import io.github.ioannes78.voica.localllm.LiteRtLmRuntimeContract
import io.github.ioannes78.voica.localllm.LocalLlmBackendPolicy
import io.github.ioannes78.voica.localllm.LocalLlmFailureCode
import io.github.ioannes78.voica.localllm.LocalLlmGenerationResult
import io.github.ioannes78.voica.localllm.LocalLlmModelIdentity
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeConfig
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeException
import io.github.ioannes78.voica.protocol.RecordingStatus
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Product run phase for the single Stage 17 offline-summary coordinator. */
enum class OfflineSummaryRunPhase {
    PREPARING,
    ANALYZING,
    VALIDATING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class OfflineSummaryRunState(
    val recordingId: String,
    val summaryId: String,
    val phase: OfflineSummaryRunPhase,
    val failureCode: OfflineSummaryFailureCode? = null,
    val message: String? = null,
    val estimatedPromptTokens: Int? = null,
    val resolvedBackend: String? = null,
    val fallbackAttempted: Boolean = false,
    val repairAttempted: Boolean = false,
    val warning: String? = null,
)

sealed interface OfflineSummaryStartOutcome {
    data class Started(
        val summaryId: String,
    ) : OfflineSummaryStartOutcome

    data class Rejected(
        val code: OfflineSummaryFailureCode,
        val message: String,
    ) : OfflineSummaryStartOutcome
}

/** Narrow persistence port keeps coordinator tests independent of Room. */
internal interface OfflineSummaryPersistencePort {
    suspend fun create(
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
    ): String

    suspend fun markPreparing(summaryId: String)

    suspend fun markAnalyzing(summaryId: String)

    suspend fun markValidating(summaryId: String)

    suspend fun complete(
        summaryId: String,
        result: AiSummaryResult,
        evidence: List<OfflineSummaryEvidencePersistenceDraft>,
    )

    suspend fun fail(
        summaryId: String,
        code: OfflineSummaryFailureCode,
        sanitizedMessage: String?,
    )

    suspend fun cancel(summaryId: String)

    suspend fun interruptAllActive(): Int
}

private class RoomOfflineSummaryPersistencePort(
    private val delegate: OfflineSummaryPersistenceRepository,
) : OfflineSummaryPersistencePort {
    override suspend fun create(
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
    ): String = delegate.create(input, modelIdentitySnapshot).id

    override suspend fun markPreparing(summaryId: String) {
        delegate.markPreparing(summaryId)
    }

    override suspend fun markAnalyzing(summaryId: String) {
        delegate.markAnalyzing(summaryId)
    }

    override suspend fun markValidating(summaryId: String) {
        delegate.markValidating(summaryId)
    }

    override suspend fun complete(
        summaryId: String,
        result: AiSummaryResult,
        evidence: List<OfflineSummaryEvidencePersistenceDraft>,
    ) {
        delegate.complete(summaryId, result, evidence)
    }

    override suspend fun fail(
        summaryId: String,
        code: OfflineSummaryFailureCode,
        sanitizedMessage: String?,
    ) {
        delegate.fail(summaryId, code, sanitizedMessage)
    }

    override suspend fun cancel(summaryId: String) {
        delegate.cancel(summaryId)
    }

    override suspend fun interruptAllActive(): Int = delegate.interruptAllActive().size
}

/** Exact P0/P1 model lock retained by both Direct and Long routes. */
internal object Stage172OfflineSummaryModelContract {
    const val MODEL_ID = "gemma-4-e2b-it-litertlm"
    const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db:r1"
    const val ARTIFACT = "gemma-4-E2B-it.litertlm"
    const val ARTIFACT_SHA256 =
        "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"

    val persistedIdentitySnapshot: String =
        listOf(
            "modelId=$MODEL_ID",
            "modelRevision=$MODEL_REVISION",
            "artifact=$ARTIFACT",
            "artifactSha256=$ARTIFACT_SHA256",
            "runtimeId=${LiteRtLmRuntimeContract.RUNTIME_ID}",
            "runtimeVersion=${LiteRtLmRuntimeContract.RUNTIME_VERSION}",
        ).joinToString(";")

    fun requireMatches(identity: LocalLlmModelIdentity) {
        if (
            identity.modelId != MODEL_ID ||
            identity.revision != MODEL_REVISION ||
            identity.artifact != ARTIFACT ||
            !identity.sha256.equals(ARTIFACT_SHA256, ignoreCase = true) ||
            identity.runtimeId != LiteRtLmRuntimeContract.RUNTIME_ID ||
            identity.runtimeVersion != LiteRtLmRuntimeContract.RUNTIME_VERSION
        ) {
            throw OfflineSummaryCoordinatorException(
                code = OfflineSummaryFailureCode.MODEL_NOT_READY,
                message = "当前本地 AI 模型不符合 Stage 17 已验证的 Gemma 4 E2B 版本",
            )
        }
    }
}

/** Same runtime envelope for Direct, MAP, REDUCE and FINAL structured requests. */
internal object OfflineSummaryLocalLlmPreset {
    fun configFor(allowedEvidenceRefs: Set<String>): LocalLlmRuntimeConfig =
        config(OfflineSummaryResponseFormat.jsonSchema(allowedEvidenceRefs))

    fun configForIntermediate(
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
    ): LocalLlmRuntimeConfig =
        config(
            OfflineSummaryIntermediateResponseFormat.jsonSchema(
                allowedSourceRefs = allowedSourceRefs,
                allowedEvidenceRefs = allowedEvidenceRefs,
            ),
        )

    private fun config(responseJsonSchema: String): LocalLlmRuntimeConfig =
        LocalLlmRuntimeConfig(
            backendPolicy = LocalLlmBackendPolicy.AUTO,
            maxNumTokens = 4_000,
            maxOutputTokens = 1_024,
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            speculativeDecoding = false,
            responseJsonSchema = responseJsonSchema,
        )
}

internal class OfflineSummaryCoordinatorException(
    val code: OfflineSummaryFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private object DisabledOfflineSummaryLongRunner : OfflineSummaryLongRunnerPort {
    private fun unsupported(): Nothing = error("long-summary runner is not configured")

    override suspend fun executeNew(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        directPromptEstimatedTokens: Int,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult = unsupported()

    override suspend fun prepareResume(summaryId: String, input: StructuredTranscriptInput): OfflineSummaryRunPhase =
        unsupported()

    override suspend fun executeResume(
        summaryId: String,
        input: StructuredTranscriptInput,
        preparedIdentity: LocalLlmModelIdentity,
        onProgress: (OfflineSummaryLongProgressUpdate) -> Unit,
    ): OfflineSummaryLongRunResult = unsupported()

    override suspend fun fail(summaryId: String, code: OfflineSummaryFailureCode, message: String?): Boolean = false

    override suspend fun cancel(summaryId: String): Boolean = false

    override suspend fun reconcileOnStartup(): Int = 0
}

/**
 * Single application-level Stage 17 summary owner. Direct and Long routes share one ownership lock,
 * one application scope and the same AppLocalLlmRuntime generate function. The coordinator never
 * creates a LiteRT-LM Engine/Conversation and never owns backend selection.
 */
class OfflineSummaryCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val resolveInput: suspend (recordingId: String) -> StructuredTranscriptInput,
    private val verifyModelReady: suspend () -> LocalLlmModelIdentity,
    private val generateLocal:
        suspend (prompt: String, config: LocalLlmRuntimeConfig) -> LocalLlmGenerationResult,
    private val persistence: OfflineSummaryPersistencePort,
    private val onCompleted: suspend (recordingId: String, summaryId: String) -> Unit,
    private val criticalTaskReason: () -> String?,
    private val longRunner: OfflineSummaryLongRunnerPort = DisabledOfflineSummaryLongRunner,
    private val longRouter: OfflineSummaryLongRouter = OfflineSummaryLongRouter(),
    private val promptRenderer: OfflineSummaryPromptRenderer = OfflineSummaryPromptRenderer(),
    private val repairPromptRenderer: OfflineSummaryRepairPromptRenderer = OfflineSummaryRepairPromptRenderer(),
    private val budgetGate: OfflineSummaryDirectBudgetGate = OfflineSummaryDirectBudgetGate(),
) {
    private val ownershipMutex = Mutex()
    private val activeRecordingIds = mutableSetOf<String>()
    private val activeJobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow<Map<String, OfflineSummaryRunState>>(emptyMap())

    val state: StateFlow<Map<String, OfflineSummaryRunState>> = mutableState.asStateFlow()

    suspend fun start(recordingId: String): OfflineSummaryStartOutcome {
        val normalizedRecordingId = recordingId.trim()
        require(normalizedRecordingId.isNotEmpty()) { "recordingId must not be blank" }

        criticalTaskReason()?.let { reason ->
            return OfflineSummaryStartOutcome.Rejected(OfflineSummaryFailureCode.RESOURCE_BLOCKED, reason)
        }
        if (!acquireOwnership(normalizedRecordingId)) {
            return OfflineSummaryStartOutcome.Rejected(
                OfflineSummaryFailureCode.RUNTIME_BUSY,
                "该录音的离线总结正在生成",
            )
        }

        var createdSummaryId: String? = null
        return try {
            val input = resolveAndValidateInput(normalizedRecordingId)
            val summaryId =
                persistence.create(
                    input = input,
                    modelIdentitySnapshot = Stage172OfflineSummaryModelContract.persistedIdentitySnapshot,
                )
            createdSummaryId = summaryId
            persistence.markPreparing(summaryId)
            putState(
                OfflineSummaryRunState(
                    recordingId = normalizedRecordingId,
                    summaryId = summaryId,
                    phase = OfflineSummaryRunPhase.PREPARING,
                ),
            )
            startOwnedJob(normalizedRecordingId) {
                executeNew(
                    recordingId = normalizedRecordingId,
                    summaryId = summaryId,
                    input = input,
                )
            }
            OfflineSummaryStartOutcome.Started(summaryId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseOwnership(normalizedRecordingId) }
            throw cancelled
        } catch (error: Throwable) {
            val failure = classifyFailure(error)
            createdSummaryId?.let { summaryId ->
                runCatching { persistence.fail(summaryId, failure.code, failure.message) }
                putFailedState(normalizedRecordingId, summaryId, failure)
            }
            withContext(NonCancellable) { releaseOwnership(normalizedRecordingId) }
            OfflineSummaryStartOutcome.Rejected(failure.code, failure.message)
        }
    }

    /** Explicit resume only. Startup reconciliation never calls this path. */
    suspend fun resume(
        recordingId: String,
        summaryId: String,
    ): OfflineSummaryStartOutcome {
        val normalizedRecordingId = recordingId.trim()
        val normalizedSummaryId = summaryId.trim()
        require(normalizedRecordingId.isNotEmpty()) { "recordingId must not be blank" }
        require(normalizedSummaryId.isNotEmpty()) { "summaryId must not be blank" }

        criticalTaskReason()?.let { reason ->
            return OfflineSummaryStartOutcome.Rejected(OfflineSummaryFailureCode.RESOURCE_BLOCKED, reason)
        }
        if (!acquireOwnership(normalizedRecordingId)) {
            return OfflineSummaryStartOutcome.Rejected(
                OfflineSummaryFailureCode.RUNTIME_BUSY,
                "该录音的离线总结正在运行",
            )
        }

        return try {
            val input = resolveAndValidateInput(normalizedRecordingId)
            val phase = longRunner.prepareResume(normalizedSummaryId, input)
            putState(
                OfflineSummaryRunState(
                    recordingId = normalizedRecordingId,
                    summaryId = normalizedSummaryId,
                    phase = phase,
                    message = "从已验证检查点继续",
                ),
            )
            startOwnedJob(normalizedRecordingId) {
                executeResume(
                    recordingId = normalizedRecordingId,
                    summaryId = normalizedSummaryId,
                    input = input,
                )
            }
            OfflineSummaryStartOutcome.Started(normalizedSummaryId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseOwnership(normalizedRecordingId) }
            throw cancelled
        } catch (error: Throwable) {
            val failure = classifyFailure(error)
            putFailedState(normalizedRecordingId, normalizedSummaryId, failure)
            withContext(NonCancellable) { releaseOwnership(normalizedRecordingId) }
            OfflineSummaryStartOutcome.Rejected(failure.code, failure.message)
        }
    }

    suspend fun cancel(recordingId: String): Boolean {
        val normalizedRecordingId = recordingId.trim()
        if (normalizedRecordingId.isEmpty()) return false
        val job = ownershipMutex.withLock { activeJobs[normalizedRecordingId] } ?: return false
        job.cancel(CancellationException("USER_CANCELLED_OFFLINE_SUMMARY"))
        return true
    }

    /** Long checkpoints reconcile first; no startup path invokes local inference. */
    suspend fun reconcileOnStartup(): Int {
        val longCount = longRunner.reconcileOnStartup()
        return longCount + persistence.interruptAllActive()
    }

    private suspend fun executeNew(
        recordingId: String,
        summaryId: String,
        input: StructuredTranscriptInput,
    ) {
        var route: OfflineSummaryRoute? = null
        var completedPersisted = false
        var estimatedPromptTokens: Int? = null
        var generation: LocalLlmGenerationResult? = null
        var repairAttempted = false
        var completionWarning: String? = null
        try {
            requireResourcesAvailable()
            val preparedIdentity = verifyModelReady()
            Stage172OfflineSummaryModelContract.requireMatches(preparedIdentity)

            val routeDecision = longRouter.decide(input)
            route = routeDecision.route
            estimatedPromptTokens = routeDecision.directBudget.estimatedPromptTokens
            if (routeDecision.route == OfflineSummaryRoute.LONG) {
                val longResult =
                    longRunner.executeNew(
                        summaryId = summaryId,
                        input = input,
                        preparedIdentity = preparedIdentity,
                        directPromptEstimatedTokens = routeDecision.directBudget.estimatedPromptTokens,
                        onProgress = { progress ->
                            putState(
                                OfflineSummaryRunState(
                                    recordingId = recordingId,
                                    summaryId = summaryId,
                                    phase = progress.phase,
                                    message = progress.message,
                                    estimatedPromptTokens = estimatedPromptTokens,
                                ),
                            )
                        },
                    )
                generation = longResult.finalGeneration
                repairAttempted = longResult.repairAttempted
                completionWarning = longResult.warning
                completedPersisted = true
                publishCompleted(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    estimatedPromptTokens = estimatedPromptTokens,
                    generation = longResult.finalGeneration,
                    repairAttempted = repairAttempted,
                    initialWarning = completionWarning,
                )
                return
            }

            val prompt = promptRenderer.render(input)
            val budget = budgetGate.requireFits(prompt)
            estimatedPromptTokens = budget.estimatedPromptTokens
            persistence.markAnalyzing(summaryId)
            putState(
                OfflineSummaryRunState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    phase = OfflineSummaryRunPhase.ANALYZING,
                    estimatedPromptTokens = estimatedPromptTokens,
                ),
            )

            val generated =
                generateLocal(
                    prompt.text,
                    OfflineSummaryLocalLlmPreset.configFor(prompt.allowedEvidenceRefs),
                )
            generation = generated
            requireGenerationIdentity(generated, preparedIdentity)

            persistence.markValidating(summaryId)
            putState(
                OfflineSummaryRunState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    phase = OfflineSummaryRunPhase.VALIDATING,
                    estimatedPromptTokens = estimatedPromptTokens,
                    resolvedBackend = generated.backendResolution.resolvedBackend.name,
                    fallbackAttempted = generated.backendResolution.fallbackAttempted,
                ),
            )

            val result =
                try {
                    SummaryResultCodec.decode(generated.text, prompt.allowedEvidenceRefs)
                } catch (firstFailure: SummaryStructuredOutputException) {
                    if (!isRepairable(firstFailure, generated.text)) throw firstFailure
                    repairAttempted = true
                    val repairPrompt =
                        repairPromptRenderer.render(
                            input = input,
                            invalidOutput = generated.text,
                            validationError = firstFailure,
                        )
                    try {
                        budgetGate.requireFits(repairPrompt)
                    } catch (tooLarge: OfflineSummaryBudgetExceededException) {
                        throw OfflineSummaryCoordinatorException(
                            OfflineSummaryFailureCode.REPAIR_FAILED,
                            "本地结构修复请求超过安全预算",
                            tooLarge,
                        )
                    }
                    val repaired =
                        try {
                            generateLocal(
                                repairPrompt.text,
                                OfflineSummaryLocalLlmPreset.configFor(repairPrompt.allowedEvidenceRefs),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (repairRuntimeFailure: Throwable) {
                            throw OfflineSummaryCoordinatorException(
                                OfflineSummaryFailureCode.REPAIR_FAILED,
                                "本地结构修复推理失败：" +
                                    sanitizeMessage(
                                        repairRuntimeFailure.message
                                            ?: repairRuntimeFailure::class.java.simpleName,
                                    ),
                                repairRuntimeFailure,
                            )
                        }
                    generation = repaired
                    try {
                        requireGenerationIdentity(repaired, preparedIdentity)
                    } catch (identityFailure: Throwable) {
                        throw OfflineSummaryCoordinatorException(
                            OfflineSummaryFailureCode.REPAIR_FAILED,
                            "本地结构修复期间模型身份发生变化",
                            identityFailure,
                        )
                    }
                    putState(
                        OfflineSummaryRunState(
                            recordingId = recordingId,
                            summaryId = summaryId,
                            phase = OfflineSummaryRunPhase.VALIDATING,
                            estimatedPromptTokens = estimatedPromptTokens,
                            resolvedBackend = repaired.backendResolution.resolvedBackend.name,
                            fallbackAttempted = repaired.backendResolution.fallbackAttempted,
                            repairAttempted = true,
                        ),
                    )
                    try {
                        SummaryResultCodec.decode(repaired.text, repairPrompt.allowedEvidenceRefs)
                    } catch (secondFailure: SummaryStructuredOutputException) {
                        throw OfflineSummaryCoordinatorException(
                            OfflineSummaryFailureCode.REPAIR_FAILED,
                            "一次本地结构修复后仍未通过校验：${secondFailure.code.name}",
                            secondFailure,
                        )
                    }
                }

            val evidence = OfflineSummaryEvidenceMapper.map(input, result)
            persistence.complete(summaryId, result, evidence)
            completedPersisted = true
            publishCompleted(
                recordingId = recordingId,
                summaryId = summaryId,
                estimatedPromptTokens = estimatedPromptTokens,
                generation = generation ?: generated,
                repairAttempted = repairAttempted,
                initialWarning = null,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!completedPersisted) {
                    val longCancelled = route == OfflineSummaryRoute.LONG && longRunner.cancel(summaryId)
                    if (!longCancelled) runCatching { persistence.cancel(summaryId) }
                    putState(
                        OfflineSummaryRunState(
                            recordingId = recordingId,
                            summaryId = summaryId,
                            phase = OfflineSummaryRunPhase.CANCELLED,
                            failureCode = OfflineSummaryFailureCode.GENERATION_CANCELLED,
                            message = "离线总结已取消",
                            estimatedPromptTokens = estimatedPromptTokens,
                            resolvedBackend = generation?.backendResolution?.resolvedBackend?.name,
                            fallbackAttempted = generation?.backendResolution?.fallbackAttempted ?: false,
                            repairAttempted = repairAttempted,
                        ),
                    )
                } else {
                    putCompletedAfterPostProcessingCancellation(
                        recordingId,
                        summaryId,
                        estimatedPromptTokens,
                        generation,
                        repairAttempted,
                        completionWarning,
                    )
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!completedPersisted) {
                val failure = classifyFailure(error)
                val longFailed = route == OfflineSummaryRoute.LONG && longRunner.fail(summaryId, failure.code, failure.message)
                if (!longFailed) runCatching { persistence.fail(summaryId, failure.code, failure.message) }
                putFailedState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    failure = failure,
                    estimatedPromptTokens = estimatedPromptTokens,
                    generation = generation,
                    repairAttempted = repairAttempted,
                )
            }
        } finally {
            withContext(NonCancellable) { releaseOwnership(recordingId) }
        }
    }

    private suspend fun executeResume(
        recordingId: String,
        summaryId: String,
        input: StructuredTranscriptInput,
    ) {
        var completedPersisted = false
        var generation: LocalLlmGenerationResult? = null
        var repairAttempted = false
        var completionWarning: String? = null
        try {
            requireResourcesAvailable()
            val preparedIdentity = verifyModelReady()
            Stage172OfflineSummaryModelContract.requireMatches(preparedIdentity)
            val longResult =
                longRunner.executeResume(
                    summaryId = summaryId,
                    input = input,
                    preparedIdentity = preparedIdentity,
                    onProgress = { progress ->
                        putState(
                            OfflineSummaryRunState(
                                recordingId = recordingId,
                                summaryId = summaryId,
                                phase = progress.phase,
                                message = progress.message,
                            ),
                        )
                    },
                )
            generation = longResult.finalGeneration
            repairAttempted = longResult.repairAttempted
            completionWarning = longResult.warning
            completedPersisted = true
            publishCompleted(
                recordingId = recordingId,
                summaryId = summaryId,
                estimatedPromptTokens = null,
                generation = longResult.finalGeneration,
                repairAttempted = repairAttempted,
                initialWarning = completionWarning,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!completedPersisted) {
                    val longCancelled = longRunner.cancel(summaryId)
                    if (!longCancelled) runCatching { persistence.cancel(summaryId) }
                    putState(
                        OfflineSummaryRunState(
                            recordingId = recordingId,
                            summaryId = summaryId,
                            phase = OfflineSummaryRunPhase.CANCELLED,
                            failureCode = OfflineSummaryFailureCode.GENERATION_CANCELLED,
                            message = "离线总结已取消",
                            resolvedBackend = generation?.backendResolution?.resolvedBackend?.name,
                            fallbackAttempted = generation?.backendResolution?.fallbackAttempted ?: false,
                            repairAttempted = repairAttempted,
                        ),
                    )
                } else {
                    putCompletedAfterPostProcessingCancellation(
                        recordingId,
                        summaryId,
                        null,
                        generation,
                        repairAttempted,
                        completionWarning,
                    )
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            if (!completedPersisted) {
                val failure = classifyFailure(error)
                val longFailed = longRunner.fail(summaryId, failure.code, failure.message)
                if (!longFailed) runCatching { persistence.fail(summaryId, failure.code, failure.message) }
                putFailedState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    failure = failure,
                    generation = generation,
                    repairAttempted = repairAttempted,
                )
            }
        } finally {
            withContext(NonCancellable) { releaseOwnership(recordingId) }
        }
    }

    private suspend fun publishCompleted(
        recordingId: String,
        summaryId: String,
        estimatedPromptTokens: Int?,
        generation: LocalLlmGenerationResult,
        repairAttempted: Boolean,
        initialWarning: String?,
    ) {
        try {
            onCompleted(recordingId, summaryId)
            putState(
                OfflineSummaryRunState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    phase = OfflineSummaryRunPhase.COMPLETED,
                    estimatedPromptTokens = estimatedPromptTokens,
                    resolvedBackend = generation.backendResolution.resolvedBackend.name,
                    fallbackAttempted = generation.backendResolution.fallbackAttempted,
                    repairAttempted = repairAttempted,
                    warning = initialWarning,
                ),
            )
        } catch (postCompletionFailure: Throwable) {
            val postWarning =
                "总结已保存，但内容版本/搜索索引后处理未完全完成：" +
                    sanitizeMessage(postCompletionFailure.message ?: "未知错误")
            putState(
                OfflineSummaryRunState(
                    recordingId = recordingId,
                    summaryId = summaryId,
                    phase = OfflineSummaryRunPhase.COMPLETED,
                    estimatedPromptTokens = estimatedPromptTokens,
                    resolvedBackend = generation.backendResolution.resolvedBackend.name,
                    fallbackAttempted = generation.backendResolution.fallbackAttempted,
                    repairAttempted = repairAttempted,
                    warning = listOfNotNull(initialWarning, postWarning).joinToString("；"),
                ),
            )
        }
    }

    private fun putCompletedAfterPostProcessingCancellation(
        recordingId: String,
        summaryId: String,
        estimatedPromptTokens: Int?,
        generation: LocalLlmGenerationResult?,
        repairAttempted: Boolean,
        initialWarning: String?,
    ) {
        putState(
            OfflineSummaryRunState(
                recordingId = recordingId,
                summaryId = summaryId,
                phase = OfflineSummaryRunPhase.COMPLETED,
                estimatedPromptTokens = estimatedPromptTokens,
                resolvedBackend = generation?.backendResolution?.resolvedBackend?.name,
                fallbackAttempted = generation?.backendResolution?.fallbackAttempted ?: false,
                repairAttempted = repairAttempted,
                warning = listOfNotNull(initialWarning, "总结已保存；取消发生在完成后的后处理阶段").joinToString("；"),
            ),
        )
    }

    private suspend fun resolveAndValidateInput(recordingId: String): StructuredTranscriptInput {
        val input =
            try {
                resolveInput(recordingId)
            } catch (known: OfflineSummaryCoordinatorException) {
                throw known
            } catch (error: Throwable) {
                throw OfflineSummaryCoordinatorException(
                    OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                    error.message ?: "当前有效转写不可用",
                    error,
                )
            }
        if (input.recordingId != recordingId) {
            throw OfflineSummaryCoordinatorException(
                OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                "当前有效转写不属于目标录音",
            )
        }
        return input
    }

    private suspend fun startOwnedJob(
        recordingId: String,
        block: suspend () -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        ownershipMutex.withLock { activeJobs[recordingId] = job }
        if (!job.start()) {
            throw OfflineSummaryCoordinatorException(
                OfflineSummaryFailureCode.UNKNOWN,
                "应用级离线总结执行器不可用",
            )
        }
    }

    private suspend fun acquireOwnership(recordingId: String): Boolean =
        ownershipMutex.withLock { activeRecordingIds.add(recordingId) }

    private fun requireResourcesAvailable() {
        criticalTaskReason()?.let { reason ->
            throw OfflineSummaryCoordinatorException(OfflineSummaryFailureCode.RESOURCE_BLOCKED, reason)
        }
    }

    private fun requireGenerationIdentity(
        generated: LocalLlmGenerationResult,
        preparedIdentity: LocalLlmModelIdentity,
    ) {
        Stage172OfflineSummaryModelContract.requireMatches(generated.modelIdentity)
        if (generated.modelIdentity != preparedIdentity) {
            throw OfflineSummaryCoordinatorException(
                OfflineSummaryFailureCode.MODEL_NOT_READY,
                "离线总结运行期间本地模型身份发生变化",
            )
        }
    }

    private fun isRepairable(
        failure: SummaryStructuredOutputException,
        rawOutput: String,
    ): Boolean {
        if (rawOutput.isBlank()) return false
        return failure.code != SummaryStructuredOutputErrorCode.EMPTY_FINAL_CONTENT
    }

    private fun putState(state: OfflineSummaryRunState) {
        mutableState.update { current -> current + (state.recordingId to state) }
    }

    private fun putFailedState(
        recordingId: String,
        summaryId: String,
        failure: ClassifiedFailure,
        estimatedPromptTokens: Int? = null,
        generation: LocalLlmGenerationResult? = null,
        repairAttempted: Boolean = false,
    ) {
        putState(
            OfflineSummaryRunState(
                recordingId = recordingId,
                summaryId = summaryId,
                phase = OfflineSummaryRunPhase.FAILED,
                failureCode = failure.code,
                message = failure.message,
                estimatedPromptTokens = estimatedPromptTokens,
                resolvedBackend = generation?.backendResolution?.resolvedBackend?.name,
                fallbackAttempted = generation?.backendResolution?.fallbackAttempted ?: false,
                repairAttempted = repairAttempted,
            ),
        )
    }

    private suspend fun releaseOwnership(recordingId: String) {
        ownershipMutex.withLock {
            activeJobs.remove(recordingId)
            activeRecordingIds.remove(recordingId)
        }
    }

    private data class ClassifiedFailure(
        val code: OfflineSummaryFailureCode,
        val message: String,
    )

    private fun classifyFailure(error: Throwable): ClassifiedFailure =
        when (error) {
            is OfflineSummaryCoordinatorException ->
                ClassifiedFailure(error.code, sanitizeMessage(error.message ?: error.code.name))

            is OfflineSummaryLongPersistenceException ->
                when (error.code) {
                    OfflineSummaryLongPersistenceErrorCode.CHECKPOINT_INVALID ->
                        ClassifiedFailure(
                            OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID,
                            sanitizeMessage(error.message ?: "长文本检查点无效"),
                        )
                    else ->
                        ClassifiedFailure(
                            OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                            sanitizeMessage(error.message ?: "长文本总结无法继续"),
                        )
                }

            is OfflineSummaryLongPlanningException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY,
                    sanitizeMessage(error.message ?: "长文本规划超过安全预算"),
                )

            is OfflineSummaryIntermediateException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID,
                    sanitizeMessage(error.message ?: "长文本中间结果无效"),
                )

            is OfflineSummaryBudgetExceededException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY,
                    "当前离线总结请求超过安全预算",
                )

            is OfflineSummaryEvidenceMappingException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.OUTPUT_INVALID_EVIDENCE,
                    "本地 AI 返回了不属于当前转写快照的证据引用",
                )

            is SummaryStructuredOutputException ->
                ClassifiedFailure(
                    mapStructuredOutputFailure(error.code),
                    sanitizeMessage(error.message ?: "本地 AI 输出结构无效"),
                )

            is LocalLlmRuntimeException ->
                ClassifiedFailure(
                    mapRuntimeFailure(error.failure.code),
                    sanitizeMessage(error.failure.message),
                )

            else ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.UNKNOWN,
                    sanitizeMessage(error.message ?: error::class.java.simpleName),
                )
        }

    private fun mapRuntimeFailure(code: LocalLlmFailureCode): OfflineSummaryFailureCode =
        when (code) {
            LocalLlmFailureCode.MODEL_NOT_INSTALLED -> OfflineSummaryFailureCode.MODEL_NOT_INSTALLED
            LocalLlmFailureCode.MODEL_CORRUPT,
            LocalLlmFailureCode.MODEL_INCOMPATIBLE,
            -> OfflineSummaryFailureCode.MODEL_NOT_READY

            LocalLlmFailureCode.BUSY -> OfflineSummaryFailureCode.RUNTIME_BUSY
            LocalLlmFailureCode.INSUFFICIENT_MEMORY,
            LocalLlmFailureCode.THERMAL_BLOCKED,
            LocalLlmFailureCode.BACKEND_UNSUPPORTED,
            -> OfflineSummaryFailureCode.RESOURCE_BLOCKED

            LocalLlmFailureCode.ENGINE_INIT_FAILED,
            LocalLlmFailureCode.CONVERSATION_CREATE_FAILED,
            -> OfflineSummaryFailureCode.ENGINE_INIT_FAILED

            LocalLlmFailureCode.CANCELLED -> OfflineSummaryFailureCode.GENERATION_CANCELLED
            LocalLlmFailureCode.GENERATION_FAILED,
            LocalLlmFailureCode.NATIVE_PROCESS_FAILURE_EVIDENCE,
            -> OfflineSummaryFailureCode.GENERATION_FAILED

            LocalLlmFailureCode.UNKNOWN -> OfflineSummaryFailureCode.UNKNOWN
        }

    private fun mapStructuredOutputFailure(code: SummaryStructuredOutputErrorCode): OfflineSummaryFailureCode =
        when (code) {
            SummaryStructuredOutputErrorCode.TRUNCATED_JSON -> OfflineSummaryFailureCode.OUTPUT_TRUNCATED
            SummaryStructuredOutputErrorCode.INVALID_JSON,
            SummaryStructuredOutputErrorCode.EMPTY_FINAL_CONTENT,
            -> OfflineSummaryFailureCode.OUTPUT_INVALID_JSON

            SummaryStructuredOutputErrorCode.INVALID_EVIDENCE_REF,
            SummaryStructuredOutputErrorCode.MISSING_EVIDENCE,
            -> OfflineSummaryFailureCode.OUTPUT_INVALID_EVIDENCE

            SummaryStructuredOutputErrorCode.MISSING_FIELD,
            SummaryStructuredOutputErrorCode.WRONG_FIELD_TYPE,
            SummaryStructuredOutputErrorCode.INVALID_ENUM,
            SummaryStructuredOutputErrorCode.INVALID_VALUE,
            SummaryStructuredOutputErrorCode.DUPLICATE_ID,
            -> OfflineSummaryFailureCode.OUTPUT_SCHEMA_INVALID
        }

    private fun sanitizeMessage(message: String): String =
        message.replace(Regex("\\s+"), " ").trim().take(240)
}

/** Production wiring: one coordinator and one AppLocalLlmRuntime for both routes. */
internal fun createOfflineSummaryCoordinator(
    application: Application,
    container: AppContainer,
    scope: CoroutineScope,
): OfflineSummaryCoordinator {
    val localLlmRuntime = container.localLlmRuntime(application)
    val directPersistence = container.aiSummaryRepository.offlineSummaryPersistenceRepository()
    val generateLocal: suspend (String, LocalLlmRuntimeConfig) -> LocalLlmGenerationResult =
        { prompt, config ->
            check(config.backendPolicy == LocalLlmBackendPolicy.AUTO) {
                "Stage 17 product summary must use AUTO backend policy"
            }
            localLlmRuntime.generate(prompt = prompt, config = config)
        }
    val longRunner =
        OfflineSummaryLongRunner(
            store =
                RoomOfflineSummaryLongStateStore(
                    longPersistence = container.aiSummaryRepository.offlineSummaryLongPersistenceRepository(),
                    directPersistence = directPersistence,
                ),
            generateLocal = generateLocal,
        )
    return OfflineSummaryCoordinator(
        scope = scope,
        resolveInput = { recordingId ->
            val effective =
                container.stage12CContentRepository.resolveEffectiveTranscription(recordingId)
                    ?: throw OfflineSummaryCoordinatorException(
                        OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                        "当前录音没有可用于离线总结的已完成转写",
                    )
            container.aiSummaryRepository.buildStructuredTranscriptSnapshot(
                transcriptionId = effective.transcriptionId,
                revisionId = effective.revisionId,
            )
        },
        verifyModelReady = {
            val active =
                container.modelManager.activeModel(Stage172OfflineSummaryModelContract.MODEL_ID)
                    ?: throw OfflineSummaryCoordinatorException(
                        OfflineSummaryFailureCode.MODEL_NOT_INSTALLED,
                        "需要先安装并激活 Gemma 4 E2B 本地模型",
                    )
            val identity =
                resolveManagedLocalLlmModel(
                    active = active,
                    policy = LocalLlmBackendPolicy.AUTO,
                ).identity
            Stage172OfflineSummaryModelContract.requireMatches(identity)
            identity
        },
        generateLocal = generateLocal,
        persistence = RoomOfflineSummaryPersistencePort(directPersistence),
        onCompleted = { recordingId, summaryId ->
            container.stage12CContentRepository.onAiSummaryCompleted(recordingId, summaryId)
        },
        criticalTaskReason = { container.offlineSummaryCriticalTaskReason() },
        longRunner = longRunner,
    )
}

internal fun AppContainer.offlineSummaryCriticalTaskReason(): String? {
    if (transcriptionCoordinator.state.value is TranscriptionRunState.Running) {
        return "本地转写正在运行，请完成后再生成离线总结"
    }
    if (diarizationCoordinator.state.value is DiarizationRunState.Running) {
        return "说话人分离正在运行，请完成后再生成离线总结"
    }
    if (diarizationCoordinator.alignmentState.value is SpeakerAlignmentRunState.Running) {
        return "说话人对齐正在运行，请完成后再生成离线总结"
    }
    if (stage16ARealtimeRuntime.state.value.phase.isProtectedForOfflineSummary()) {
        return "录音卡实时录音/转写正在运行，离线总结不会抢占该任务"
    }
    if (phoneMicrophoneRealtimeRuntime.state.value.phase.isProtectedForOfflineSummary()) {
        return "手机实时转写正在运行，离线总结不会抢占该任务"
    }
    if (phoneMicrophoneRecordingRuntime.state.value.phase.isProtectedForOfflineSummary()) {
        return "手机录音正在运行，离线总结不会抢占该任务"
    }
    when (deviceRepository.recordingState.value.status) {
        RecordingStatus.Recording,
        RecordingStatus.Paused,
        -> return "录音卡正在录音，离线总结不会抢占录音任务"
        else -> Unit
    }
    return null
}

private fun Stage16ARealtimeUiPhase.isProtectedForOfflineSummary(): Boolean =
    this == Stage16ARealtimeUiPhase.PREPARING ||
        this == Stage16ARealtimeUiPhase.STARTING ||
        this == Stage16ARealtimeUiPhase.ACTIVE ||
        this == Stage16ARealtimeUiPhase.PAUSED ||
        this == Stage16ARealtimeUiPhase.RESUMING ||
        this == Stage16ARealtimeUiPhase.FINALIZING

private fun PhoneMicrophoneRecordingPhase.isProtectedForOfflineSummary(): Boolean =
    this == PhoneMicrophoneRecordingPhase.STARTING ||
        this == PhoneMicrophoneRecordingPhase.RECORDING ||
        this == PhoneMicrophoneRecordingPhase.PAUSING ||
        this == PhoneMicrophoneRecordingPhase.PAUSED ||
        this == PhoneMicrophoneRecordingPhase.RESUMING ||
        this == PhoneMicrophoneRecordingPhase.STOPPING
