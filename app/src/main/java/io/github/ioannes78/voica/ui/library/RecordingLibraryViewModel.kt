package io.github.ioannes78.voica.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.database.FolderEntity
import io.github.ioannes78.voica.database.LibraryQueryCriteria
import io.github.ioannes78.voica.database.LibrarySort
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.RecordingLibraryRow
import io.github.ioannes78.voica.database.TagEntity
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordingLibraryUiState(
    val recordings: List<RecordingLibraryRow> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val query: String = "",
    val criteria: LibraryQueryCriteria = LibraryQueryCriteria(),
    val selectedIds: Set<String> = emptySet(),
    val operationMessage: String? = null,
) {
    val selectionMode: Boolean
        get() = selectedIds.isNotEmpty()

    val selectedCount: Int
        get() = selectedIds.size
}

@OptIn(FlowPreview::class)
class RecordingLibraryViewModel(
    private val repository: RecordingLibraryRepository,
) : ViewModel() {
    private val queryText = MutableStateFlow("")
    private val criteria = MutableStateFlow(LibraryQueryCriteria())
    private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val operationMessage = MutableStateFlow<String?>(null)

    private val effectiveCriteria =
        combine(
            queryText.debounce(275),
            criteria,
        ) { query, current ->
            val date = LibraryDateQueryParser.parse(query)
            if (date == null) {
                current.copy(
                    query = query,
                    searchDateFromLocalIso = null,
                    searchDateToLocalIsoExclusive = null,
                    searchDateFromMs = null,
                    searchDateToMsExclusive = null,
                )
            } else {
                current.copy(
                    query = "",
                    searchDateFromLocalIso = date.fromLocalIso,
                    searchDateToLocalIsoExclusive = date.toLocalIsoExclusive,
                    searchDateFromMs = date.fromEpochMs,
                    searchDateToMsExclusive = date.toEpochMsExclusive,
                )
            }
        }

    private val visibleRecordings =
        effectiveCriteria
            .flatMapLatest(repository::observeLibrary)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    private val libraryData =
        combine(
            visibleRecordings,
            repository.folders,
            repository.tags,
        ) { recordings, folders, tags ->
            LibraryData(recordings, folders, tags)
        }

    private val interactionState =
        combine(
            queryText,
            criteria,
            selectedIds,
            operationMessage,
        ) { query, currentCriteria, selected, message ->
            InteractionState(
                query = query,
                criteria = currentCriteria,
                selectedIds = selected,
                operationMessage = message,
            )
        }

    val uiState: StateFlow<RecordingLibraryUiState> =
        combine(
            libraryData,
            interactionState,
        ) { data, interaction ->
            val visibleIds = data.recordings.mapTo(mutableSetOf()) { it.id }
            val stillSelected = interaction.selectedIds.intersect(visibleIds)
            RecordingLibraryUiState(
                recordings = data.recordings,
                folders = data.folders,
                tags = data.tags,
                query = interaction.query,
                criteria = interaction.criteria,
                selectedIds = stillSelected,
                operationMessage = interaction.operationMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecordingLibraryUiState(),
        )

    fun setQuery(value: String) {
        queryText.value = value
    }

    fun setSort(value: LibrarySort) {
        criteria.value = criteria.value.copy(sort = value)
    }

    fun setFavoriteOnly(enabled: Boolean) {
        criteria.value = criteria.value.copy(favoriteOnly = enabled)
    }

    fun setCompletedTranscriptionOnly(enabled: Boolean) {
        criteria.value = criteria.value.copy(completedTranscriptionOnly = enabled)
    }

    fun setCompletedSummaryOnly(enabled: Boolean) {
        criteria.value = criteria.value.copy(completedSummaryOnly = enabled)
    }

    fun setFolderFilter(folderId: String?) {
        criteria.value =
            criteria.value.copy(
                folderId = folderId,
                uncategorizedOnly = false,
            )
    }

    fun setUncategorizedOnly(enabled: Boolean) {
        criteria.value =
            criteria.value.copy(
                folderId = null,
                uncategorizedOnly = enabled,
            )
    }

    fun toggleTagFilter(tagId: String) {
        val current = criteria.value.tagIds
        criteria.value =
            criteria.value.copy(
                tagIds =
                    if (tagId in current) {
                        current - tagId
                    } else {
                        current + tagId
                    },
            )
    }

    fun toggleSourceType(sourceType: String) {
        val current = criteria.value.sourceTypes
        criteria.value =
            criteria.value.copy(
                sourceTypes =
                    if (sourceType in current) {
                        current - sourceType
                    } else {
                        current + sourceType
                    },
            )
    }

    fun clearFilters() {
        criteria.value =
            LibraryQueryCriteria(
                query = "",
                sort = criteria.value.sort,
            )
        queryText.value = ""
        clearSelection()
    }

    fun toggleSelection(recordingId: String) {
        selectedIds.value =
            if (recordingId in selectedIds.value) {
                selectedIds.value - recordingId
            } else {
                selectedIds.value + recordingId
            }
    }

    fun enterSelection(recordingId: String) {
        selectedIds.value = selectedIds.value + recordingId
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun selectAllVisible() {
        selectedIds.value = visibleRecordings.value.mapTo(linkedSetOf()) { it.id }
    }

    fun toggleFavorite(recordingId: String) {
        val row = visibleRecordings.value.firstOrNull { it.id == recordingId } ?: return
        viewModelScope.launch {
            repository.setFavorite(listOf(recordingId), !row.isFavorite)
        }
    }

    fun setSelectedFavorite(favorite: Boolean) {
        val ids = uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setFavorite(ids, favorite)
            operationMessage.value = if (favorite) "已收藏 ${ids.size} 条录音" else "已取消收藏 ${ids.size} 条录音"
        }
    }

    fun moveSelectedToFolder(folderId: String?) {
        val ids = uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.moveToFolder(ids, folderId) }
                .onSuccess {
                    operationMessage.value =
                        if (folderId == null) "已移出文件夹" else "已移动 ${ids.size} 条录音"
                }
                .onFailure { operationMessage.value = it.message ?: "移动失败" }
        }
    }

    fun addTagToSelected(tagId: String) {
        val ids = uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.addTag(ids, tagId) }
                .onSuccess { operationMessage.value = "已添加标签" }
                .onFailure { operationMessage.value = it.message ?: "添加标签失败" }
        }
    }

    fun removeTagFromSelected(tagId: String) {
        val ids = uiState.value.selectedIds
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.removeTag(ids, tagId) }
                .onSuccess { operationMessage.value = "已移除标签" }
                .onFailure { operationMessage.value = it.message ?: "移除标签失败" }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            runCatching { repository.createFolder(name) }
                .onSuccess { operationMessage.value = "文件夹已创建" }
                .onFailure { operationMessage.value = it.message ?: "创建文件夹失败" }
        }
    }

    fun createTag(name: String) {
        viewModelScope.launch {
            runCatching { repository.createTag(name) }
                .onSuccess { operationMessage.value = "标签已创建" }
                .onFailure { operationMessage.value = it.message ?: "创建标签失败" }
        }
    }

    fun renameFolder(folderId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.renameFolder(folderId, name) }
                .onSuccess { renamed ->
                    operationMessage.value =
                        if (renamed) "文件夹已重命名" else "文件夹名称已存在"
                }
                .onFailure { operationMessage.value = it.message ?: "重命名失败" }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteFolder(folderId) }
                .onSuccess { deleted ->
                    if (deleted && criteria.value.folderId == folderId) {
                        criteria.value =
                            criteria.value.copy(
                                folderId = null,
                                uncategorizedOnly = false,
                            )
                    }
                    operationMessage.value =
                        if (deleted) "文件夹已删除，录音已移至未分类" else "文件夹不存在"
                }
                .onFailure { operationMessage.value = it.message ?: "删除文件夹失败" }
        }
    }

    fun renameTag(tagId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.renameTag(tagId, name) }
                .onSuccess { renamed ->
                    operationMessage.value =
                        if (renamed) "标签已重命名" else "标签名称已存在"
                }
                .onFailure { operationMessage.value = it.message ?: "重命名失败" }
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteTag(tagId) }
                .onSuccess { deleted ->
                    if (deleted && tagId in criteria.value.tagIds) {
                        criteria.value =
                            criteria.value.copy(
                                tagIds = criteria.value.tagIds - tagId,
                            )
                    }
                    operationMessage.value =
                        if (deleted) "标签已删除" else "标签不存在"
                }
                .onFailure { operationMessage.value = it.message ?: "删除标签失败" }
        }
    }

    fun consumeOperationMessage() {
        operationMessage.value = null
    }

    private data class LibraryData(
        val recordings: List<RecordingLibraryRow>,
        val folders: List<FolderEntity>,
        val tags: List<TagEntity>,
    )

    private data class InteractionState(
        val query: String,
        val criteria: LibraryQueryCriteria,
        val selectedIds: Set<String>,
        val operationMessage: String?,
    )

    class Factory(
        private val repository: RecordingLibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingLibraryViewModel(repository) as T
    }
}
