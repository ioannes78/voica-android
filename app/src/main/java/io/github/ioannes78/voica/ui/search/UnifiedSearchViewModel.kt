package io.github.ioannes78.voica.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.ioannes78.voica.database.SearchDocumentEntity
import io.github.ioannes78.voica.database.SearchDocumentTypeValue
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.SearchIndexStateEntity
import io.github.ioannes78.voica.database.SearchIndexStatusValue
import io.github.ioannes78.voica.database.UnifiedSearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class UnifiedSearchFilter(
    val label: String,
    val documentTypes: Set<String>?,
) {
    ALL("全部", null),
    RECORDING("录音", setOf(SearchDocumentTypeValue.RECORDING)),
    TRANSCRIPT("转写", setOf(SearchDocumentTypeValue.TRANSCRIPT_UNIT)),
    SUMMARY(
        "AI 总结",
        setOf(
            SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
            SearchDocumentTypeValue.SUMMARY_ITEM,
        ),
    ),
    FOLDER("文件夹", setOf(SearchDocumentTypeValue.FOLDER)),
    TAG("标签", setOf(SearchDocumentTypeValue.TAG)),
}

data class UnifiedSearchUiState(
    val query: String = "",
    val filter: UnifiedSearchFilter = UnifiedSearchFilter.ALL,
    val indexState: SearchIndexStateEntity? = null,
    val results: List<SearchDocumentEntity> = emptyList(),
    val searching: Boolean = false,
    val message: String? = null,
)

class UnifiedSearchViewModel(
    private val repository: UnifiedSearchRepository,
    private val rebuilder: SearchIndexRebuilder,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UnifiedSearchUiState())
    val state: StateFlow<UnifiedSearchUiState> = mutableState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeState().collectLatest { indexState ->
                mutableState.value = mutableState.value.copy(indexState = indexState)
                if (
                    indexState?.status == SearchIndexStatusValue.READY &&
                    mutableState.value.query.isNotBlank()
                ) {
                    scheduleSearch(immediate = true)
                }
            }
        }
    }

    fun setQuery(value: String) {
        mutableState.value =
            mutableState.value.copy(
                query = value.take(MAX_QUERY_LENGTH),
                message = null,
            )
        scheduleSearch(immediate = false)
    }

    fun setFilter(filter: UnifiedSearchFilter) {
        if (mutableState.value.filter == filter) return
        mutableState.value = mutableState.value.copy(filter = filter)
        scheduleSearch(immediate = true)
    }

    fun rebuildIndex() {
        searchJob?.cancel()
        viewModelScope.launch {
            mutableState.value =
                mutableState.value.copy(
                    searching = true,
                    message = "正在重建搜索索引…",
                )
            runCatching { rebuilder.rebuildAll() }
                .onSuccess {
                    mutableState.value =
                        mutableState.value.copy(
                            searching = false,
                            message = null,
                        )
                    scheduleSearch(immediate = true)
                }
                .onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
                            searching = false,
                            message = error.message ?: "搜索索引重建失败",
                        )
                }
        }
    }

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        val snapshot = mutableState.value
        if (snapshot.query.isBlank()) {
            mutableState.value =
                snapshot.copy(
                    results = emptyList(),
                    searching = false,
                    message = null,
                )
            return
        }
        if (snapshot.indexState?.status != SearchIndexStatusValue.READY) {
            mutableState.value =
                snapshot.copy(
                    results = emptyList(),
                    searching = false,
                    message =
                        when (snapshot.indexState?.status) {
                            SearchIndexStatusValue.REBUILDING ->
                                "正在建立搜索索引…"
                            SearchIndexStatusValue.FAILED ->
                                "搜索索引不可用，可尝试重建。"
                            else ->
                                "搜索索引尚未准备好。"
                        },
                )
            return
        }

        searchJob =
            viewModelScope.launch {
                if (!immediate) delay(SEARCH_DEBOUNCE_MS)
                val query = mutableState.value.query
                val filter = mutableState.value.filter
                if (query.isBlank()) return@launch
                mutableState.value =
                    mutableState.value.copy(
                        searching = true,
                        message = null,
                    )
                runCatching {
                    repository.search(
                        query = query,
                        documentTypes = filter.documentTypes,
                        limit = 80,
                    )
                }.onSuccess { results ->
                    if (
                        mutableState.value.query == query &&
                        mutableState.value.filter == filter
                    ) {
                        mutableState.value =
                            mutableState.value.copy(
                                results = results,
                                searching = false,
                                message =
                                    if (results.isEmpty()) {
                                        "没有找到相关内容"
                                    } else {
                                        null
                                    },
                            )
                    }
                }.onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
                            results = emptyList(),
                            searching = false,
                            message = error.message ?: "搜索失败",
                        )
                }
            }
    }

    class Factory(
        private val repository: UnifiedSearchRepository,
        private val rebuilder: SearchIndexRebuilder,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UnifiedSearchViewModel(repository, rebuilder) as T
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
        const val MAX_QUERY_LENGTH = 200
    }
}
