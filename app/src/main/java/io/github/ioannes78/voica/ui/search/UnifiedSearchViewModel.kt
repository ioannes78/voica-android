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
import java.util.Locale
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

data class UnifiedSearchRecordingGroup(
    val recordingId: String,
    val recordingTitle: String,
    val hits: List<SearchDocumentEntity>,
    val score: Int,
    val updatedAtMs: Long,
) {
    val fileNameMatched: Boolean
        get() = hits.any { it.documentType == SearchDocumentTypeValue.RECORDING }

    val transcriptHits: List<SearchDocumentEntity>
        get() = hits.filter { it.documentType == SearchDocumentTypeValue.TRANSCRIPT_UNIT }

    val summaryHits: List<SearchDocumentEntity>
        get() =
            hits.filter {
                it.documentType == SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW ||
                    it.documentType == SearchDocumentTypeValue.SUMMARY_ITEM
            }

    val contentHitCount: Int
        get() = transcriptHits.size + summaryHits.size
}

data class UnifiedSearchUiState(
    val query: String = "",
    val filter: UnifiedSearchFilter = UnifiedSearchFilter.ALL,
    val indexState: SearchIndexStateEntity? = null,
    val results: List<SearchDocumentEntity> = emptyList(),
    val recordingGroups: List<UnifiedSearchRecordingGroup> = emptyList(),
    val unlinkedResults: List<SearchDocumentEntity> = emptyList(),
    val expandedRecordingIds: Set<String> = emptySet(),
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
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

        viewModelScope.launch {
            SearchReturnRuntime.launchQuery.collectLatest { launchQuery ->
                val query = launchQuery ?: return@collectLatest
                SearchReturnRuntime.consumeSearchLaunch(query)
                setQuery(query)
            }
        }
    }

    fun setQuery(value: String) {
        val next = value.take(MAX_QUERY_LENGTH)
        val changed = mutableState.value.query != next
        mutableState.value =
            mutableState.value.copy(
                query = next,
                expandedRecordingIds = if (changed) emptySet() else mutableState.value.expandedRecordingIds,
                firstVisibleItemIndex = if (changed) 0 else mutableState.value.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = if (changed) 0 else mutableState.value.firstVisibleItemScrollOffset,
                message = null,
            )
        scheduleSearch(immediate = false)
    }

    fun setFilter(filter: UnifiedSearchFilter) {
        if (mutableState.value.filter == filter) return
        mutableState.value =
            mutableState.value.copy(
                filter = filter,
                expandedRecordingIds = emptySet(),
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0,
            )
        scheduleSearch(immediate = true)
    }

    fun setRecordingExpanded(recordingId: String, expanded: Boolean) {
        val current = mutableState.value.expandedRecordingIds
        mutableState.value =
            mutableState.value.copy(
                expandedRecordingIds =
                    if (expanded) current + recordingId else current - recordingId,
            )
    }

    fun rememberListPosition(index: Int, scrollOffset: Int) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = scrollOffset.coerceAtLeast(0)
        val current = mutableState.value
        if (
            current.firstVisibleItemIndex == safeIndex &&
            current.firstVisibleItemScrollOffset == safeOffset
        ) {
            return
        }
        mutableState.value =
            current.copy(
                firstVisibleItemIndex = safeIndex,
                firstVisibleItemScrollOffset = safeOffset,
            )
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
                    recordingGroups = emptyList(),
                    unlinkedResults = emptyList(),
                    searching = false,
                    message = null,
                )
            return
        }
        if (snapshot.indexState?.status != SearchIndexStatusValue.READY) {
            mutableState.value =
                snapshot.copy(
                    results = emptyList(),
                    recordingGroups = emptyList(),
                    unlinkedResults = emptyList(),
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
                    if (filter == UnifiedSearchFilter.ALL) {
                        val results = searchAllSources(query)
                        val recordingIds = results.mapNotNull { it.recordingId }.distinct()
                        val recordingTitles =
                            repository.findRecordingDocuments(recordingIds)
                                .mapNotNull { document ->
                                    document.recordingId?.let { it to document.displayTitle }
                                }
                                .toMap()
                        SearchResultProjection(
                            results = results,
                            groups = buildUnifiedSearchRecordingGroups(results, recordingTitles, query),
                            unlinked = results.filter { it.recordingId == null },
                        )
                    } else {
                        val results =
                            repository.search(
                                query = query,
                                documentTypes = filter.documentTypes,
                                limit = FILTERED_RESULT_LIMIT,
                            )
                        SearchResultProjection(
                            results = results,
                            groups = emptyList(),
                            unlinked = emptyList(),
                        )
                    }
                }.onSuccess { projection ->
                    if (
                        mutableState.value.query == query &&
                        mutableState.value.filter == filter
                    ) {
                        val validGroupIds = projection.groups.mapTo(mutableSetOf()) { it.recordingId }
                        mutableState.value =
                            mutableState.value.copy(
                                results = projection.results,
                                recordingGroups = projection.groups,
                                unlinkedResults = projection.unlinked,
                                expandedRecordingIds =
                                    mutableState.value.expandedRecordingIds.intersect(validGroupIds),
                                searching = false,
                                message =
                                    if (projection.results.isEmpty()) {
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
                            recordingGroups = emptyList(),
                            unlinkedResults = emptyList(),
                            searching = false,
                            message = error.message ?: "搜索失败",
                        )
                }
            }
    }

    private suspend fun searchAllSources(query: String): List<SearchDocumentEntity> =
        buildList {
            addAll(
                repository.search(
                    query = query,
                    documentTypes = setOf(SearchDocumentTypeValue.RECORDING),
                    limit = RECORDING_RESULT_LIMIT,
                ),
            )
            addAll(
                repository.search(
                    query = query,
                    documentTypes = setOf(SearchDocumentTypeValue.TRANSCRIPT_UNIT),
                    limit = TRANSCRIPT_RESULT_LIMIT,
                ),
            )
            addAll(
                repository.search(
                    query = query,
                    documentTypes =
                        setOf(
                            SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                            SearchDocumentTypeValue.SUMMARY_ITEM,
                        ),
                    limit = SUMMARY_RESULT_LIMIT,
                ),
            )
            addAll(
                repository.search(
                    query = query,
                    documentTypes = setOf(SearchDocumentTypeValue.FOLDER),
                    limit = AUXILIARY_RESULT_LIMIT,
                ),
            )
            addAll(
                repository.search(
                    query = query,
                    documentTypes = setOf(SearchDocumentTypeValue.TAG),
                    limit = AUXILIARY_RESULT_LIMIT,
                ),
            )
        }.distinctBy { it.documentId }

    class Factory(
        private val repository: UnifiedSearchRepository,
        private val rebuilder: SearchIndexRebuilder,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UnifiedSearchViewModel(repository, rebuilder) as T
    }

    private data class SearchResultProjection(
        val results: List<SearchDocumentEntity>,
        val groups: List<UnifiedSearchRecordingGroup>,
        val unlinked: List<SearchDocumentEntity>,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 280L
        const val MAX_QUERY_LENGTH = 200
        const val FILTERED_RESULT_LIMIT = 100
        const val RECORDING_RESULT_LIMIT = 40
        const val TRANSCRIPT_RESULT_LIMIT = 100
        const val SUMMARY_RESULT_LIMIT = 80
        const val AUXILIARY_RESULT_LIMIT = 20
    }
}

internal fun buildUnifiedSearchRecordingGroups(
    results: List<SearchDocumentEntity>,
    recordingTitles: Map<String, String>,
    query: String,
): List<UnifiedSearchRecordingGroup> {
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return results
        .mapNotNull { result -> result.recordingId?.let { it to result } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (recordingId, hits) ->
            val recordingHit = hits.firstOrNull { it.documentType == SearchDocumentTypeValue.RECORDING }
            val title =
                recordingTitles[recordingId]
                    ?.takeIf { it.isNotBlank() }
                    ?: recordingHit?.displayTitle?.takeIf { it.isNotBlank() }
                    ?: "录音"
            val normalizedTitle = title.lowercase(Locale.ROOT)
            val sourceKinds =
                hits.mapNotNull { hit ->
                    when (hit.documentType) {
                        SearchDocumentTypeValue.RECORDING -> "recording"
                        SearchDocumentTypeValue.TRANSCRIPT_UNIT -> "transcript"
                        SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                        SearchDocumentTypeValue.SUMMARY_ITEM,
                        -> "summary"
                        else -> null
                    }
                }.toSet()
            val score =
                buildSearchRelevanceScore(
                    hits = hits,
                    normalizedTitle = normalizedTitle,
                    normalizedQuery = normalizedQuery,
                    sourceKindCount = sourceKinds.size,
                )
            UnifiedSearchRecordingGroup(
                recordingId = recordingId,
                recordingTitle = title,
                hits = hits.sortedByDescending { it.updatedAtMs },
                score = score,
                updatedAtMs = hits.maxOfOrNull { it.updatedAtMs } ?: 0L,
            )
        }
        .sortedWith(
            compareByDescending<UnifiedSearchRecordingGroup> { it.score }
                .thenByDescending { it.updatedAtMs },
        )
}

private fun buildSearchRelevanceScore(
    hits: List<SearchDocumentEntity>,
    normalizedTitle: String,
    normalizedQuery: String,
    sourceKindCount: Int,
): Int {
    val fileNameMatched = hits.any { it.documentType == SearchDocumentTypeValue.RECORDING }
    val titleScore =
        when {
            !fileNameMatched -> 0
            normalizedQuery.isNotBlank() && normalizedTitle == normalizedQuery -> 1_200
            normalizedQuery.isNotBlank() && normalizedTitle.contains(normalizedQuery) -> 900
            else -> 650
        }
    val summaryOverviewCount =
        hits.count { it.documentType == SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW }
    val summaryItemCount = hits.count { it.documentType == SearchDocumentTypeValue.SUMMARY_ITEM }
    val transcriptCount = hits.count { it.documentType == SearchDocumentTypeValue.TRANSCRIPT_UNIT }
    val diversityBonus = (sourceKindCount - 1).coerceAtLeast(0) * 90
    val boundedHitBonus = hits.size.coerceAtMost(8) * 15
    return titleScore +
        summaryOverviewCount * 190 +
        summaryItemCount * 150 +
        transcriptCount * 100 +
        diversityBonus +
        boundedHitBonus
}
