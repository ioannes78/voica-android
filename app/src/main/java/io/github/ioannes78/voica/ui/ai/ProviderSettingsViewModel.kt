package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.ai.ConnectionTestResult
import io.github.ioannes78.voica.ai.ProviderAdapterKind
import io.github.ioannes78.voica.ai.ProviderFailureCarrier
import io.github.ioannes78.voica.ai.ProviderModel
import io.github.ioannes78.voica.ai.ProviderPresetCatalog
import io.github.ioannes78.voica.ai.ProviderPresetIds
import io.github.ioannes78.voica.ai.ProviderProfile
import io.github.ioannes78.voica.ai.StructuredOutputMode
import io.github.ioannes78.voica.ai.StructuredSummaryCompatibility
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderConfigurationRepository
import io.github.ioannes78.voica.llm.ProviderProfileStore
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProviderEditorState(
    val profiles: List<ProviderProfile> = emptyList(),
    val defaultProfileId: String? = null,
    val selectedProfileId: String? = null,
    val persisted: Boolean = false,
    val presetId: String = ProviderPresetIds.OPENAI,
    val displayName: String = "OpenAI",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKeyInput: String = "",
    val credentialConfigured: Boolean = false,
    val defaultModel: String = "",
    val manualContextWindowTokens: String = "",
    val discoveredModels: List<ProviderModel> = emptyList(),
    val busy: Boolean = false,
    val connectionTest: ConnectionTestResult? = null,
    val notice: String? = null,
) {
    val selectedPreset
        get() = ProviderPresetCatalog.find(presetId)

    val stage11Unsupported: Boolean
        get() = selectedPreset?.adapterKind == ProviderAdapterKind.GOOGLE_VERTEX

    val host: String?
        get() = runCatching { URI(baseUrl).host }.getOrNull()
}

class ProviderSettingsViewModel(
    private val profileStore: ProviderProfileStore,
    private val configurationRepository: ProviderConfigurationRepository,
    private val providerRegistry: ProviderAdapterRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProviderEditorState())
    val state: StateFlow<ProviderEditorState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshProfiles(selectId = mutableState.value.selectedProfileId)
        }
    }

    fun newProfile() {
        val preset = ProviderPresetCatalog.find(ProviderPresetIds.OPENAI)!!
        mutableState.value =
            ProviderEditorState(
                profiles = mutableState.value.profiles,
                defaultProfileId = mutableState.value.defaultProfileId,
                selectedProfileId = UUID.randomUUID().toString(),
                persisted = false,
                presetId = preset.presetId,
                displayName = preset.displayName,
                baseUrl = preset.defaultBaseUrl.orEmpty(),
            )
    }

    fun selectProfile(providerProfileId: String) {
        viewModelScope.launch {
            val snapshot = profileStore.load()
            val profile =
                snapshot.profiles.firstOrNull { it.providerProfileId == providerProfileId }
                    ?: return@launch
            mutableState.value =
                editorFor(
                    profile = profile,
                    profiles = snapshot.profiles,
                    defaultProfileId = snapshot.defaultProfileId,
                    credentialConfigured =
                        configurationRepository.hasCredential(profile.credentialRef),
                )
        }
    }

    fun setPreset(presetId: String) {
        val current = mutableState.value
        val preset = ProviderPresetCatalog.find(presetId) ?: return
        mutableState.value =
            current.copy(
                presetId = presetId,
                displayName =
                    if (!current.persisted ||
                        current.displayName == current.selectedPreset?.displayName
                    ) {
                        preset.displayName
                    } else {
                        current.displayName
                    },
                baseUrl =
                    preset.defaultBaseUrl
                        ?: if (current.presetId == presetId) current.baseUrl else "",
                discoveredModels = emptyList(),
                connectionTest = null,
                notice =
                    if (preset.adapterKind == ProviderAdapterKind.GOOGLE_VERTEX) {
                        "Vertex AI 需要 Google Cloud OAuth；Stage 11 不在手机中保存服务账号私钥，当前仅保留接口契约。"
                    } else {
                        null
                    },
            )
    }

    fun setDisplayName(value: String) {
        mutableState.value = mutableState.value.copy(displayName = value)
    }

    fun setBaseUrl(value: String) {
        mutableState.value = mutableState.value.copy(baseUrl = value)
    }

    fun setApiKey(value: String) {
        mutableState.value = mutableState.value.copy(apiKeyInput = value)
    }

    fun setModel(value: String) {
        mutableState.value =
            mutableState.value.copy(
                defaultModel = value,
                connectionTest = null,
                notice = null,
            )
    }

    fun setManualContextWindow(value: String) {
        mutableState.value =
            mutableState.value.copy(
                manualContextWindowTokens = value.filter(Char::isDigit).take(9),
            )
    }

    fun save() {
        val current = mutableState.value
        if (current.stage11Unsupported) {
            mutableState.value =
                current.copy(
                    notice = "Vertex AI 标准 OAuth 尚未在 Stage 11 开放，请选择 Gemini API/AI Studio 或其他 Provider。",
                )
            return
        }
        viewModelScope.launch {
            mutableState.value = current.copy(busy = true, notice = null)
            val result =
                runCatching {
                    val id =
                        current.selectedProfileId
                            ?.takeIf { it.isNotBlank() }
                            ?: UUID.randomUUID().toString()
                    val credentialRef = "provider-" + id
                    val manualContext =
                        current.manualContextWindowTokens
                            .takeIf { it.isNotBlank() }
                            ?.toIntOrNull()
                    val profile =
                        ProviderProfile(
                            providerProfileId = id,
                            presetId = current.presetId,
                            displayName = current.displayName.trim(),
                            baseUrl = current.baseUrl.trim(),
                            credentialRef = credentialRef,
                            defaultModel = current.defaultModel.trim(),
                            timeoutMs = 60_000L,
                            manualContextWindowTokens = manualContext,
                            capabilityOverrides = null,
                            enabled = true,
                        )
                    configurationRepository.save(
                        profile = profile,
                        apiKey = current.apiKeyInput.takeIf { it.isNotBlank() },
                    )
                    val snapshot = profileStore.load()
                    if (snapshot.defaultProfileId == null) {
                        profileStore.setDefault(profile.providerProfileId)
                    }
                    profile.providerProfileId
                }
            result.onSuccess { id ->
                refreshProfiles(
                    selectId = id,
                    notice = "Provider 配置已保存；API Key 仅以加密凭据保存在本机。",
                )
            }.onFailure { error ->
                mutableState.value =
                    current.copy(
                        busy = false,
                        notice = safeError(error, "Provider 配置保存失败"),
                    )
            }
        }
    }

    fun setDefault() {
        val id = mutableState.value.selectedProfileId ?: return
        if (!mutableState.value.persisted) return
        viewModelScope.launch {
            runCatching { profileStore.setDefault(id) }
                .onSuccess {
                    refreshProfiles(
                        selectId = id,
                        notice = "已设为默认文本模型 Provider。",
                    )
                }
                .onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
                            notice = safeError(error, "设置默认 Provider 失败"),
                        )
                }
        }
    }

    fun deleteSelected() {
        val current = mutableState.value
        val id = current.selectedProfileId ?: return
        if (!current.persisted) {
            newProfile()
            return
        }
        viewModelScope.launch {
            mutableState.value = current.copy(busy = true)
            runCatching { configurationRepository.delete(id) }
                .onSuccess {
                    val snapshot = profileStore.load()
                    val next =
                        snapshot.defaultProfileId
                            ?: snapshot.profiles.firstOrNull()?.providerProfileId
                    if (next == null) {
                        mutableState.value =
                            ProviderEditorState(
                                profiles = emptyList(),
                                defaultProfileId = null,
                                notice = "Provider 已删除。",
                            )
                    } else {
                        refreshProfiles(
                            selectId = next,
                            notice = "Provider 已删除。",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.value =
                        current.copy(
                            busy = false,
                            notice = safeError(error, "删除 Provider 失败"),
                        )
                }
        }
    }

    fun discoverModels() {
        val current = mutableState.value
        val profile = current.persistedProfileOrNull()
        if (profile == null) {
            mutableState.value =
                current.copy(notice = "请先保存 Provider 配置，再获取模型列表。")
            return
        }
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(
                    busy = true,
                    notice = "正在获取可用模型…",
                    connectionTest = null,
                )
            val result =
                runCatching {
                    providerRegistry.forProfile(profile)
                        .discoverModels(profile)
                        .getOrThrow()
                }
            result.onSuccess { models ->
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        discoveredModels = models.distinctBy { it.id }.sortedBy { it.id },
                        notice =
                            if (models.isEmpty()) {
                                "Provider 未返回可用模型；可继续手动填写模型 ID。"
                            } else {
                                "已获取 " + models.size + " 个模型；请选择后保存。"
                            },
                    )
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        notice =
                            safeError(
                                error,
                                "模型自动获取失败；不会清除当前手动模型 ID。",
                            ),
                    )
            }
        }
    }

    fun testConnection() {
        val current = mutableState.value
        val profile = current.persistedProfileOrNull()
        if (profile == null) {
            mutableState.value =
                current.copy(notice = "请先保存 Provider 配置，再执行连接测试。")
            return
        }
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(
                    busy = true,
                    notice = "正在执行连接测试；不会上传真实转写内容。",
                    connectionTest = null,
                )
            val result =
                runCatching {
                    providerRegistry.forProfile(profile).testConnection(profile)
                }
            result.onSuccess { test ->
                val structuredProbe = test.structuredSummaryProbe
                val negotiatedMode = structuredProbe?.mode
                val negotiatedCompatibility =
                    structuredProbe?.compatibility
                val negotiatedCaps =
                    test.capabilities?.let { capabilities ->
                        when (negotiatedCompatibility) {
                            StructuredSummaryCompatibility.VERIFIED_STRICT ->
                                capabilities.copy(
                                    supportsJsonSchema = true,
                                    supportsJsonObject = true,
                                )
                            StructuredSummaryCompatibility.VERIFIED_COMPATIBLE ->
                                capabilities.copy(
                                    supportsJsonSchema = false,
                                    supportsJsonObject =
                                        negotiatedMode == StructuredOutputMode.JSON_OBJECT,
                                )
                            else -> null
                        }
                    }
                if (test.success && negotiatedCaps != null) {
                    viewModelScope.launch {
                        profileStore.upsert(
                            profile.copy(capabilityOverrides = negotiatedCaps),
                        )
                    }
                }
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        connectionTest = test,
                        discoveredModels =
                            if (test.models.isNotEmpty()) {
                                test.models.distinctBy { it.id }.sortedBy { it.id }
                            } else {
                                mutableState.value.discoveredModels
                            },
                        notice =
                            if (test.success) {
                                when (negotiatedCompatibility) {
                                    StructuredSummaryCompatibility.VERIFIED_STRICT ->
                                        "连接测试成功；智能总结结构化输出已验证（严格模式）；测试未上传真实转写内容。"
                                    StructuredSummaryCompatibility.VERIFIED_COMPATIBLE ->
                                        "连接测试成功；智能总结结构化输出已验证（兼容模式）；测试未上传真实转写内容。"
                                    StructuredSummaryCompatibility.INCOMPATIBLE ->
                                        structuredProbe?.failure?.sanitizedMessage
                                            ?: "连接正常，但当前模型未通过智能总结结构化输出测试."
                                    StructuredSummaryCompatibility.NOT_TESTED,
                                    null,
                                    -> "连接测试成功；测试未上传真实转写内容。"
                                }
                            } else {
                                test.failure?.sanitizedMessage ?: "连接测试失败。"
                            },
                    )
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        busy = false,
                        notice = safeError(error, "连接测试失败"),
                    )
            }
        }
    }

    private suspend fun refreshProfiles(
        selectId: String?,
        notice: String? = null,
    ) {
        val snapshot = profileStore.load()
        val selected =
            selectId?.let { id ->
                snapshot.profiles.firstOrNull { it.providerProfileId == id }
            } ?: snapshot.defaultProfileId?.let { id ->
                snapshot.profiles.firstOrNull { it.providerProfileId == id }
            } ?: snapshot.profiles.firstOrNull()

        if (selected == null) {
            val preset = ProviderPresetCatalog.find(ProviderPresetIds.OPENAI)!!
            mutableState.value =
                ProviderEditorState(
                    profiles = emptyList(),
                    defaultProfileId = null,
                    selectedProfileId = UUID.randomUUID().toString(),
                    persisted = false,
                    presetId = preset.presetId,
                    displayName = preset.displayName,
                    baseUrl = preset.defaultBaseUrl.orEmpty(),
                    notice = notice,
                )
            return
        }

        mutableState.value =
            editorFor(
                profile = selected,
                profiles = snapshot.profiles,
                defaultProfileId = snapshot.defaultProfileId,
                credentialConfigured =
                    configurationRepository.hasCredential(selected.credentialRef),
                notice = notice,
            )
    }

    private fun editorFor(
        profile: ProviderProfile,
        profiles: List<ProviderProfile>,
        defaultProfileId: String?,
        credentialConfigured: Boolean,
        notice: String? = null,
    ): ProviderEditorState =
        ProviderEditorState(
            profiles = profiles,
            defaultProfileId = defaultProfileId,
            selectedProfileId = profile.providerProfileId,
            persisted = true,
            presetId = profile.presetId,
            displayName = profile.displayName,
            baseUrl = profile.baseUrl,
            apiKeyInput = "",
            credentialConfigured = credentialConfigured,
            defaultModel = profile.defaultModel,
            manualContextWindowTokens =
                profile.manualContextWindowTokens?.toString().orEmpty(),
            notice = notice,
        )

    private fun ProviderEditorState.persistedProfileOrNull(): ProviderProfile? {
        val id = selectedProfileId ?: return null
        return profiles.firstOrNull { it.providerProfileId == id }
    }

    private fun safeError(
        error: Throwable,
        fallback: String,
    ): String =
        (error as? ProviderFailureCarrier)
            ?.failure
            ?.sanitizedMessage
            ?: error.message?.take(160)
            ?: fallback

    class Factory(
        private val profileStore: ProviderProfileStore,
        private val configurationRepository: ProviderConfigurationRepository,
        private val providerRegistry: ProviderAdapterRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProviderSettingsViewModel(
                profileStore = profileStore,
                configurationRepository = configurationRepository,
                providerRegistry = providerRegistry,
            ) as T
    }
}
