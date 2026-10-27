package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import io.github.ioannes78.voica.localllm.LocalLlmRuntimeDiagnosticPhase
import io.github.ioannes78.voica.localllm.LocalLlmRuntimePhase
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * R5-A persistent, content-free crash breadcrumb.
 *
 * No prompt, transcript, generated text, recording title or other user content is represented in
 * this type. It is QA diagnostics only and stores scalar runtime/lifecycle metadata needed to
 * identify the last phase that completed a durable write before native process death.
 *
 * R5-C also enriches a breadcrumb recovered from a prior process with Android's scalar
 * ApplicationExitInfo metadata when available. Tombstone/trace content is deliberately not read.
 */
internal data class Stage17R5CrashBreadcrumb(
    val pid: Int,
    val processSessionId: String,
    val attemptSessionId: String,
    val requestedBackend: String,
    val resolvedBackend: String,
    val schemaEnabled: Boolean,
    val schemaSizeChars: Int,
    val estimatedPromptTokens: Int,
    val runtimePhase: String,
    val emissionCount: Long,
    val pssKb: Long,
    val interactive: Boolean,
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
    val timestampEpochMs: Long,
    val exitReason: Int? = null,
    val exitReasonLabel: String? = null,
    val exitStatus: Int? = null,
    val exitImportance: Int? = null,
    val exitPssKb: Long? = null,
    val exitRssKb: Long? = null,
    val exitTimestampEpochMs: Long? = null,
)

internal class Stage17R5CrashBreadcrumbStore(
    context: Context,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private data class ActiveAttempt(
        val attemptSessionId: String,
        val estimatedPromptTokens: Int,
    )

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private var activeAttempt: ActiveAttempt? = null
    private var lastProgressPersistedAtMs: Long = 0L
    private val mutableLast = MutableStateFlow(readPersisted())

    val last: StateFlow<Stage17R5CrashBreadcrumb?> = mutableLast.asStateFlow()
    val currentPid: Int = Process.myPid()
    val currentProcessSessionId: String = UUID.randomUUID().toString()

    fun arm(estimatedPromptTokens: Int) {
        require(estimatedPromptTokens >= 0) { "estimatedPromptTokens must be non-negative" }
        activeAttempt =
            ActiveAttempt(
                attemptSessionId = UUID.randomUUID().toString(),
                estimatedPromptTokens = estimatedPromptTokens,
            )
        lastProgressPersistedAtMs = 0L
    }

    fun disarm() {
        activeAttempt = null
    }

    fun record(event: LocalLlmRuntimeDiagnosticPhase) {
        val attempt = activeAttempt ?: return
        val now = clockMillis()
        if (
            event.phase == LocalLlmRuntimePhase.GENERATION_PROGRESS &&
            !shouldPersistStage17R5GenerationProgress(
                emissionCount = event.emissionCount,
                nowMs = now,
                lastPersistedAtMs = lastProgressPersistedAtMs,
            )
        ) {
            return
        }

        val breadcrumb =
            Stage17R5CrashBreadcrumb(
                pid = currentPid,
                processSessionId = currentProcessSessionId,
                attemptSessionId = attempt.attemptSessionId,
                requestedBackend = event.requestedPolicy.name,
                resolvedBackend = event.resolvedBackend.name,
                schemaEnabled = event.schemaEnabled,
                schemaSizeChars = event.schemaSizeChars,
                estimatedPromptTokens = attempt.estimatedPromptTokens,
                runtimePhase = event.phase.name,
                emissionCount = event.emissionCount,
                pssKb = Debug.getPss().toLong().coerceAtLeast(0L),
                interactive = powerManager.isInteractive,
                keyguardShowing = keyguardManager.isKeyguardLocked,
                deviceLocked = keyguardManager.isDeviceLocked,
                timestampEpochMs = now,
            )
        if (persist(breadcrumb)) {
            if (event.phase == LocalLlmRuntimePhase.GENERATION_PROGRESS) {
                lastProgressPersistedAtMs = now
            }
            mutableLast.value = breadcrumb
        }
    }

    fun clear() {
        if (preferences.edit().clear().commit()) {
            mutableLast.value = null
        }
    }

    fun isPreviousProcess(breadcrumb: Stage17R5CrashBreadcrumb): Boolean =
        breadcrumb.pid != currentPid || breadcrumb.processSessionId != currentProcessSessionId

    private fun persist(value: Stage17R5CrashBreadcrumb): Boolean =
        preferences.edit()
            .putInt(KEY_VERSION, FORMAT_VERSION)
            .putInt(KEY_PID, value.pid)
            .putString(KEY_PROCESS_SESSION_ID, value.processSessionId)
            .putString(KEY_ATTEMPT_SESSION_ID, value.attemptSessionId)
            .putString(KEY_REQUESTED_BACKEND, value.requestedBackend)
            .putString(KEY_RESOLVED_BACKEND, value.resolvedBackend)
            .putBoolean(KEY_SCHEMA_ENABLED, value.schemaEnabled)
            .putInt(KEY_SCHEMA_SIZE_CHARS, value.schemaSizeChars)
            .putInt(KEY_ESTIMATED_PROMPT_TOKENS, value.estimatedPromptTokens)
            .putString(KEY_RUNTIME_PHASE, value.runtimePhase)
            .putLong(KEY_EMISSION_COUNT, value.emissionCount)
            .putLong(KEY_PSS_KB, value.pssKb)
            .putBoolean(KEY_INTERACTIVE, value.interactive)
            .putBoolean(KEY_KEYGUARD_SHOWING, value.keyguardShowing)
            .putBoolean(KEY_DEVICE_LOCKED, value.deviceLocked)
            .putLong(KEY_TIMESTAMP_EPOCH_MS, value.timestampEpochMs)
            .commit()

    private fun readPersisted(): Stage17R5CrashBreadcrumb? {
        if (preferences.getInt(KEY_VERSION, 0) != FORMAT_VERSION) return null
        val processSessionId = preferences.getString(KEY_PROCESS_SESSION_ID, null) ?: return null
        val attemptSessionId = preferences.getString(KEY_ATTEMPT_SESSION_ID, null) ?: return null
        val requestedBackend = preferences.getString(KEY_REQUESTED_BACKEND, null) ?: return null
        val resolvedBackend = preferences.getString(KEY_RESOLVED_BACKEND, null) ?: return null
        val runtimePhase = preferences.getString(KEY_RUNTIME_PHASE, null) ?: return null
        val breadcrumb =
            Stage17R5CrashBreadcrumb(
                pid = preferences.getInt(KEY_PID, -1),
                processSessionId = processSessionId,
                attemptSessionId = attemptSessionId,
                requestedBackend = requestedBackend,
                resolvedBackend = resolvedBackend,
                schemaEnabled = preferences.getBoolean(KEY_SCHEMA_ENABLED, false),
                schemaSizeChars = preferences.getInt(KEY_SCHEMA_SIZE_CHARS, 0),
                estimatedPromptTokens = preferences.getInt(KEY_ESTIMATED_PROMPT_TOKENS, 0),
                runtimePhase = runtimePhase,
                emissionCount = preferences.getLong(KEY_EMISSION_COUNT, 0L),
                pssKb = preferences.getLong(KEY_PSS_KB, 0L),
                interactive = preferences.getBoolean(KEY_INTERACTIVE, false),
                keyguardShowing = preferences.getBoolean(KEY_KEYGUARD_SHOWING, false),
                deviceLocked = preferences.getBoolean(KEY_DEVICE_LOCKED, false),
                timestampEpochMs = preferences.getLong(KEY_TIMESTAMP_EPOCH_MS, 0L),
            )
        return enrichWithHistoricalExitInfo(breadcrumb)
    }

    private fun enrichWithHistoricalExitInfo(
        breadcrumb: Stage17R5CrashBreadcrumb,
    ): Stage17R5CrashBreadcrumb {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return breadcrumb
        if (breadcrumb.pid <= 0 || breadcrumb.pid == Process.myPid()) return breadcrumb

        val exitInfo =
            runCatching {
                activityManager
                    .getHistoricalProcessExitReasons(appContext.packageName, breadcrumb.pid, 1)
                    .firstOrNull()
            }.getOrNull() ?: return breadcrumb

        // PID values may eventually be reused. Reject an older historical record rather than
        // attaching it to the current recovered breadcrumb.
        if (exitInfo.timestamp + EXIT_INFO_TIMESTAMP_TOLERANCE_MS < breadcrumb.timestampEpochMs) {
            return breadcrumb
        }

        return breadcrumb.copy(
            exitReason = exitInfo.reason,
            exitReasonLabel = stage17R5ExitReasonLabel(exitInfo.reason),
            exitStatus = exitInfo.status,
            exitImportance = exitInfo.importance,
            exitPssKb = exitInfo.pss.coerceAtLeast(0L),
            exitRssKb = exitInfo.rss.coerceAtLeast(0L),
            exitTimestampEpochMs = exitInfo.timestamp,
        )
    }

    private companion object {
        const val PREFS_NAME = "stage17_r5_crash_breadcrumb"
        const val FORMAT_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_PID = "pid"
        const val KEY_PROCESS_SESSION_ID = "process_session_id"
        const val KEY_ATTEMPT_SESSION_ID = "attempt_session_id"
        const val KEY_REQUESTED_BACKEND = "requested_backend"
        const val KEY_RESOLVED_BACKEND = "resolved_backend"
        const val KEY_SCHEMA_ENABLED = "schema_enabled"
        const val KEY_SCHEMA_SIZE_CHARS = "schema_size_chars"
        const val KEY_ESTIMATED_PROMPT_TOKENS = "estimated_prompt_tokens"
        const val KEY_RUNTIME_PHASE = "runtime_phase"
        const val KEY_EMISSION_COUNT = "emission_count"
        const val KEY_PSS_KB = "pss_kb"
        const val KEY_INTERACTIVE = "interactive"
        const val KEY_KEYGUARD_SHOWING = "keyguard_showing"
        const val KEY_DEVICE_LOCKED = "device_locked"
        const val KEY_TIMESTAMP_EPOCH_MS = "timestamp_epoch_ms"
        const val EXIT_INFO_TIMESTAMP_TOLERANCE_MS = 5_000L
    }
}

internal fun shouldPersistStage17R5GenerationProgress(
    emissionCount: Long,
    nowMs: Long,
    lastPersistedAtMs: Long,
): Boolean {
    if (emissionCount <= 1L) return true
    if (emissionCount % R5_PROGRESS_EMISSION_INTERVAL == 0L) return true
    return lastPersistedAtMs <= 0L || nowMs - lastPersistedAtMs >= R5_PROGRESS_TIME_INTERVAL_MS
}

internal fun stage17R5ExitReasonLabel(reason: Int): String =
    when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }

internal fun Stage17R5CrashBreadcrumb.toQaDisplayText(previousProcess: Boolean): String =
    buildString {
        appendLine("source=${if (previousProcess) "PREVIOUS_PROCESS" else "CURRENT_PROCESS"}")
        appendLine("pid=$pid")
        appendLine("processSession=$processSessionId")
        appendLine("attemptSession=$attemptSessionId")
        appendLine("requestedBackend=$requestedBackend")
        appendLine("resolvedBackend=$resolvedBackend")
        appendLine("schemaEnabled=$schemaEnabled")
        appendLine("schemaSizeChars=$schemaSizeChars")
        appendLine("estimatedPromptTokens=$estimatedPromptTokens")
        appendLine("runtimePhase=$runtimePhase")
        appendLine("emissionCount=$emissionCount")
        appendLine("pssKb=$pssKb")
        appendLine("interactive=$interactive")
        appendLine("keyguard=$keyguardShowing")
        appendLine("deviceLocked=$deviceLocked")
        appendLine("timestampEpochMs=$timestampEpochMs")
        if (previousProcess) {
            if (exitReason != null) {
                appendLine("exitReason=${exitReasonLabel ?: "REASON_$exitReason"}($exitReason)")
                appendLine("exitStatus=${exitStatus ?: "n/a"}")
                appendLine("exitImportance=${exitImportance ?: "n/a"}")
                appendLine("exitPssKb=${exitPssKb ?: "n/a"}")
                appendLine("exitRssKb=${exitRssKb ?: "n/a"}")
                append("exitTimestampEpochMs=${exitTimestampEpochMs ?: "n/a"}")
            } else {
                append("exitReason=UNAVAILABLE")
            }
        } else {
            append("exitReason=CURRENT_PROCESS")
        }
    }

private const val R5_PROGRESS_EMISSION_INTERVAL = 16L
private const val R5_PROGRESS_TIME_INTERVAL_MS = 1_000L
