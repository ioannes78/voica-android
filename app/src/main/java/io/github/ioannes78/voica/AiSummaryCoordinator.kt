package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEngine
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.ai.AiSummaryEngineProgress
import io.github.ioannes78.voica.ai.AiSummaryEngineRequest
import io.github.ioannes78.voica.ai.AiSummaryMode
import io.github.ioannes78.voica.ai.ProviderFailureCarrier
import io.github.ioannes78.voica.ai.ProviderProfile
import io.github.ioannes78.voica.ai.SummaryDisplayFormatter
import io.github.ioannes78.voica.ai.SummaryPromptFactory
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.ai.SummaryTemplateCatalog
import io.github.ioannes78.voica.ai.SummaryTemplateSnapshotCodec
import io.github.ioannes78.voica.ai.SummaryTemplateSpec
import io.github.ioannes78.voica.database.AiSummaryEvidenceWrite
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.NewAiSummaryRequest
import io.github.ioannes78.voica.database.RoomSummaryCheckpointStore
import io.github.ioannes78.voica.database.StructuredTranscriptInputBuilder
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderProfileStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AiSummaryRunState {
    data object Idle : AiSummaryRunState

    data class Running(
        val summaryId: String?,
        val recordingId: String?,
        val transcriptionId: String,
        val phase: AiSummaryEnginePhase,
        val completedUnits: Int = 0,
        val totalUnits: Int = 0,
    ) : AiSummaryRunState

    data class Completed(
        val summaryId: String,
        val recordingId: String,
        val transcriptionId: String,
    ) : AiSummaryRunState

    data class Failed(
        val summaryId: String?,
        val transcriptionId: String,
        val errorCode: String,
        val message: String,
    ) : AiSummaryRunState

    data class Cancelled(
        val summaryId: String?,
        val transcriptionId: String,
    ) : AiSummaryRunState
}

class AiSummaryCoordinator(
    private val scope: CoroutineScope,
    private val repository: AiSummaryRepository,
    private val inputBuilder: StructuredTranscriptInputBuilder,
    private val profileStore: ProviderProfileStore,
    private val providerRegistry: ProviderAdapterRegistry,
    private val engine: AiSummaryEngine = AiSummaryEngine(),
) {
    private val mutableState = MutableStateFlow<AiSummaryRunState>(AiSummaryRunState.Idle)
    val state: StateFlow<AiSummaryRunState> = mutableState.asStateFlow()

    private var activeJob: Job? = null

    fun start(
        transcriptionId: String,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
    ): Boolean {
        if (activeJob?.isActive == true) return false
        require(transcriptionId.isNotBlank())
        activeJob =
            scope.launch {
                generateNew(
                    transcriptionId = transcriptionId,
                    mode = mode,
                    template = template,
                )
            }
        return true
    }

    fun startSmart(transcriptionId: String): Boolean =
        start(
            transcriptionId = transcriptionId,
            mode = AiSummaryMode.SMART,
            template = SummaryTemplateCatalog.smart(),
        )

    fun cancel() {
        activeJob?.cancel()
    }

    fun resumeInterrupted(summaryId: String): Boolean {
        if (activeJob?.isActive == true) return false
        require(summaryId.isNotBlank())
        activeJob =
            scope.launch {
                resume(summaryId)
            }
        return true
    }

    private suspend fun generateNew(
        transcriptionId: String,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
    ) {
        var summaryId: String? = null
        try {
            mutableState.value =
                AiSummaryRunState.Running(
                    summaryId = null,
                    recordingId = null,
                    transcriptionId = transcriptionId,
                    phase = AiSummaryEnginePhase.PREPARING,
                )
            val input = inputBuilder.build(transcriptionId)
            val profile = resolveDefaultProfile()
            validateProfileForGeneration(profile)
            summaryId =
                repository.create(
                    NewAiSummaryRequest(
                        recordingId = input.recordingId,
                        transcriptionId = input.transcriptionId,
                        mode = mode.databaseValue(),
                        templateId = template.id,
                        templateSnapshot = SummaryTemplateSnapshotCodec.encode(template),
                        providerProfileId = profile.providerProfileId,
                        providerNameSnapshot = profile.displayName,
                        baseUrlSnapshot = profile.baseUrl,
                        model = profile.defaultModel,
                        promptVersion = SummaryPromptFactory.PROMPT_VERSION,
                        resultSchemaVersion = SummaryPromptFactory.RESULT_SCHEMA_VERSION,
                        requestConfigSnapshot =
                            buildJsonObject {
                                put("inputMode", "TRANSCRIPT_TEXT")
                                put("mode", mode.name)
                                template.id?.let { put("templateId", it) }
                            }.toString(),
                        alignmentIdSnapshot = input.alignmentId,
                        sourceLineageSnapshot = lineageSnapshot(input),
                    ),
                )
            runGeneration(
                summaryId = summaryId,
                inputTranscriptionId = transcriptionId,
                profile = profile,
                mode = mode,
                template = template,
            )
        } catch (cancelled: CancellationException) {
            summaryId?.let { id ->
                runCatching {
                    repository.transition(
                        summaryId = id,
                        status = AiSummaryStateValue.CANCELLED,
                        errorCode = "USER_CANCELLED",
                        sanitizedErrorMessage = "AI summary generation cancelled by user",
                    )
                }
            }
            mutableState.value =
                AiSummaryRunState.Cancelled(
                    summaryId = summaryId,
                    transcriptionId = transcriptionId,
                )
            throw cancelled
        } catch (error: Throwable) {
            fail(summaryId, transcriptionId, error)
        } finally {
            activeJob = null
        }
    }

    private suspend fun resume(summaryId: String) {
        var transcriptionId = ""
        try {
            val summary = repository.find(summaryId)
                ?: throw AiSummaryConfigurationException("AI 总结记录不存在")
            transcriptionId =
                summary.transcriptionId
                    ?: throw AiSummaryConfigurationException("该总结不是转写文本模式")
            if (summary.status != AiSummaryStateValue.INTERRUPTED) {
                throw AiSummaryConfigurationException("只有中断的总结任务可以继续")
            }
            val profileSnapshot = profileStore.load()
            val storedProfile =
                profileSnapshot.profiles.firstOrNull {
                    it.providerProfileId == summary.providerProfileId
                } ?: throw AiSummaryConfigurationException("原文本模型配置已不存在")
            val profile =
                storedProfile.copy(
                    baseUrl = summary.baseUrlSnapshot,
                    defaultModel = summary.model,
                )
            validateProfileForGeneration(profile)
            val template =
                summary.templateSnapshot?.let(SummaryTemplateSnapshotCodec::decode)
                    ?: summary.templateId?.let(SummaryTemplateCatalog::find)
                    ?: SummaryTemplateCatalog.smart()
            val mode =
                AiSummaryMode.entries.firstOrNull { it.databaseValue() == summary.mode }
                    ?: AiSummaryMode.SMART
            check(repository.resumeInterrupted(summaryId)) {
                "AI summary resume state changed"
            }
            runGeneration(
                summaryId = summaryId,
                inputTranscriptionId = transcriptionId,
                profile = profile,
                mode = mode,
                template = template,
            )
        } catch (cancelled: CancellationException) {
            runCatching {
                repository.transition(
                    summaryId = summaryId,
                    status = AiSummaryStateValue.CANCELLED,
                    errorCode = "USER_CANCELLED",
                    sanitizedErrorMessage = "AI summary generation cancelled by user",
                )
            }
            mutableState.value =
                AiSummaryRunState.Cancelled(
                    summaryId = summaryId,
                    transcriptionId = transcriptionId,
                )
            throw cancelled
        } catch (error: Throwable) {
            fail(summaryId, transcriptionId, error)
        } finally {
            activeJob = null
        }
    }

    private suspend fun runGeneration(
        summaryId: String,
        inputTranscriptionId: String,
        profile: ProviderProfile,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
    ) {
        val input = inputBuilder.build(inputTranscriptionId)
        val provider = providerRegistry.forProfile(profile)
        val output =
            engine.generate(
                provider = provider,
                request =
                    AiSummaryEngineRequest(
                        input = input,
                        profile = profile,
                        mode = mode,
                        template = template,
                        checkpointStore =
                            RoomSummaryCheckpointStore(
                                repository = repository,
                                summaryId = summaryId,
                            ),
                    ),
            ) { progress ->
                persistProgress(
                    summaryId = summaryId,
                    inputRecordingId = input.recordingId,
                    inputTranscriptionId = input.transcriptionId,
                    progress = progress,
                )
            }

        val sources = input.units.associateBy { it.evidence.ref }
        val evidence =
            output.result.sections.flatMap { section ->
                section.items.flatMap { item ->
                    item.evidenceRefs.map { ref ->
                        val source =
                            sources[ref]
                                ?: error("validated summary evidence ref disappeared")
                        AiSummaryEvidenceWrite(
                            summaryItemId = item.id,
                            sourceRef = ref,
                            sourceKind = source.evidence.sourceKind.name,
                            sourceId = source.evidence.sourceId,
                            startSampleIndex = source.evidence.startSampleIndex,
                            endSampleIndexExclusive = source.evidence.endSampleIndexExclusive,
                            speakerId = source.evidence.speakerId,
                            assignmentQuality = source.evidence.assignmentQuality,
                        )
                    }
                }
            }
        repository.persistCompleted(
            summaryId = summaryId,
            contentType = output.result.contentType.name,
            classificationConfidence = output.result.classificationConfidence,
            structuredPayloadJson = output.structuredPayloadJson,
            displayText = SummaryDisplayFormatter.format(output.result),
            usageSnapshot =
                output.usage?.let { usage ->
                    buildJsonObject {
                        usage.inputTokens?.let { put("inputTokens", it) }
                        usage.outputTokens?.let { put("outputTokens", it) }
                        usage.totalTokens?.let { put("totalTokens", it) }
                        put("providerCallCount", output.providerCallCount)
                        put("mapChunkCount", output.mapChunkCount)
                    }.toString()
                },
            evidence = evidence,
        )
        mutableState.value =
            AiSummaryRunState.Completed(
                summaryId = summaryId,
                recordingId = input.recordingId,
                transcriptionId = input.transcriptionId,
            )
    }

    private suspend fun persistProgress(
        summaryId: String,
        inputRecordingId: String,
        inputTranscriptionId: String,
        progress: AiSummaryEngineProgress,
    ) {
        val status =
            when (progress.phase) {
                AiSummaryEnginePhase.PREPARING -> AiSummaryStateValue.PREPARING
                AiSummaryEnginePhase.ANALYZING -> AiSummaryStateValue.ANALYZING
                AiSummaryEnginePhase.MAPPING -> AiSummaryStateValue.MAPPING
                AiSummaryEnginePhase.REDUCING -> AiSummaryStateValue.REDUCING
                AiSummaryEnginePhase.VALIDATING -> AiSummaryStateValue.VALIDATING
            }
        repository.transition(summaryId, status)
        mutableState.value =
            AiSummaryRunState.Running(
                summaryId = summaryId,
                recordingId = inputRecordingId,
                transcriptionId = inputTranscriptionId,
                phase = progress.phase,
                completedUnits = progress.completedUnits,
                totalUnits = progress.totalUnits,
            )
    }

    private suspend fun resolveDefaultProfile(): ProviderProfile {
        val snapshot = profileStore.load()
        val selected =
            snapshot.defaultProfileId?.let { defaultId ->
                snapshot.profiles.firstOrNull {
                    it.providerProfileId == defaultId && it.enabled
                }
            } ?: snapshot.profiles.firstOrNull { it.enabled }
        return selected
            ?: throw AiSummaryConfigurationException("请先在设置中配置文本大模型")
    }

    private fun validateProfileForGeneration(profile: ProviderProfile) {
        if (profile.defaultModel.isBlank()) {
            throw AiSummaryConfigurationException("文本大模型尚未选择模型")
        }
    }

    private suspend fun fail(
        summaryId: String?,
        transcriptionId: String,
        error: Throwable,
    ) {
        val failure = sanitizeFailure(error)
        summaryId?.let { id ->
            runCatching {
                repository.transition(
                    summaryId = id,
                    status = AiSummaryStateValue.FAILED,
                    errorCode = failure.first,
                    sanitizedErrorMessage = failure.second,
                )
            }
        }
        mutableState.value =
            AiSummaryRunState.Failed(
                summaryId = summaryId,
                transcriptionId = transcriptionId,
                errorCode = failure.first,
                message = failure.second,
            )
    }

    private fun sanitizeFailure(error: Throwable): Pair<String, String> =
        when (error) {
            is AiSummaryConfigurationException ->
                "INVALID_CONFIGURATION" to error.safeMessage
            is ProviderFailureCarrier -> {
                val failure = error.failure
                failure.code.name to
                    (failure.sanitizedMessage ?: "文本模型请求失败")
            }
            is SummaryStructuredOutputException ->
                "STRUCTURED_OUTPUT_INVALID" to "AI 返回结果结构无法验证，请重试或更换模型"
            else ->
                "AI_SUMMARY_FAILED" to "AI 总结生成失败，请重试"
        }

    private fun lineageSnapshot(
        input: io.github.ioannes78.voica.ai.StructuredTranscriptInput,
    ): String =
        buildJsonObject {
            put("recordingId", input.recordingId)
            put("transcriptionId", input.transcriptionId)
            put("canonicalAssetId", input.canonicalAssetId)
            put("canonicalSha256", input.canonicalSha256)
            put("canonicalProfileId", input.canonicalProfileId)
            put("totalSampleCount", input.totalSampleCount)
            input.alignmentId?.let { put("alignmentId", it) }
        }.toString()

    private fun AiSummaryMode.databaseValue(): String =
        when (this) {
            AiSummaryMode.SMART -> AiSummaryModeValue.SMART
            AiSummaryMode.PRESET -> AiSummaryModeValue.PRESET
            AiSummaryMode.CUSTOM -> AiSummaryModeValue.CUSTOM
        }

    private class AiSummaryConfigurationException(
        val safeMessage: String,
    ) : IllegalArgumentException(safeMessage)
}
