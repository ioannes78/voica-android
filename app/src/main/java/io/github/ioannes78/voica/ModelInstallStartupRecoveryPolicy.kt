package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

internal data class ModelInstallExitSnapshot(
    val reason: Int,
    val timestampMs: Long,
    val processName: String?,
)

internal data class ModelInstallTaskRemovalMark(
    val operationId: String,
    val executorGeneration: Long,
)

internal fun shouldInterruptModelInstallForExit(
    exit: ModelInstallExitSnapshot,
    lastHandledTimestampMs: Long,
    mainProcessName: String,
    appVersionChanged: Boolean,
): Boolean =
    !appVersionChanged &&
        exit.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
        exit.timestampMs > lastHandledTimestampMs &&
        exit.processName == mainProcessName

internal fun shouldApplyTaskRemovalMark(
    mark: ModelInstallTaskRemovalMark,
    record: ModelInstallJournalRecord,
): Boolean =
    record.operationId == mark.operationId &&
        record.origin == ModelInstallOrigin.MANUAL &&
        !record.phase.terminal &&
        !record.requiresUserResume &&
        record.executorGeneration == mark.executorGeneration

internal class ModelInstallTaskRemovalMarkerStore(
    context: Context,
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun mark(records: List<ModelInstallJournalRecord>) {
        if (records.isEmpty()) return
        val entries =
            preferences.getStringSet(KEY_MARKS, emptySet())
                .orEmpty()
                .toMutableSet()
        records.forEach { record ->
            entries += encode(
                ModelInstallTaskRemovalMark(
                    operationId = record.operationId,
                    executorGeneration = record.executorGeneration,
                ),
            )
        }
        preferences.edit().putStringSet(KEY_MARKS, entries).commit()
    }

    fun consume(): List<ModelInstallTaskRemovalMark> {
        val entries = preferences.getStringSet(KEY_MARKS, emptySet()).orEmpty().toSet()
        if (entries.isEmpty()) return emptyList()
        preferences.edit().remove(KEY_MARKS).commit()
        return entries.mapNotNull(::decode)
    }

    private fun encode(mark: ModelInstallTaskRemovalMark): String =
        mark.operationId + SEPARATOR + mark.executorGeneration

    private fun decode(value: String): ModelInstallTaskRemovalMark? {
        val separatorIndex = value.lastIndexOf(SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex == value.lastIndex) return null
        val operationId = value.substring(0, separatorIndex)
        val generation = value.substring(separatorIndex + 1).toLongOrNull() ?: return null
        if (generation < 1L) return null
        return ModelInstallTaskRemovalMark(operationId, generation)
    }

    private companion object {
        const val PREFERENCES_NAME = "model-install-task-removal"
        const val KEY_MARKS = "pending-marks"
        const val SEPARATOR = "|"
    }
}

/**
 * Converts explicit user stops into an explicit-resume boundary before durable
 * executors are allowed to reconcile. Task-removal markers cover OEMs that do
 * not report a recent-task swipe as REASON_USER_REQUESTED; ApplicationExitInfo
 * remains the fallback for system Force stop / user-requested process stops.
 */
internal class ModelInstallStartupRecoveryPolicy(
    private val application: Application,
    private val journalStore: ModelInstallJournalStore,
) {
    fun interruptManualInstallsAfterUserRequestedExit(): List<String> {
        val interrupted = linkedSetOf<String>()
        interruptTaskRemovedInstalls(interrupted)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return interrupted.toList()

        val preferences =
            application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
        val currentVersionCode = BuildConfig.VERSION_CODE
        val previousVersionCode = preferences.getInt(KEY_VERSION_CODE, VERSION_UNKNOWN)
        val lastHandledTimestamp = preferences.getLong(KEY_LAST_HANDLED_EXIT_TIMESTAMP, 0L)
        val latestMainExit = latestMainProcessExit() ?: run {
            preferences.edit().putInt(KEY_VERSION_CODE, currentVersionCode).commit()
            return interrupted.toList()
        }

        // The first run after introducing this policy, and the first run after an app
        // update, establish a fresh baseline. Older Android versions may report package
        // replacement as USER_REQUESTED, so never reinterpret an old-version exit.
        if (previousVersionCode == VERSION_UNKNOWN || previousVersionCode != currentVersionCode) {
            preferences.edit()
                .putInt(KEY_VERSION_CODE, currentVersionCode)
                .putLong(
                    KEY_LAST_HANDLED_EXIT_TIMESTAMP,
                    maxOf(lastHandledTimestamp, latestMainExit.timestampMs),
                )
                .commit()
            return interrupted.toList()
        }

        val shouldInterrupt =
            shouldInterruptModelInstallForExit(
                exit = latestMainExit,
                lastHandledTimestampMs = lastHandledTimestamp,
                mainProcessName = application.packageName,
                appVersionChanged = false,
            )
        if (!shouldInterrupt) {
            if (latestMainExit.timestampMs > lastHandledTimestamp) {
                preferences.edit()
                    .putLong(KEY_LAST_HANDLED_EXIT_TIMESTAMP, latestMainExit.timestampMs)
                    .commit()
            }
            return interrupted.toList()
        }

        journalStore.readAll()
            .filter { record ->
                record.origin == ModelInstallOrigin.MANUAL &&
                    !record.phase.terminal &&
                    !record.requiresUserResume
            }
            .forEach { record ->
                val written = interruptRecord(record, FAILURE_USER_STOPPED)
                interrupted += written.operationId
            }

        preferences.edit()
            .putInt(KEY_VERSION_CODE, currentVersionCode)
            .putLong(KEY_LAST_HANDLED_EXIT_TIMESTAMP, latestMainExit.timestampMs)
            .commit()
        return interrupted.toList()
    }

    private fun interruptTaskRemovedInstalls(interrupted: MutableSet<String>) {
        val markerStore = ModelInstallTaskRemovalMarkerStore(application)
        markerStore.consume().forEach { mark ->
            val record = journalStore.read(mark.operationId) ?: return@forEach
            if (!shouldApplyTaskRemovalMark(mark, record)) return@forEach
            val written = interruptRecord(record, FAILURE_TASK_REMOVED)
            interrupted += written.operationId
        }
    }

    private fun interruptRecord(
        record: ModelInstallJournalRecord,
        failureCode: String,
    ): ModelInstallJournalRecord =
        journalStore.write(
            record.copy(
                phase = ModelInstallPhase.INTERRUPTED,
                cancelRequested = false,
                requiresUserResume = true,
                executorKind = ModelInstallExecutorKind.NONE,
                executorGeneration = record.executorGeneration + 1L,
                lastFailureCode = failureCode,
                lastFailureMessage = "安装已中断，请点击继续安装",
            ),
        )

    private fun latestMainProcessExit(): ModelInstallExitSnapshot? {
        val activityManager = application.getSystemService(ActivityManager::class.java)
        return activityManager
            .getHistoricalProcessExitReasons(application.packageName, 0, EXIT_HISTORY_LIMIT)
            .asSequence()
            .map { exit ->
                ModelInstallExitSnapshot(
                    reason = exit.reason,
                    timestampMs = exit.timestamp,
                    processName = exit.processName,
                )
            }
            .filter { it.processName == application.packageName }
            .maxByOrNull { it.timestampMs }
    }

    private companion object {
        const val PREFERENCES_NAME = "model-install-startup-recovery"
        const val KEY_VERSION_CODE = "version-code"
        const val KEY_LAST_HANDLED_EXIT_TIMESTAMP = "last-handled-exit-timestamp"
        const val VERSION_UNKNOWN = -1
        const val EXIT_HISTORY_LIMIT = 12
        const val FAILURE_USER_STOPPED = "USER_STOPPED"
        const val FAILURE_TASK_REMOVED = "TASK_REMOVED"
    }
}
