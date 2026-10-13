package io.github.ioannes78.voica.ui.realtime

internal enum class RealtimeReaderFollowMode {
    FOLLOW_LIVE,
    READING_HISTORY,
}

/**
 * Pure policy for the realtime transcript reader viewport.
 *
 * Programmatic list movement must not disable live following. Only an actual user drag away from
 * the live edge enters history-reading mode. Returning to the live edge restores follow mode.
 */
internal object RealtimeReaderFollowPolicy {
    fun reduce(
        current: RealtimeReaderFollowMode,
        userDragging: Boolean,
        atBottom: Boolean,
    ): RealtimeReaderFollowMode =
        when {
            atBottom -> RealtimeReaderFollowMode.FOLLOW_LIVE
            userDragging -> RealtimeReaderFollowMode.READING_HISTORY
            else -> current
        }

    fun shouldShowReturnToLive(
        mode: RealtimeReaderFollowMode,
        hasTranscriptContent: Boolean,
    ): Boolean =
        mode == RealtimeReaderFollowMode.READING_HISTORY && hasTranscriptContent
}
