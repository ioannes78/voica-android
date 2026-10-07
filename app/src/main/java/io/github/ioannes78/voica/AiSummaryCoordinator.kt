package io.github.ioannes78.voica

import io.github.ioannes78.voica.ai.AiSummaryEngine
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase
import io.github.ioannes78.voica.ai.AiSummaryEngineProgress
import io.github.ioannes78.voica.ai.AiSummaryEngineRequest
import io.github.ioannes78.voica.ai.AiSummaryMode
import io.github.ioannes78.voica.ai.ProviderErrorCode
import io.github.ioannes78.voica.ai.ProviderFailureCarrier
import io.github.ioannes78.voica.ai.ProviderProfile
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.SummaryDisplayFormatter
import io.github.ioannes78.voica.ai.SummaryPromptFactory
import io.github.ioannes78.voica.ai.SummaryStructuredOutputErrorCode
import io.github.ioannes78.voica.ai.SummaryStructuredOutputException
import io.github.ioannes78.voica.ai.SummaryTemplateCatalog
import io.github.ioannes78.voica.ai.SummaryTemplateSnapshotCodec
import io.github.ioannes78.voica.ai.SummaryTemplateSpec
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryEvidenceWrite
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRemoteDispatchStateValue
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.NewAiSummaryRequest
import io.github.ioannes78.voica.database.RoomSummaryCheckpointStore
import io.github.ioannes78.voica.database.RoomSummaryRemoteCallGate
import io.github.ioannes78.voica.database.StructuredTranscriptInputBuilder
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderProfileStore
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val recordingId: String?,
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
    private val workScheduler: AiSummaryWorkScheduler,
    private val engine: AiSummaryEngine = AiSummaryEngine(),
    private val isRecordingActive: suspend (String) -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow<AiSummaryRunState>(AiSummaryRunState.Idle)
    val state: StateFlow<AiSummaryRunState> = mutableState.asStateFlow()

    private val preparationLock = Any()
    private var preparationJob: Job? = null
    private var preparationTarget: PreparationTarget? = null

    private sealed interface PreparationTarget {
        data class Transcription(val transcriptionId: String) : PreparationTarget
        data class Summary(val summaryId: String) : PreparationTarget
    }

    fun start(
        transcriptionId: String,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
        providerProfileId: String? = null,
        modelOverride: String? = null,
    ): Boolean {
        require(transcriptionId.isNotBlank())
        synchronized(preparationLock) {
            if (preparationJob?.isActive == true || mutableState.value is AiSummaryRunState.Running) {
                return false
            }
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    prepareNew(
                        transcriptionId = transcriptionId,
                        mode = mode,
                        template = template,
                        providerProfileId = providerProfileId,
                        modelOverride = modelOverride,
                    )
                }
            installPreparationJob(job, PreparationTarget.Transcription(transcriptionId))
            job.start()
            return true
        }
    }

    fun startSmart(
        transcriptionId: String,
        providerProfileId: String? = null,
        modelOverride: String? = null,
    ): Boolean =
        start(
            transcriptionId = transcriptionId,
            mode = AiSummaryMode.SMART,
            template = SummaryTemplateCatalog.smart(),
            providerProfileId = providerProfileId,
            modelOverride = modelOverride,
        )

    fun cancel() {
        val preparing = synchronized(preparationLock) { preparationJob }
        if (preparing?.isActive == true) {
            preparing.cancel(CancellationException("user cancelled AI summary preparation"))
            return
        }
        scope.launch {
            val runningId = (mutableState.value as? AiSummaryRunState.Running)?.summaryId
            val target =
                runningId?.let(repository::find)
                    ?: repository.loadActiveSummaries().firstOrNull()
                    ?: return@launch
            cancelDurable(target)
        }
    }

    suspend fun cancelAndAwait(recordingId: String) {
        val snapshot =
            synchronized(preparationLock) {
                preparationJob to preparationTarget
            }
        val job = snapshot.first
        val target = snapshot.second
        if (job != null && target != null) {
            val targetRecordingId =
                when (target) {
                    is PreparationTarget.Transcription ->
                        repository.recordingIdForTranscription(target.transcriptionId)
                    is PreparationTarget.Summary ->
                        repository.find(target.summaryId)?.recordingId
                }
            if (targetRecordingId == recordingId) {
                job.cancel(CancellationException("recording deletion"))
                runCatching { job.join() }
            }
        }

        repository.loadActiveSummaries()
            .filter { it.recordingId == recordingId }
            .forEach { cancelDurable(it) }
    }

    fun resumeInterrupted(summaryId: String): Boolean {
        require(summaryId.isNotBlank())
        synchronized(preparationLock) {
            if (preparationJob?.isActive == true || mutableState.value is AiSummaryRunState.Running) {
                return false
            }
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    prepareResume(summaryId)
                }
            installPreparationJob(job, PreparationTarget.Summary(summaryId))
            job.start()
            return true
        }
    }

    suspend fun recoverOnStartup() {
        repository.loadActiveSummaries().forEach { summary ->
            runCatching {
                recoverActiveSummary(summary)
            }
        }
    }

    internal suspend fun executeDurable(
        summaryId: String,
        generation: Long,
    ) {
        require(summaryId.isNotBlank())
        require(generation >= 1L)
        var summary = repository.find(summaryId) ?: return
        if (summary.executionGeneration != generation) return
        if (summary.status !in AiSummaryStateValue.ACTIVE) {
            publishTerminal(summary)
            return
        }

        summary = when (summary.remoteDispatchState) {
            AiSummaryRemoteDispatchStateValue.NONE -> summary
            AiSummaryRemoteDispatchStateValue.READY_TO_SEND -> {
                val requestId = summary.remoteRequestId
                if (requestId.isNullOrBlank()) {
                    failForGeneration(
                        summaryId = summary.id,
                        generation = generation,
                        transcriptionId = summary.transcriptionId.orEmpty(),
                        error = AiSummaryConfigurationException("AI 总结远端请求状态无效，请重新生成总结"),
                    )
                    return
                }
                if (!repository.resetPreparedRemoteCall(summary.id, generation, requestId)) {
                    val latest = repository.find(summary.id) ?: return
                    if (latest.status !in AiSummaryStateValue.ACTIVE) publishTerminal(latest)
                    return
                }
                repository.find(summary.id) ?: return
            }
            AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT -> {
                markInFlightAmbiguous(summary)
                return
            }
            else -> {
                failForGeneration(
                    summaryId = summary.id,
                    generation = generation,
                    transcriptionId = summary.transcriptionId.orEmpty(),
                    error = AiSummaryConfigurationException("AI 总结远端请求状态无效，请重新生成总结"),
                )
                return
            }
        }

        if (summary.executionGeneration != generation || summary.status !in AiSummaryStateValue.ACTIVE) {
            publishTerminal(summary)
            return
        }

        try {
            executeFrozenSummary(summary, generation)
        } catch (cancelled: CancellationException) {
            val latest =
                withContext(NonCancellable) {
                    runCatching { repository.find(summaryId) }.getOrNull()
                }
            if (
                latest == null ||
                latest.executionGeneration != generation ||
                latest.status !in AiSummaryStateValue.ACTIVE
            ) {
                latest?.let(::publishTerminal)
                return
            }
            throw cancelled
        } catch (error: Throwable) {
            failForGeneration(
                summaryId = summaryId,
                generation = generation,
                transcriptionId = summary.transcriptionId.orEmpty(),
                error = error,
            )
        }
    }

    private fun installPreparationJob(
        job: Job,
        target: PreparationTarget,
    ) {
        preparationJob = job
        preparationTarget = target
        job.invokeOnCompletion {
            synchronized(preparationLock) {
                if (preparationJob === job) {
                    preparationJob = null
                    preparationTarget = null
                }
            }
        }
    }

    private suspend fun prepareNew(
        transcriptionId: String,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
        providerProfileId: String?,
        modelOverride: String?,
    ) {
        var summaryId: String? = null
        try {
            val existing = repository.loadActiveSummaries().firstOrNull()
            if (existing != null) {
                publishRunning(existing)
                return
            }
            val recordingId =
                repository.recordingIdForTranscription(transcriptionId)
                    ?: throw AiSummaryConfigurationException("转写记录不存在")
            if (!isRecordingActive(recordingId)) return

            mutableState.value =
                AiSummaryRunState.Running(
                    summaryId = null,
                    recordingId = recordingId,
                    transcriptionId = transcriptionId,
                    phase = AiSummaryEnginePhase.PREPARING,
                )
            val input = inputBuilder.buildEffective(transcriptionId)
            val profile = resolveProfile(providerProfileId, modelOverride)
            validateProfileForGeneration(profile)
            if (!isRecordingActive(input.recordingId)) {
                throw CancellationException("recording is being deleted")
            }
            summaryId =
                withContext(NonCancellable) {
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
                                    put("inputContentDigest", input.inputContentDigest)
                                    input.transcriptionRevisionId?.let {
                                        put("transcriptionRevisionId", it)
                                    }
                                    template.id?.let { put("templateId", it) }
                                }.toString(),
                            alignmentIdSnapshot = input.alignmentId,
                            sourceLineageSnapshot = lineageSnapshot(input),
                        ),
                    )
                }
            currentCoroutineContext().ensureActive()
            val durable =
                repository.find(summaryId)
                    ?: throw AiSummaryConfigurationException("AI 总结任务创建失败")
            publishRunning(durable)
            workScheduler.enqueue(
                summaryId = durable.id,
                generation = durable.executionGeneration,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                summaryId?.let { id ->
                    repository.find(id)?.let { current ->
                        if (current.executionGeneration >= 1L) {
                            runCatching {
                                repository.cancelForGeneration(id, current.executionGeneration)
                            }
                        }
                        workScheduler.cancel(id)
                    }
                }
                mutableState.value =
                    AiSummaryRunState.Cancelled(
                        summaryId = summaryId,
                        transcriptionId = transcriptionId,
                    )
            }
            throw cancelled
        } catch (error: Throwable) {
            val id = summaryId
            if (id == null) {
                failWithoutSummary(transcriptionId, error)
            } else {
                val generation = repository.find(id)?.executionGeneration ?: 1L
                failForGeneration(id, generation.coerceAtLeast(1L), transcriptionId, error)
            }
        }
    }

    private suspend fun prepareResume(summaryId: String) {
        var transcriptionId = ""
        try {
            val summary =
                repository.find(summaryId)
                    ?: throw AiSummaryConfigurationException("AI 总结记录不存在")
            transcriptionId =
                summary.transcriptionId
                    ?: throw AiSummaryConfigurationException("该总结不是转写文本模式")
            if (summary.status != AiSummaryStateValue.INTERRUPTED) {
                throw AiSummaryConfigurationException("只有中断的总结任务可以继续")
            }
            if (summary.remoteDispatchState != AiSummaryRemoteDispatchStateValue.NONE) {
                throw AiSummaryConfigurationException("上一次请求状态无法确认，需要重新生成总结")
            }
            if (!isRecordingActive(summary.recordingId)) return
            val resumed =
                withContext(NonCancellable) {
                    repository.resumeInterrupted(summaryId)
                }
            if (!resumed) {
                throw AiSummaryConfigurationException("总结任务状态已变化，请刷新后重试")
            }
            currentCoroutineContext().ensureActive()
            val current =
                repository.find(summaryId)
                    ?: throw AiSummaryConfigurationException("AI 总结记录不存在")
            publishRunning(current)
            workScheduler.enqueue(
                summaryId = summaryId,
                generation = current.executionGeneration,
                replaceExisting = true,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                repository.find(summaryId)?.let { current ->
                    if (
                        current.status in AiSummaryStateValue.ACTIVE &&
                        current.executionGeneration >= 1L
                    ) {
                        runCatching {
                            repository.cancelForGeneration(summaryId, current.executionGeneration)
                        }
                        workScheduler.cancel(summaryId)
                    }
                }
                mutableState.value =
                    AiSummaryRunState.Cancelled(
                        summaryId = summaryId,
                        transcriptionId = transcriptionId,
                    )
            }
            throw cancelled
        } catch (error: Throwable) {
            val current = repository.find(summaryId)
            val generation = current?.executionGeneration
            if (current != null && generation != null && generation >= 1L && current.status in AiSummaryStateValue.ACTIVE) {
                failForGeneration(summaryId, generation, transcriptionId, error)
            } else {
                failWithoutSummary(transcriptionId, error, summaryId)
            }
        }
    }

    private suspend fun recoverActiveSummary(summary: AiSummaryEntity) {
        if (summary.executionGeneration < 1L) {
            val transitioned =
                repository.transition(
                    summaryId = summary.id,
                    status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                    errorCode = "REMOTE_RESULT_UNKNOWN",
                    sanitizedErrorMessage = AMBIGUOUS_REMOTE_MESSAGE,
                )
            if (transitioned) repository.find(summary.id)?.let(::publishTerminal)
            return
        }
        when (summary.remoteDispatchState) {
            AiSummaryRemoteDispatchStateValue.NONE -> {
                publishRunning(summary)
                workScheduler.enqueue(summary.id, summary.executionGeneration)
            }
            AiSummaryRemoteDispatchStateValue.READY_TO_SEND -> {
                val requestId = summary.remoteRequestId
                if (requestId.isNullOrBlank()) {
                    failForGeneration(
                        summary.id,
                        summary.executionGeneration,
                        summary.transcriptionId.orEmpty(),
                        AiSummaryConfigurationException("AI 总结远端请求状态无效，请重新生成总结"),
                    )
                    return
                }
                if (repository.resetPreparedRemoteCall(summary.id, summary.executionGeneration, requestId)) {
                    val recovered = repository.find(summary.id) ?: return
                    publishRunning(recovered)
                    workScheduler.enqueue(recovered.id, recovered.executionGeneration)
                } else {
                    repository.find(summary.id)?.let { latest ->
                        if (latest.remoteDispatchState == AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT) {
                            markInFlightAmbiguous(latest)
                        } else if (latest.status !in AiSummaryStateValue.ACTIVE) {
                            publishTerminal(latest)
                        }
                    }
                }
            }
            AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT ->
                markInFlightAmbiguous(summary)
            else ->
                failForGeneration(
                    summary.id,
                    summary.executionGeneration,
                    summary.transcriptionId.orEmpty(),
                    AiSummaryConfigurationException("AI 总结远端请求状态无效，请重新生成总结"),
                )
        }
    }

    private suspend fun executeFrozenSummary(
        summary: AiSummaryEntity,
        generation: Long,
    ) {
        val transcriptionId =
            summary.transcriptionId
                ?: throw AiSummaryConfigurationException("该总结不是转写文本模式")
        if (!isRecordingActive(summary.recordingId)) return

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
        val lineage = parseLineage(summary.sourceLineageSnapshot)
        val input =
            inputBuilder.buildSnapshot(
                transcriptionId = transcriptionId,
                revisionId = lineage.revisionId,
            )
        if (lineage.inputContentDigest != null && lineage.inputContentDigest != input.inputContentDigest) {
            throw AiSummaryConfigurationException("原总结输入内容已无法恢复，请重新生成总结")
        }
        if (!isRecordingActive(summary.recordingId)) return

        runGeneration(
            summaryId = summary.id,
            generation = generation,
            input = input,
            profile = profile,
            mode = mode,
            template = template,
        )
    }

    private suspend fun runGeneration(
        summaryId: String,
        generation: Long,
        input: StructuredTranscriptInput,
        profile: ProviderProfile,
        mode: AiSummaryMode,
        template: SummaryTemplateSpec,
    ) {
        val durableSummary = repository.find(summaryId) ?: return
        if (
            durableSummary.executionGeneration != generation ||
            durableSummary.status !in AiSummaryStateValue.ACTIVE ||
            durableSummary.remoteDispatchState != AiSummaryRemoteDispatchStateValue.NONE
        ) {
            return
        }
        val remoteCallGate =
            RoomSummaryRemoteCallGate(
                repository = repository,
                summaryId = summaryId,
                generation = generation,
            )
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
                                generation = generation,
                            ),
                        remoteCallGate = remoteCallGate,
                    ),
            ) { progress ->
                persistProgress(
                    summaryId = summaryId,
                    generation = generation,
                    inputRecordingId = input.recordingId,
                    inputTranscriptionId = input.transcriptionId,
                    progress = progress,
                )
            }

        currentCoroutineContext().ensureActive()
        val sources =
            input.units.mapNotNull { unit ->
                unit.evidence?.let { evidence -> evidence.ref to evidence }
            }.toMap()
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
                            sourceKind = source.sourceKind.name,
                            sourceId = source.sourceId,
                            startSampleIndex = source.startSampleIndex,
                            endSampleIndexExclusive = source.endSampleIndexExclusive,
                            speakerId = source.speakerId,
                            assignmentQuality = source.assignmentQuality,
                        )
                    }
                }
            }
        currentCoroutineContext().ensureActive()
        val completed =
            repository.persistCompletedForGeneration(
                summaryId = summaryId,
                generation = generation,
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
        if (!completed) return

        output.pendingRemoteRequestId?.let { requestId ->
            runCatching { remoteCallGate.resolve(requestId) }
        }
        mutableState.value =
            AiSummaryRunState.Completed(
                summaryId = summaryId,
                recordingId = input.recordingId,
                transcriptionId = input.transcriptionId,
            )
    }

    private suspend fun persistProgress(
        summaryId: String,
        generation: Long,
        inputRecordingId: String,
        inputTranscriptionId: String,
        progress: AiSummaryEngineProgress,
    ) {
        currentCoroutineContext().ensureActive()
        val status =
            when (progress.phase) {
                AiSummaryEnginePhase.PREPARING -> AiSummaryStateValue.PREPARING
                AiSummaryEnginePhase.ANALYZING -> AiSummaryStateValue.ANALYZING
                AiSummaryEnginePhase.MAPPING -> AiSummaryStateValue.MAPPING
                AiSummaryEnginePhase.REDUCING -> AiSummaryStateValue.REDUCING
                AiSummaryEnginePhase.VALIDATING -> AiSummaryStateValue.VALIDATING
            }
        val transitioned = repository.transitionForGeneration(summaryId, generation, status)
        if (!transitioned) {
            throw CancellationException("AI summary executor is stale or terminal")
        }
        currentCoroutineContext().ensureActive()
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

    private suspend fun resolveProfile(
        providerProfileId: String?,
        modelOverride: String?,
    ): ProviderProfile {
        val snapshot = profileStore.load()
        val selected =
            providerProfileId
                ?.let { requestedId ->
                    snapshot.profiles.firstOrNull {
                        it.providerProfileId == requestedId && it.enabled
                    }
                }
                ?: snapshot.defaultProfileId?.let { defaultId ->
                    snapshot.profiles.firstOrNull {
                        it.providerProfileId == defaultId && it.enabled
                    }
                }
                ?: snapshot.profiles.firstOrNull { it.enabled }
                ?: throw AiSummaryConfigurationException("请先在设置中配置文本大模型")

        val requestedModel = modelOverride?.trim().orEmpty()
        if (requestedModel.isBlank() || requestedModel == selected.defaultModel) {
            return selected
        }
        return selected.copy(
            defaultModel = requestedModel,
            capabilityOverrides = null,
        )
    }

    private fun validateProfileForGeneration(profile: ProviderProfile) {
        if (profile.defaultModel.isBlank()) {
            throw AiSummaryConfigurationException("文本大模型尚未选择模型")
        }
    }

    private suspend fun cancelDurable(summary: AiSummaryEntity) {
        if (summary.status !in AiSummaryStateValue.ACTIVE) return
        val cancelled =
            if (summary.executionGeneration >= 1L) {
                repository.cancelForGeneration(summary.id, summary.executionGeneration)
            } else {
                repository.transition(
                    summaryId = summary.id,
                    status = AiSummaryStateValue.CANCELLED,
                    errorCode = "USER_CANCELLED",
                    sanitizedErrorMessage = "AI 总结生成已取消。",
                )
            }
        if (!cancelled) return
        workScheduler.cancel(summary.id)
        mutableState.value =
            AiSummaryRunState.Cancelled(
                summaryId = summary.id,
                transcriptionId = summary.transcriptionId.orEmpty(),
            )
    }

    private suspend fun markInFlightAmbiguous(summary: AiSummaryEntity) {
        val requestId = summary.remoteRequestId
        if (summary.executionGeneration < 1L || requestId.isNullOrBlank()) {
            failForGeneration(
                summary.id,
                summary.executionGeneration.coerceAtLeast(1L),
                summary.transcriptionId.orEmpty(),
                AiSummaryConfigurationException(AMBIGUOUS_REMOTE_MESSAGE),
            )
            return
        }
        val marked =
            repository.markAmbiguousRemoteResult(
                summaryId = summary.id,
                generation = summary.executionGeneration,
                requestId = requestId,
            )
        if (marked) {
            repository.find(summary.id)?.let(::publishTerminal)
        }
    }

    private suspend fun failForGeneration(
        summaryId: String,
        generation: Long,
        transcriptionId: String,
        error: Throwable,
    ) {
        val current = repository.find(summaryId) ?: return
        if (current.executionGeneration != generation) return
        if (current.status !in AiSummaryStateValue.ACTIVE) {
            publishTerminal(current)
            return
        }
        if (shouldMarkAmbiguousRemoteFailure(error)) {
            val requestId = current.remoteRequestId
            if (
                current.remoteDispatchState == AiSummaryRemoteDispatchStateValue.REQUEST_IN_FLIGHT &&
                !requestId.isNullOrBlank()
            ) {
                val marked =
                    repository.markAmbiguousRemoteResult(
                        summaryId = summaryId,
                        generation = generation,
                        requestId = requestId,
                    )
                if (marked) {
                    repository.find(summaryId)?.let(::publishTerminal)
                    return
                }
            }
        }

        val failure = sanitizeFailure(error)
        val transitioned =
            repository.transitionForGeneration(
                summaryId = summaryId,
                generation = generation,
                status = AiSummaryStateValue.FAILED,
                errorCode = failure.first,
                sanitizedErrorMessage = failure.second,
            )
        if (!transitioned) return
        mutableState.value =
            AiSummaryRunState.Failed(
                summaryId = summaryId,
                recordingId = current.recordingId,
                transcriptionId = transcriptionId,
                errorCode = failure.first,
                message = failure.second,
            )
    }

    private suspend fun failWithoutSummary(
        transcriptionId: String,
        error: Throwable,
        summaryId: String? = null,
    ) {
        val failure = sanitizeFailure(error)
        val recordingId =
            runCatching {
                summaryId?.let { repository.find(it)?.recordingId }
                    ?: repository.recordingIdForTranscription(transcriptionId)
            }.getOrNull()
        mutableState.value =
            AiSummaryRunState.Failed(
                summaryId = summaryId,
                recordingId = recordingId,
                transcriptionId = transcriptionId,
                errorCode = failure.first,
                message = failure.second,
            )
    }

    private fun shouldMarkAmbiguousRemoteFailure(error: Throwable): Boolean {
        val failure = (error as? ProviderFailureCarrier)?.failure ?: return false
        return failure.code == ProviderErrorCode.TIMEOUT ||
            failure.code == ProviderErrorCode.NETWORK_UNAVAILABLE
    }

    private fun publishRunning(summary: AiSummaryEntity) {
        val transcriptionId = summary.transcriptionId ?: return
        mutableState.value =
            AiSummaryRunState.Running(
                summaryId = summary.id,
                recordingId = summary.recordingId,
                transcriptionId = transcriptionId,
                phase = phaseForStatus(summary.status),
            )
    }

    private fun publishTerminal(summary: AiSummaryEntity) {
        val transcriptionId = summary.transcriptionId.orEmpty()
        mutableState.value =
            when (summary.status) {
                AiSummaryStateValue.COMPLETED ->
                    AiSummaryRunState.Completed(
                        summaryId = summary.id,
                        recordingId = summary.recordingId,
                        transcriptionId = transcriptionId,
                    )
                AiSummaryStateValue.CANCELLED ->
                    AiSummaryRunState.Cancelled(
                        summaryId = summary.id,
                        transcriptionId = transcriptionId,
                    )
                AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT ->
                    AiSummaryRunState.Failed(
                        summaryId = summary.id,
                        recordingId = summary.recordingId,
                        transcriptionId = transcriptionId,
                        errorCode = summary.errorCode ?: "REMOTE_RESULT_UNKNOWN",
                        message = summary.sanitizedErrorMessage ?: AMBIGUOUS_REMOTE_MESSAGE,
                    )
                else ->
                    AiSummaryRunState.Failed(
                        summaryId = summary.id,
                        recordingId = summary.recordingId,
                        transcriptionId = transcriptionId,
                        errorCode = summary.errorCode ?: "AI_SUMMARY_FAILED",
                        message = summary.sanitizedErrorMessage ?: "AI 总结生成失败，请重试",
                    )
            }
    }

    private fun phaseForStatus(status: String): AiSummaryEnginePhase =
        when (status) {
            AiSummaryStateValue.MAPPING -> AiSummaryEnginePhase.MAPPING
            AiSummaryStateValue.REDUCING -> AiSummaryEnginePhase.REDUCING
            AiSummaryStateValue.VALIDATING -> AiSummaryEnginePhase.VALIDATING
            AiSummaryStateValue.ANALYZING,
            AiSummaryStateValue.PLANNING,
            -> AiSummaryEnginePhase.ANALYZING
            else -> AiSummaryEnginePhase.PREPARING
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
                if (error.code == SummaryStructuredOutputErrorCode.TRUNCATED_JSON) {
                    "STRUCTURED_OUTPUT_TRUNCATED" to
                        "AI 输出达到模型长度限制，无法完成结构化结果，请重试或更换支持更长输出的模型"
                } else {
                    ("STRUCTURED_OUTPUT_" + error.code.name) to
                        "AI 返回结果结构无法验证，请重试或更换模型"
                }
            else ->
                "AI_SUMMARY_FAILED" to "AI 总结生成失败，请重试"
        }

    private fun lineageSnapshot(input: StructuredTranscriptInput): String =
        buildJsonObject {
            put("lineageVersion", 2)
            put("recordingId", input.recordingId)
            put("transcriptionId", input.transcriptionId)
            if (input.transcriptionRevisionId == null) {
                put("transcriptionRevisionId", JsonNull)
            } else {
                put("transcriptionRevisionId", input.transcriptionRevisionId)
            }
            put("inputContentDigest", input.inputContentDigest)
            put("canonicalAssetId", input.canonicalAssetId)
            put("canonicalSha256", input.canonicalSha256)
            put("canonicalProfileId", input.canonicalProfileId)
            put("totalSampleCount", input.totalSampleCount)
            input.alignmentId?.let { put("alignmentId", it) }
            put("evidenceMappingVersion", 2)
        }.toString()

    private fun parseLineage(raw: String): LineageInputRef =
        runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            LineageInputRef(
                revisionId =
                    root["transcriptionRevisionId"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.contentOrNull,
                inputContentDigest = root["inputContentDigest"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrDefault(LineageInputRef(revisionId = null, inputContentDigest = null))

    private data class LineageInputRef(
        val revisionId: String?,
        val inputContentDigest: String?,
    )

    private fun AiSummaryMode.databaseValue(): String =
        when (this) {
            AiSummaryMode.SMART -> AiSummaryModeValue.SMART
            AiSummaryMode.PRESET -> AiSummaryModeValue.PRESET
            AiSummaryMode.CUSTOM -> AiSummaryModeValue.CUSTOM
        }

    private class AiSummaryConfigurationException(
        val safeMessage: String,
    ) : IllegalArgumentException(safeMessage)

    private companion object {
        const val AMBIGUOUS_REMOTE_MESSAGE =
            "上一次请求状态无法确认，需要手动重试。"
    }
}
