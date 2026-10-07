package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.AiSummaryCoordinator
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.ai.AiSummaryMode
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.AiSummarySectionType
import io.github.ioannes78.voica.ai.ProviderModel
import io.github.ioannes78.voica.ai.ProviderProfile
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.SummaryTemplateCatalog
import io.github.ioannes78.voica.ai.SummaryTemplateSnapshotCodec
import io.github.ioannes78.voica.ai.SummaryTemplateSpec
import io.github.ioannes78.voica.database.AiCustomTemplateEntity
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryEvidenceEntity
import io.github.ioannes78.voica.database.AiSummaryModeValue
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import io.github.ioannes78.voica.database.SaveAiCustomTemplateRequest
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderProfileStore
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AiSummaryProviderPreview(
    val providerProfileId: String,
    val displayName: String,
    val model: String,
    val host: String,
    val structuredCompatibilityLabel: String?,
)

data class AiSummaryDocument(
    val entity: AiSummaryEntity,
    val result: AiSummaryResult?,
    val evidence: List<AiSummaryEvidenceEntity>,
) {
    val evidenceByRef: Map<String, AiSummaryEvidenceEntity> =
        evidence.associateBy { it.sourceRef }
}

class AiSummaryViewModel(
    private val coordinator: AiSummaryCoordinator,
    private val repository: AiSummaryRepository,
    private val profileStore: ProviderProfileStore,
    private val providerRegistry: ProviderAdapterRegistry,
    private val contentRepository: Stage12CContentRepository,
) : ViewModel() {
    val runState: StateFlow<AiSummaryRunState> = coordinator.state

    private val mutableHistory = MutableStateFlow<List<AiSummaryEntity>>(emptyList())
    val history: StateFlow<List<AiSummaryEntity>> =
        mutableHistory
            .map { summaries ->
                summaries.filter { it.status == AiSummaryStateValue.COMPLETED }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

    private val mutableSelected = MutableStateFlow<AiSummaryDocument?>(null)
    val selected: StateFlow<AiSummaryDocument?> = mutableSelected.asStateFlow()

    private val mutableCandidateId = MutableStateFlow<String?>(null)
    val candidateId: StateFlow<String?> = mutableCandidateId.asStateFlow()

    private val mutableStale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = mutableStale.asStateFlow()

    private val mutableProvider = MutableStateFlow<AiSummaryProviderPreview?>(null)
    val provider: StateFlow<AiSummaryProviderPreview?> = mutableProvider.asStateFlow()

    private val mutableProviders =
        MutableStateFlow<List<AiSummaryProviderPreview>>(emptyList())
    val providers: StateFlow<List<AiSummaryProviderPreview>> =
        mutableProviders.asStateFlow()

    private val mutableGenerationModels =
        MutableStateFlow<List<ProviderModel>>(emptyList())
    val generationModels: StateFlow<List<ProviderModel>> =
        mutableGenerationModels.asStateFlow()

    private val mutableCustomTemplates =
        MutableStateFlow<List<AiCustomTemplateEntity>>(emptyList())
    val customTemplates: StateFlow<List<AiCustomTemplateEntity>> =
        mutableCustomTemplates.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private var boundTranscriptionId: String? = null
    private var boundRecordingId: String? = null
    private var historyJob: Job? = null
    private var revisionJob: Job? = null
    private var generationModelsProfileId: String? = null

    init {
        viewModelScope.launch {
            repository.observeCustomTemplates().collectLatest {
                mutableCustomTemplates.value = it
            }
        }
        viewModelScope.launch {
            coordinator.state.collectLatest { state ->
                when (state) {
                    is AiSummaryRunState.Completed -> {
                        if (state.recordingId == boundRecordingId) {
                            mutableCandidateId.value =
                                contentRepository.resolveAiSummaryCandidateId(state.recordingId)
                            if (mutableCandidateId.value == state.summaryId) {
                                mutableNotice.value = "新的总结结果已生成。"
                            }
                            refreshCurrentSelection()
                        }
                    }
                    is AiSummaryRunState.Failed -> {
                        if (state.recordingId == boundRecordingId) {
                            mutableNotice.value = state.message
                        }
                    }
                    is AiSummaryRunState.Cancelled -> {
                        if (state.transcriptionId == boundTranscriptionId) {
                            mutableNotice.value = "总结已取消。"
                        }
                    }
                    AiSummaryRunState.Idle,
                    is AiSummaryRunState.Running,
                    -> Unit
                }
            }
        }
    }

    fun bind(transcriptionId: String?) {
        refreshProvider()
        if (boundTranscriptionId == transcriptionId) return
        boundTranscriptionId = transcriptionId
        boundRecordingId = null
        historyJob?.cancel()
        revisionJob?.cancel()
        mutableHistory.value = emptyList()
        mutableSelected.value = null
        mutableCandidateId.value = null
        mutableStale.value = false
        if (transcriptionId == null) return

        viewModelScope.launch {
            val recordingId = repository.recordingIdForTranscription(transcriptionId) ?: return@launch
            if (boundTranscriptionId != transcriptionId) return@launch
            boundRecordingId = recordingId
            historyJob =
                viewModelScope.launch {
                    repository.observeForRecording(recordingId)
                        .collectLatest { summaries ->
                            mutableHistory.value = summaries
                            mutableCandidateId.value =
                                contentRepository.resolveAiSummaryCandidateId(recordingId)
                            refreshCurrentSelection()
                        }
                }
            revisionJob =
                viewModelScope.launch {
                    contentRepository.observeTranscriptionMetadata(transcriptionId)
                        .collectLatest {
                            refreshStale()
                        }
                }
        }
    }

    fun refreshProvider() {
        viewModelScope.launch {
            val snapshot = profileStore.load()
            val enabled = snapshot.profiles.filter { it.enabled }
            mutableProviders.value = enabled.map { it.toPreview() }
            val profile =
                snapshot.defaultProfileId?.let { id ->
                    enabled.firstOrNull { it.providerProfileId == id }
                } ?: enabled.firstOrNull()
            mutableProvider.value = profile?.toPreview()
        }
    }

    fun loadGenerationModels(providerProfileId: String) {
        generationModelsProfileId = providerProfileId
        viewModelScope.launch {
            val snapshot = profileStore.load()
            val profile =
                snapshot.profiles.firstOrNull {
                    it.providerProfileId == providerProfileId && it.enabled
                }
            if (profile == null) {
                if (generationModelsProfileId == providerProfileId) {
                    mutableGenerationModels.value = emptyList()
                }
                return@launch
            }
            val configuredDefault =
                listOf(
                    ProviderModel(
                        id = profile.defaultModel,
                        displayName = profile.defaultModel,
                    ),
                ).filter { it.id.isNotBlank() }
            if (generationModelsProfileId == providerProfileId) {
                mutableGenerationModels.value = configuredDefault
            }
            val discovered =
                runCatching {
                    providerRegistry.forProfile(profile)
                        .discoverModels(profile)
                        .getOrThrow()
                }.getOrDefault(emptyList())
            if (generationModelsProfileId == providerProfileId) {
                mutableGenerationModels.value =
                    (configuredDefault + discovered)
                        .distinctBy { it.id }
                        .sortedBy { it.displayName.lowercase() }
            }
        }
    }

    fun generateSmart(
        providerProfileId: String? = null,
        model: String? = null,
    ): Boolean {
        val transcriptionId = boundTranscriptionId ?: return false
        mutableNotice.value = null
        return coordinator.startSmart(
            transcriptionId = transcriptionId,
            providerProfileId = providerProfileId,
            modelOverride = model,
        ).also { started ->
            if (!started) mutableNotice.value = "已有总结任务正在运行。"
        }
    }

    fun generatePreset(
        presetId: String,
        providerProfileId: String? = null,
        model: String? = null,
    ): Boolean {
        val transcriptionId = boundTranscriptionId ?: return false
        val template = SummaryTemplateCatalog.find(presetId) ?: return false
        mutableNotice.value = null
        return coordinator.start(
            transcriptionId = transcriptionId,
            mode = AiSummaryMode.PRESET,
            template = template,
            providerProfileId = providerProfileId,
            modelOverride = model,
        ).also { started ->
            if (!started) mutableNotice.value = "已有总结任务正在运行。"
        }
    }

    fun generateCustom(
        templateId: String,
        providerProfileId: String? = null,
        model: String? = null,
    ): Boolean {
        val transcriptionId = boundTranscriptionId ?: return false
        val entity = mutableCustomTemplates.value.firstOrNull { it.id == templateId } ?: return false
        val template =
            runCatching { SummaryTemplateSnapshotCodec.decode(entity.sectionsConfigJson) }
                .getOrElse {
                    mutableNotice.value = "自定义模板数据无效。"
                    return false
                }
        mutableNotice.value = null
        return coordinator.start(
            transcriptionId = transcriptionId,
            mode = AiSummaryMode.CUSTOM,
            template = template,
            providerProfileId = providerProfileId,
            modelOverride = model,
        ).also { started ->
            if (!started) mutableNotice.value = "已有总结任务正在运行。"
        }
    }

    fun saveCustomTemplate(
        name: String,
        sections: Set<AiSummarySectionType>,
        focus: String,
        instruction: String,
    ) {
        if (name.isBlank()) {
            mutableNotice.value = "请输入自定义模板名称。"
            return
        }
        if (sections.isEmpty()) {
            mutableNotice.value = "自定义模板至少选择一个输出章节。"
            return
        }
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val template =
                SummaryTemplateSpec(
                    id = id,
                    name = name.trim(),
                    sections = sections.toList(),
                    focus = focus.trim().takeIf { it.isNotBlank() },
                    userInstruction = instruction.trim().takeIf { it.isNotBlank() },
                )
            runCatching {
                repository.saveCustomTemplate(
                    SaveAiCustomTemplateRequest(
                        id = id,
                        name = template.name,
                        schemaVersion = 1,
                        sectionsConfigJson = SummaryTemplateSnapshotCodec.encode(template),
                        focus = template.focus,
                        userInstruction = template.userInstruction,
                    ),
                )
            }.onSuccess {
                mutableNotice.value = "自定义模板已保存。"
            }.onFailure {
                mutableNotice.value = "自定义模板保存失败。"
            }
        }
    }

    fun deleteCustomTemplate(templateId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteCustomTemplate(templateId) }
                .onSuccess { mutableNotice.value = "自定义模板已删除。" }
                .onFailure { mutableNotice.value = "删除自定义模板失败。" }
        }
    }

    fun cancel() {
        coordinator.cancel()
    }

    /** Preview only. Product current selection is changed only by [adoptSummaryResult]. */
    fun selectSummary(summaryId: String) {
        viewModelScope.launch {
            val entity =
                mutableHistory.value.firstOrNull { it.id == summaryId }
                    ?: repository.find(summaryId)
                    ?: return@launch
            loadDocument(entity)
            refreshStale()
        }
    }

    /** Explicit product action: "使用新结果". */
    fun adoptSummaryResult(summaryId: String) {
        viewModelScope.launch {
            val entity = repository.find(summaryId) ?: return@launch
            if (entity.status != AiSummaryStateValue.COMPLETED) return@launch
            contentRepository.setCurrentAiSummaryVersion(entity.recordingId, entity.id)
            mutableCandidateId.value = contentRepository.resolveAiSummaryCandidateId(entity.recordingId)
            loadDocument(entity)
            refreshStale()
            mutableNotice.value = "已使用新的总结结果。"
        }
    }

    fun resumeSelected(): Boolean {
        val entity = mutableSelected.value?.entity ?: return false
        if (entity.status != AiSummaryStateValue.INTERRUPTED) return false
        mutableNotice.value = null
        return coordinator.resumeInterrupted(entity.id).also { started ->
            if (!started) mutableNotice.value = "已有总结任务正在运行。"
        }
    }

    fun regenerateSelected(): Boolean {
        val entity = mutableSelected.value?.entity ?: return false
        val transcriptionId = boundTranscriptionId ?: entity.transcriptionId ?: return false
        val mode =
            when (entity.mode) {
                AiSummaryModeValue.PRESET -> AiSummaryMode.PRESET
                AiSummaryModeValue.CUSTOM -> AiSummaryMode.CUSTOM
                else -> AiSummaryMode.SMART
            }
        val template =
            entity.templateSnapshot
                ?.let { runCatching { SummaryTemplateSnapshotCodec.decode(it) }.getOrNull() }
                ?: entity.templateId?.let(SummaryTemplateCatalog::find)
                ?: SummaryTemplateCatalog.smart()
        mutableNotice.value = null
        return coordinator.start(
            transcriptionId = transcriptionId,
            mode = mode,
            template = template,
            providerProfileId = entity.providerProfileId,
            modelOverride = entity.model,
        ).also { started ->
            if (!started) mutableNotice.value = "已有总结任务正在运行。"
        }
    }

    fun acknowledgeTerminal(summaryId: String) {
        if (summaryId.isBlank()) return
        viewModelScope.launch {
            val entity = repository.find(summaryId) ?: return@launch
            if (entity.transcriptionId != boundTranscriptionId) return@launch
            when (entity.status) {
                AiSummaryStateValue.COMPLETED -> {
                    mutableCandidateId.value =
                        contentRepository.resolveAiSummaryCandidateId(entity.recordingId)
                    if (mutableCandidateId.value == entity.id) {
                        mutableNotice.value = "新的总结结果已生成。"
                    }
                    refreshCurrentSelection()
                }
                AiSummaryStateValue.FAILED,
                AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                -> {
                    mutableNotice.value =
                        entity.sanitizedErrorMessage ?: "AI 总结生成失败，请重试"
                }
                else -> return@launch
            }
            repository.acknowledgeTerminal(summaryId)
        }
    }

    fun clearNotice() {
        mutableNotice.value = null
    }

    private suspend fun refreshCurrentSelection() {
        val recordingId = boundRecordingId ?: return
        val summaries = mutableHistory.value
        val currentId = contentRepository.resolveCurrentAiSummaryId(recordingId)
        val target =
            summaries.firstOrNull {
                it.id == currentId && it.status == AiSummaryStateValue.COMPLETED
            } ?: summaries.firstOrNull { it.status == AiSummaryStateValue.INTERRUPTED }
        if (target == null) {
            mutableSelected.value = null
            mutableStale.value = false
        } else if (mutableSelected.value?.entity?.id != target.id) {
            loadDocument(target)
            refreshStale()
        } else {
            refreshStale()
        }
    }

    private suspend fun refreshStale() {
        val recordingId = boundRecordingId ?: return
        val summary = mutableSelected.value?.entity
        if (summary == null || summary.status != AiSummaryStateValue.COMPLETED) {
            mutableStale.value = false
            return
        }
        val effective = contentRepository.resolveEffectiveTranscription(recordingId)
        val lineage = parseSummaryLineage(summary.sourceLineageSnapshot)
        mutableStale.value =
            isSummaryStale(
                lineageTranscriptionId = lineage.transcriptionId,
                lineageRevisionId = lineage.revisionId,
                effective = effective,
            )
    }

    private fun parseSummaryLineage(raw: String): SummaryLineageRef =
        runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            SummaryLineageRef(
                transcriptionId = root["transcriptionId"]?.jsonPrimitive?.contentOrNull,
                revisionId =
                    root["transcriptionRevisionId"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.contentOrNull,
            )
        }.getOrDefault(SummaryLineageRef(null, null))

    private suspend fun loadDocument(entity: AiSummaryEntity) {
        val evidence = repository.loadEvidence(entity.id)
        val result =
            entity.structuredPayloadJson?.let { raw ->
                runCatching {
                    SummaryResultCodec.decode(
                        raw = raw,
                        allowedEvidenceRefs = evidence.mapTo(LinkedHashSet()) { it.sourceRef },
                    )
                }.getOrNull()
            }
        mutableSelected.value =
            AiSummaryDocument(
                entity = entity,
                result = result,
                evidence = evidence,
            )
    }

    private fun ProviderProfile.toPreview(): AiSummaryProviderPreview =
        AiSummaryProviderPreview(
            providerProfileId = providerProfileId,
            displayName = displayName,
            model = defaultModel,
            host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty(),
            structuredCompatibilityLabel =
                capabilityOverrides?.let { capabilities ->
                    when {
                        capabilities.supportsJsonSchema -> "结构化输出已验证"
                        capabilities.supportsJsonObject -> "兼容模式"
                        else -> "结构化输出未验证"
                    }
                },
        )

    private data class SummaryLineageRef(
        val transcriptionId: String?,
        val revisionId: String?,
    )

    class Factory(
        private val coordinator: AiSummaryCoordinator,
        private val repository: AiSummaryRepository,
        private val profileStore: ProviderProfileStore,
        private val providerRegistry: ProviderAdapterRegistry,
        private val contentRepository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiSummaryViewModel(
                coordinator = coordinator,
                repository = repository,
                profileStore = profileStore,
                providerRegistry = providerRegistry,
                contentRepository = contentRepository,
            ) as T
    }
}
