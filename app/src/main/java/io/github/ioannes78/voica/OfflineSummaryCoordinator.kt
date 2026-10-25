package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.OfflineSummaryBudgetExceededException
import io.github.ioannes78.voica.ai.OfflineSummaryDirectBudgetGate
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryPromptRenderer
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.SummaryStructuredOutputErrorCode
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.database.OfflineSummaryEvidenceMapper
import io.github.ioannes78.voica.database.OfflineSummaryEvidenceMappingException
import io.github.ioannes78.voica.database.OfflineSummaryEvidencePersistenceDraft
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Product run phase for one Stage 17.2 direct offline summary generation. */
enum class OfflineSummaryRunPhase {
    PREPARING,
    ANALYZING,
    VALIDATING,
    COMPLETED,
    FAILED,
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

/**
 * Narrow persistence port keeps coordinator tests independent of Room while production still reuses
 * the single Room v16 [OfflineSummaryPersistenceRepository].
 */
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
}

/**
 * Stage 17.2 product model lock. A future candidate-channel change must not silently move summary
 * inference away from the exact pre-MTP Gemma artifact validated by Stage 17 P0/P1.
 */
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
                message = "当前本地 AI 模型不符合 Stage 17.2 已验证的 Gemma 4 E2B 版本",
            )
        }
    }
}

/** Separately named product preset; values intentionally stay inside the P0/P1 validated envelope. */
internal object OfflineSummaryLocalLlmPreset {
    val CONFIG =
        LocalLlmRuntimeConfig(
            backendPolicy = LocalLlmBackendPolicy.AUTO,
            maxNumTokens = 4_000,
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            speculativeDecoding = false,
        )
}

internal class OfflineSummaryCoordinatorException(
    val code: OfflineSummaryFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Application-level Stage 17.2 direct-summary owner.
 *
 * The supplied [scope] is the existing AppContainer application scope. This class never creates a
 * LiteRT-LM Engine/Conversation and never owns backend selection. Production generation is wired
 * only to [AppLocalLlmRuntime.generate], which remains the sole P1 local-LLM execution boundary.
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
    private val promptRenderer: OfflineSummaryPromptRenderer = OfflineSummaryPromptRenderer(),
    private val budgetGate: OfflineSummaryDirectBudgetGate = OfflineSummaryDirectBudgetGate(),
) {
    private val ownershipMutex = Mutex()
    private val activeRecordingIds = mutableSetOf<String>()
    private val mutableState = MutableStateFlow<Map<String, OfflineSummaryRunState>>(emptyMap())

    val state: StateFlow<Map<String, OfflineSummaryRunState>> = mutableState.asStateFlow()

    suspend fun start(recordingId: String): OfflineSummaryStartOutcome {
        val normalizedRecordingId = recordingId.trim()
        require(normalizedRecordingId.isNotEmpty()) { "recordingId must not be blank" }

        criticalTaskReason()?.let { reason ->
            return OfflineSummaryStartOutcome.Rejected(
                code = OfflineSummaryFailureCode.RESOURCE_BLOCKED,
                message = reason,
            )
        }

        val acquired =
            ownershipMutex.withLock {
                activeRecordingIds.add(normalizedRecordingId)
            }
        if (!acquired) {
            return OfflineSummaryStartOutcome.Rejected(
                code = OfflineSummaryFailureCode.RUNTIME_BUSY,
                message = "该录音的离线总结正在生成",
            )
        }

        var createdSummaryId: String? = null
        return try {
            val input =
                try {
                    resolveInput(normalizedRecordingId)
                } catch (known: OfflineSummaryCoordinatorException) {
                    throw known
                } catch (error: Throwable) {
                    throw OfflineSummaryCoordinatorException(
                        code = OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                        message = error.message ?: "当前有效转写不可用",
                        cause = error,
                    )
                }
            if (input.recordingId != normalizedRecordingId) {
                throw OfflineSummaryCoordinatorException(
                    code = OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                    message = "当前有效转写不属于目标录音",
                )
            }

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

            scope.launch {
                execute(
                    recordingId = normalizedRecordingId,
                    summaryId = summaryId,
                    input = input,
                )
            }
            OfflineSummaryStartOutcome.Started(summaryId)
        } catch (cancelled: CancellationException) {
            releaseOwnership(normalizedRecordingId)
            throw cancelled
        } catch (error: Throwable) {
            val failure = classifyFailure(error)
            createdSummaryId?.let { summaryId ->
                runCatching { persistence.fail(summaryId, failure.code, failure.message) }
                putState(
                    OfflineSummaryRunState(
                        recordingId = normalizedRecordingId,
                        summaryId = summaryId,
                        phase = OfflineSummaryRunPhase.FAILED,
                        failureCode = failure.code,
                        message = failure.message,
                    ),
                )
            }
            releaseOwnership(normalizedRecordingId)
            OfflineSummaryStartOutcome.Rejected(failure.code, failure.message)
        }
    }

    private suspend fun execute(
        recordingId: String,
        summaryId: String,
        input: StructuredTranscriptInput,
    ) {
        var completedPersisted = false
        var estimatedPromptTokens: Int? = null
        var generation: LocalLlmGenerationResult? = null
        try {
            criticalTaskReason()?.let { reason ->
                throw OfflineSummaryCoordinatorException(
                    code = OfflineSummaryFailureCode.RESOURCE_BLOCKED,
                    message = reason,
                )
            }

            val preparedIdentity = verifyModelReady()
            Stage172OfflineSummaryModelContract.requireMatches(preparedIdentity)

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
                    OfflineSummaryLocalLlmPreset.CONFIG,
                )
            generation = generated
            Stage172OfflineSummaryModelContract.requireMatches(generated.modelIdentity)
            if (generated.modelIdentity != preparedIdentity) {
                throw OfflineSummaryCoordinatorException(
                    code = OfflineSummaryFailureCode.MODEL_NOT_READY,
                    message = "离线总结运行期间本地模型身份发生变化",
                )
            }

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

            val result = SummaryResultCodec.decode(generated.text, prompt.allowedEvidenceRefs)
            val evidence = OfflineSummaryEvidenceMapper.map(input, result)
            persistence.complete(summaryId, result, evidence)
            completedPersisted = true

            try {
                onCompleted(recordingId, summaryId)
                putState(
                    OfflineSummaryRunState(
                        recordingId = recordingId,
                        summaryId = summaryId,
                        phase = OfflineSummaryRunPhase.COMPLETED,
                        estimatedPromptTokens = estimatedPromptTokens,
                        resolvedBackend = generated.backendResolution.resolvedBackend.name,
                        fallbackAttempted = generated.backendResolution.fallbackAttempted,
                    ),
                )
            } catch (postCompletionFailure: Throwable) {
                putState(
                    OfflineSummaryRunState(
                        recordingId = recordingId,
                        summaryId = summaryId,
                        phase = OfflineSummaryRunPhase.COMPLETED,
                        estimatedPromptTokens = estimatedPromptTokens,
                        resolvedBackend = generated.backendResolution.resolvedBackend.name,
                        fallbackAttempted = generated.backendResolution.fallbackAttempted,
                        warning =
                            "总结已保存，但内容版本/搜索索引后处理未完全完成：" +
                                sanitizeMessage(postCompletionFailure.message ?: "未知错误"),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            // P6 adds explicit user-cancellation persistence. Process/scope cancellation must not be
            // mislabeled as a normal FAILED result here.
            throw cancelled
        } catch (error: Throwable) {
            if (!completedPersisted) {
                val failure = classifyFailure(error)
                runCatching { persistence.fail(summaryId, failure.code, failure.message) }
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
                    ),
                )
            }
        } finally {
            withContext(NonCancellable) {
                releaseOwnership(recordingId)
            }
        }
    }

    private fun putState(state: OfflineSummaryRunState) {
        mutableState.update { current -> current + (state.recordingId to state) }
    }

    private suspend fun releaseOwnership(recordingId: String) {
        ownershipMutex.withLock {
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

            is OfflineSummaryBudgetExceededException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY,
                    "当前转写超过 Stage 17.2 单次离线总结容量，请等待后续长文本总结能力",
                )

            is OfflineSummaryEvidenceMappingException ->
                ClassifiedFailure(
                    OfflineSummaryFailureCode.OUTPUT_INVALID_EVIDENCE,
                    "本地 AI 返回了不属于当前转写快照的证据引用",
                )

            is SummaryStructuredOutputException ->
                ClassifiedFailure(
                    code = mapStructuredOutputFailure(error.code),
                    message = sanitizeMessage(error.message ?: "本地 AI 输出结构无效"),
                )

            is LocalLlmRuntimeException ->
                ClassifiedFailure(
                    code = mapRuntimeFailure(error.failure.code),
                    message = sanitizeMessage(error.failure.message),
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

    private fun mapStructuredOutputFailure(
        code: SummaryStructuredOutputErrorCode,
    ): OfflineSummaryFailureCode =
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

/** Production wiring: exactly one coordinator instance is owned by AppContainer/application scope. */
internal fun createOfflineSummaryCoordinator(
    application: Application,
    container: AppContainer,
    scope: CoroutineScope,
): OfflineSummaryCoordinator {
    val localLlmRuntime = container.localLlmRuntime(application)
    return OfflineSummaryCoordinator(
        scope = scope,
        resolveInput = { recordingId ->
            val effective =
                container.stage12CContentRepository.resolveEffectiveTranscription(recordingId)
                    ?: throw OfflineSummaryCoordinatorException(
                        code = OfflineSummaryFailureCode.INPUT_NOT_AVAILABLE,
                        message = "当前录音没有可用于离线总结的已完成转写",
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
                        code = OfflineSummaryFailureCode.MODEL_NOT_INSTALLED,
                        message = "需要先安装并激活 Gemma 4 E2B 本地模型",
                    )
            val identity =
                resolveManagedLocalLlmModel(
                    active = active,
                    policy = LocalLlmBackendPolicy.AUTO,
                ).identity
            Stage172OfflineSummaryModelContract.requireMatches(identity)
            identity
        },
        generateLocal = { prompt, config ->
            check(config.backendPolicy == LocalLlmBackendPolicy.AUTO) {
                "Stage 17.2 product summary must use AUTO backend policy"
            }
            localLlmRuntime.generate(prompt = prompt, config = config)
        },
        persistence =
            RoomOfflineSummaryPersistencePort(
                container.aiSummaryRepository.offlineSummaryPersistenceRepository(),
            ),
        onCompleted = { recordingId, summaryId ->
            container.stage12CContentRepository.onAiSummaryCompleted(recordingId, summaryId)
        },
        criticalTaskReason = { container.offlineSummaryCriticalTaskReason() },
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
