package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build

internal data class ModelInstallExitSnapshot(
    val reason: Int,
    val timestampMs: Long,
    val processName: String?,
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

/**
 * Converts a user-requested process stop into an explicit-resume boundary before the
 * durable executors are allowed to reconcile. This runs before ModelInstallOrchestrator
 * is constructed so an old WorkManager/JobScheduler generation can never silently
 * continue network I/O after the user reopens Voica.
 */
internal class ModelInstallStartupRecoveryPolicy(
    private val application: Application,
    private val journalStore: ModelInstallJournalStore,
) {
    fun interruptManualInstallsAfterUserRequestedExit(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()

        val preferences =
            application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
        val currentVersionCode = BuildConfig.VERSION_CODE
        val previousVersionCode = preferences.getInt(KEY_VERSION_CODE, VERSION_UNKNOWN)
        val lastHandledTimestamp = preferences.getLong(KEY_LAST_HANDLED_EXIT_TIMESTAMP, 0L)
        val latestMainExit = latestMainProcessExit() ?: run {
            preferences.edit().putInt(KEY_VERSION_CODE, currentVersionCode).commit()
            return emptyList()
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
            return emptyList()
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
            return emptyList()
        }

        val interrupted =
            journalStore.readAll()
                .filter { record ->
                    record.origin == ModelInstallOrigin.MANUAL && !record.phase.terminal
                }
                .map { record ->
                    journalStore.write(
                        record.copy(
                            phase = ModelInstallPhase.INTERRUPTED,
                            cancelRequested = false,
                            requiresUserResume = true,
                            executorKind = ModelInstallExecutorKind.NONE,
                            executorGeneration = record.executorGeneration + 1L,
                            lastFailureCode = FAILURE_USER_STOPPED,
                            lastFailureMessage = "安装已中断，请点击继续安装",
                        ),
                    ).operationId
                }

        preferences.edit()
            .putInt(KEY_VERSION_CODE, currentVersionCode)
            .putLong(KEY_LAST_HANDLED_EXIT_TIMESTAMP, latestMainExit.timestampMs)
            .commit()
        return interrupted
    }

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
    }
}
