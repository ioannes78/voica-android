package io.github.ioannes78.voica

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskCompletionKind {
    TRANSCRIPTION,
    DIARIZATION,
    AI_SUMMARY,
}

data class TaskCompletionNotice(
    val kind: TaskCompletionKind,
    val taskId: String,
    val recordingId: String,
    val publishedAtMs: Long,
)

/**
 * Persistent acknowledgement state for ordinary successful task completion notifications.
 *
 * Business truth stays in Room/coordinators. This store only remembers which completed task result
 * still needs to be surfaced to the user and which unique result ids have already been viewed.
 */
class TaskCompletionNoticeStore(
    context: Context,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutablePending = MutableStateFlow(loadPending())
    val pending: StateFlow<List<TaskCompletionNotice>> = mutablePending.asStateFlow()

    @Synchronized
    fun publish(
        kind: TaskCompletionKind,
        taskId: String,
        recordingId: String,
    ) {
        require(taskId.isNotBlank())
        require(recordingId.isNotBlank())
        if (preferences.contains(acknowledgedKey(kind, taskId))) return
        val key = pendingKey(kind, taskId)
        if (!preferences.contains(key)) {
            preferences.edit()
                .putString(key, encodePending(recordingId, nowMs()))
                .apply()
        }
        refreshPending()
    }

    @Synchronized
    fun acknowledge(
        kind: TaskCompletionKind,
        taskId: String,
    ) {
        if (taskId.isBlank()) return
        preferences.edit()
            .remove(pendingKey(kind, taskId))
            .putLong(acknowledgedKey(kind, taskId), nowMs())
            .apply()
        pruneAcknowledged()
        refreshPending()
    }

    @Synchronized
    fun acknowledgeVisible(
        recordingId: String,
        includeTranscriptResults: Boolean,
        includeSummaryResults: Boolean,
    ) {
        if (recordingId.isBlank()) return
        val matches =
            mutablePending.value.filter { notice ->
                notice.recordingId == recordingId &&
                    when (notice.kind) {
                        TaskCompletionKind.TRANSCRIPTION,
                        TaskCompletionKind.DIARIZATION,
                        -> includeTranscriptResults
                        TaskCompletionKind.AI_SUMMARY -> includeSummaryResults
                    }
            }
        if (matches.isEmpty()) return
        val editor = preferences.edit()
        val acknowledgedAt = nowMs()
        matches.forEach { notice ->
            editor
                .remove(pendingKey(notice.kind, notice.taskId))
                .putLong(acknowledgedKey(notice.kind, notice.taskId), acknowledgedAt)
        }
        editor.apply()
        pruneAcknowledged()
        refreshPending()
    }

    private fun loadPending(): List<TaskCompletionNotice> =
        preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(PENDING_PREFIX)) return@mapNotNull null
            val suffix = key.removePrefix(PENDING_PREFIX)
            val separator = suffix.indexOf('.')
            if (separator <= 0 || separator >= suffix.lastIndex) return@mapNotNull null
            val kind =
                runCatching { TaskCompletionKind.valueOf(suffix.substring(0, separator)) }
                    .getOrNull()
                    ?: return@mapNotNull null
            val taskId = suffix.substring(separator + 1).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            val split = encoded.lastIndexOf(VALUE_SEPARATOR)
            if (split <= 0 || split >= encoded.lastIndex) return@mapNotNull null
            val recordingId = encoded.substring(0, split).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val publishedAtMs = encoded.substring(split + 1).toLongOrNull()
                ?: return@mapNotNull null
            TaskCompletionNotice(
                kind = kind,
                taskId = taskId,
                recordingId = recordingId,
                publishedAtMs = publishedAtMs,
            )
        }.sortedByDescending { it.publishedAtMs }

    private fun refreshPending() {
        mutablePending.value = loadPending()
    }

    private fun pruneAcknowledged() {
        val acknowledged =
            preferences.all
                .mapNotNull { (key, value) ->
                    if (!key.startsWith(ACKNOWLEDGED_PREFIX)) return@mapNotNull null
                    val timestamp = (value as? Long) ?: return@mapNotNull null
                    key to timestamp
                }
                .sortedByDescending { it.second }
        if (acknowledged.size <= MAX_ACKNOWLEDGED_IDS) return
        val editor = preferences.edit()
        acknowledged.drop(MAX_ACKNOWLEDGED_IDS).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
    }

    private fun pendingKey(kind: TaskCompletionKind, taskId: String): String =
        "$PENDING_PREFIX${kind.name}.$taskId"

    private fun acknowledgedKey(kind: TaskCompletionKind, taskId: String): String =
        "$ACKNOWLEDGED_PREFIX${kind.name}.$taskId"

    private fun encodePending(recordingId: String, publishedAtMs: Long): String =
        "$recordingId$VALUE_SEPARATOR$publishedAtMs"

    private companion object {
        const val PREFERENCES_NAME = "voica-task-completion-notices"
        const val PENDING_PREFIX = "pending."
        const val ACKNOWLEDGED_PREFIX = "acknowledged."
        const val VALUE_SEPARATOR = '|'
        const val MAX_ACKNOWLEDGED_IDS = 256
    }
}
