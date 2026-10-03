package io.github.ioannes78.voica.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.AiSummaryRevisionCodec
import io.github.ioannes78.voica.ai.AiSummaryRevisionDocument
import io.github.ioannes78.voica.database.AiSummaryEntity
import io.github.ioannes78.voica.database.AiSummaryRevisionEntity
import io.github.ioannes78.voica.database.ContentVersionDeleteResult
import io.github.ioannes78.voica.database.Stage12CContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiSummaryContentState(
    val summaryId: String? = null,
    val currentRevisionId: String? = null,
    val revisions: List<AiSummaryRevisionEntity> = emptyList(),
    val document: AiSummaryRevisionDocument? = null,
    val loading: Boolean = false,
    val notice: String? = null,
)

class AiSummaryContentViewModel(
    private val repository: Stage12CContentRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AiSummaryContentState())
    val state: StateFlow<AiSummaryContentState> = mutableState.asStateFlow()

    private var boundSummary: AiSummaryEntity? = null
    private var boundOriginal: AiSummaryResult? = null
    private var loadJob: Job? = null
    private var generation = 0L

    fun bind(
        summary: AiSummaryEntity?,
        original: AiSummaryResult?,
    ) {
        if (
            boundSummary?.id == summary?.id &&
            boundOriginal == original &&
            mutableState.value.summaryId == summary?.id
        ) {
            return
        }
        boundSummary = summary
        boundOriginal = original
        generation += 1L
        val expectedGeneration = generation
        loadJob?.cancel()
        if (summary == null) {
            mutableState.value = AiSummaryContentState()
            return
        }
        mutableState.value =
            mutableState.value.copy(
                summaryId = summary.id,
                loading = true,
            )
        loadJob =
            viewModelScope.launch {
                load(summary, original, expectedGeneration)
            }
    }

    fun saveRevision(document: AiSummaryRevisionDocument) {
        val summary = boundSummary ?: return
        viewModelScope.launch {
            runCatching {
                repository.createAiSummaryRevision(
                    summaryId = summary.id,
                    revisionPayloadJson = AiSummaryRevisionCodec.encode(document),
                    revisionSchemaVersion = document.schemaVersion,
                )
            }.onSuccess {
                reload("人工修改已保存。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "保存 AI 总结修改失败",
                    )
            }
        }
    }

    fun restoreOriginal() {
        val summary = boundSummary ?: return
        viewModelScope.launch {
            runCatching {
                repository.setCurrentAiSummaryRevision(summary.id, null)
            }.onSuccess {
                reload("已恢复 AI 原始结果。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "恢复 AI 原始结果失败",
                    )
            }
        }
    }

    fun selectRevision(revisionId: String?) {
        val summary = boundSummary ?: return
        viewModelScope.launch {
            runCatching {
                repository.setCurrentAiSummaryRevision(summary.id, revisionId)
            }.onSuccess {
                reload(null)
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "切换总结修订版本失败",
                    )
            }
        }
    }

    fun deleteRevision(revisionId: String) {
        viewModelScope.launch {
            runCatching {
                repository.deleteAiSummaryRevision(revisionId)
            }.onSuccess { deleted ->
                reload(if (deleted) "总结修订版本已删除。" else "修订版本不存在。")
            }.onFailure { error ->
                mutableState.value =
                    mutableState.value.copy(
                        notice = error.message ?: "删除总结修订版本失败",
                    )
            }
        }
    }

    fun deleteVersion(onDeleted: () -> Unit) {
        val summary = boundSummary ?: return
        viewModelScope.launch {
            when (repository.deleteAiSummaryVersion(summary.id)) {
                ContentVersionDeleteResult.NotFound ->
                    mutableState.value =
                        mutableState.value.copy(notice = "AI 总结版本不存在。")
                ContentVersionDeleteResult.ActiveTask ->
                    mutableState.value =
                        mutableState.value.copy(
                            notice = "AI 总结任务仍在运行，暂时不能删除。",
                        )
                is ContentVersionDeleteResult.ReferencedByAiSummaries ->
                    mutableState.value =
                        mutableState.value.copy(notice = "无法删除 AI 总结版本。")
                is ContentVersionDeleteResult.Deleted -> {
                    boundSummary = null
                    boundOriginal = null
                    mutableState.value =
                        AiSummaryContentState(notice = "AI 总结版本已删除。")
                    onDeleted()
                }
            }
        }
    }

    fun clearNotice() {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    private fun reload(notice: String?) {
        val summary = boundSummary ?: return
        generation += 1L
        val expectedGeneration = generation
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                load(summary, boundOriginal, expectedGeneration, notice)
            }
    }

    private suspend fun load(
        summary: AiSummaryEntity,
        original: AiSummaryResult?,
        expectedGeneration: Long,
        notice: String? = null,
    ) {
        val metadata = repository.loadAiSummaryMetadata(summary.id)
        val revisions = repository.loadAiSummaryRevisions(summary.id)
        val currentRevision =
            metadata?.currentRevisionId?.let { revisionId ->
                repository.loadAiSummaryRevision(revisionId)
                    ?.takeIf { it.aiSummaryId == summary.id }
            }
        if (expectedGeneration != generation) return

        val document =
            when {
                currentRevision != null ->
                    runCatching {
                        AiSummaryRevisionCodec.decode(currentRevision.revisionPayloadJson)
                    }.getOrNull()
                original != null -> AiSummaryRevisionCodec.fromOriginal(original)
                else -> null
            }

        mutableState.value =
            AiSummaryContentState(
                summaryId = summary.id,
                currentRevisionId = currentRevision?.id,
                revisions = revisions,
                document = document,
                loading = false,
                notice = notice,
            )
    }

    class Factory(
        private val repository: Stage12CContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiSummaryContentViewModel(repository) as T
    }
}
