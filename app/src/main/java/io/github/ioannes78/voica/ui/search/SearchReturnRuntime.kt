package io.github.ioannes78.voica.ui.search

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge for the current single-activity navigation shell.
 *
 * Unified search and recording detail are currently sibling states owned by VoicaApp rather than
 * Navigation Compose destinations. A search-origin detail open therefore records a one-shot return
 * marker so the library route can immediately restore the existing search ViewModel/UI state after
 * detail back navigation. Folder/tag results intentionally do not set it because those results
 * navigate to the filtered recording library.
 *
 * Stage 14.1A R1 also uses a one-shot launch query to bridge the legacy recording-library search
 * field into unified full-content search. The query is observed by UnifiedSearchViewModel so this
 * works even when that ViewModel already exists before the library field starts the search route.
 */
object SearchReturnRuntime {
    private val pendingReturn = AtomicBoolean(false)
    private val mutableLaunchQuery = MutableStateFlow<String?>(null)

    val launchQuery: StateFlow<String?> = mutableLaunchQuery.asStateFlow()

    fun markDetailOpen() {
        pendingReturn.set(true)
    }

    fun consumePendingReturn(): Boolean = pendingReturn.compareAndSet(true, false)

    fun requestSearchLaunch(query: String) {
        val normalized = query.trim()
        if (normalized.isNotEmpty()) {
            mutableLaunchQuery.value = normalized
        }
    }

    fun consumeSearchLaunch(query: String) {
        if (mutableLaunchQuery.value == query) {
            mutableLaunchQuery.value = null
        }
    }

    fun clear() {
        pendingReturn.set(false)
        mutableLaunchQuery.value = null
    }
}
