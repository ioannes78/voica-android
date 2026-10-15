package io.github.ioannes78.voica.ui.status

import io.github.ioannes78.voica.ModelInstallJournalRecord
import io.github.ioannes78.voica.ModelInstallPhase
import io.github.ioannes78.voica.modelInstallNotificationPhaseLabel

internal fun buildModelInstallActivityFacts(
    operations: Map<String, ModelInstallJournalRecord>,
): List<AppActivityFact> =
    operations.values
        .sortedBy(ModelInstallJournalRecord::createdAtMs)
        .mapNotNull(::modelInstallActivityFact)

internal fun modelInstallActivityFact(record: ModelInstallJournalRecord): AppActivityFact? {
    if (record.phase == ModelInstallPhase.READY || record.phase == ModelInstallPhase.CANCELLED) {
        return null
    }
    val displayName = record.snapshot.descriptor.displayName
    val lifecycle =
        when (record.phase) {
            ModelInstallPhase.INTERRUPTED,
            ModelInstallPhase.FAILED_RECOVERABLE,
            -> AppActivityLifecycleClass.INTERRUPTED_ATTENTION
            ModelInstallPhase.FAILED_INTEGRITY,
            ModelInstallPhase.FAILED_RUNTIME,
            ModelInstallPhase.FAILED_CONFIGURATION,
            -> AppActivityLifecycleClass.FAILED_ATTENTION
            else ->
                if (record.requiresUserResume) {
                    AppActivityLifecycleClass.INTERRUPTED_ATTENTION
                } else {
                    AppActivityLifecycleClass.BACKGROUND_RUNNING
                }
        }
    val progress =
        if (record.phase == ModelInstallPhase.DOWNLOAD) {
            val total = record.totalBytes?.takeIf { it > 0L }
            total?.let { (record.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
        } else {
            null
        }
    val actions =
        when (lifecycle) {
            AppActivityLifecycleClass.INTERRUPTED_ATTENTION -> setOf(AppActivityAction.RETRY)
            AppActivityLifecycleClass.FAILED_ATTENTION -> setOf(AppActivityAction.OPEN)
            AppActivityLifecycleClass.BACKGROUND_RUNNING ->
                if (record.cancelRequested) emptySet() else setOf(AppActivityAction.CANCEL)
            else -> emptySet()
        }
    return AppActivityFact(
        logicalKey = "model-install:${record.operationId}",
        kind = AppActivityKind.MODEL_INSTALL,
        source = AppActivitySource.MODEL_MANAGER,
        lifecycleClass = lifecycle,
        title =
            when (lifecycle) {
                AppActivityLifecycleClass.INTERRUPTED_ATTENTION -> "模型安装需要继续"
                AppActivityLifecycleClass.FAILED_ATTENTION -> "模型安装失败"
                else -> "模型安装中"
            },
        subtitle = "$displayName · ${modelInstallNotificationPhaseLabel(record.phase)}",
        progressFraction = progress,
        taskId = record.operationId,
        availableActions = actions,
        priorityHint = MODEL_INSTALL_PRIORITY,
        startedAtMs = record.createdAtMs,
    )
}

private const val MODEL_INSTALL_PRIORITY = 30
