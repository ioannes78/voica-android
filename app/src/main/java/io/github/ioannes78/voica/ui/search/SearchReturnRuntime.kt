package io.github.ioannes78.voica.ui.search

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot in-process route marker for the current single-activity navigation shell.
 *
 * Unified search and recording detail are currently sibling states owned by VoicaApp rather than
 * Navigation Compose destinations. A search-origin detail open therefore records this marker so
 * the library route can immediately restore the existing search ViewModel/UI state after detail
 * back navigation. Folder/tag results intentionally do not set it because those results navigate
 * to the filtered recording library.
 */
object SearchReturnRuntime {
    private val pending = AtomicBoolean(false)

    fun markDetailOpen() {
        pending.set(true)
    }

    fun consumePendingReturn(): Boolean = pending.compareAndSet(true, false)

    fun clear() {
        pending.set(false)
    }
}
