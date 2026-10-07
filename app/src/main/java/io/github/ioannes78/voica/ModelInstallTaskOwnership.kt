package io.github.ioannes78.voica

import android.app.ActivityManager
import android.content.Context

internal data class ModelInstallTaskOwner(
    val operationId: String,
    val executorGeneration: Long,
    val taskId: Int,
)

internal class ModelInstallTaskOwnershipStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun registerCurrentTask(taskId: Int) {
        if (taskId < 0) return
        preferences.edit().putInt(KEY_CURRENT_TASK_ID, taskId).commit()
    }

    fun currentTaskId(): Int? =
        preferences
            .getInt(KEY_CURRENT_TASK_ID, TASK_ID_UNKNOWN)
            .takeIf { it >= 0 }

    fun bindIfCurrentTaskActive(record: ModelInstallJournalRecord): ModelInstallTaskOwner? {
        if (record.origin != ModelInstallOrigin.MANUAL ||
            record.phase.terminal ||
            record.requiresUserResume ||
            record.executorKind == ModelInstallExecutorKind.NONE
        ) {
            return null
        }
        val taskId = currentTaskId() ?: return null
        val activeTaskIds = currentAppTaskIds(appContext) ?: return null
        if (taskId !in activeTaskIds) return null

        val owner =
            ModelInstallTaskOwner(
                operationId = record.operationId,
                executorGeneration = record.executorGeneration,
                taskId = taskId,
            )
        preferences.edit()
            .putString(ownerKey(record.operationId), encode(owner))
            .commit()
        return owner
    }

    fun ownerFor(operationId: String): ModelInstallTaskOwner? =
        preferences.getString(ownerKey(operationId), null)
            ?.let { value -> decode(operationId, value) }

    fun clear(operationId: String) {
        preferences.edit().remove(ownerKey(operationId)).apply()
    }

    private fun ownerKey(operationId: String): String = KEY_OWNER_PREFIX + operationId

    private fun encode(owner: ModelInstallTaskOwner): String =
        owner.executorGeneration.toString() + SEPARATOR + owner.taskId

    private fun decode(
        operationId: String,
        value: String,
    ): ModelInstallTaskOwner? {
        val separatorIndex = value.indexOf(SEPARATOR)
        if (separatorIndex <= 0 || separatorIndex == value.lastIndex) return null
        val generation = value.substring(0, separatorIndex).toLongOrNull() ?: return null
        val taskId = value.substring(separatorIndex + 1).toIntOrNull() ?: return null
        if (generation < 1L || taskId < 0) return null
        return ModelInstallTaskOwner(operationId, generation, taskId)
    }

    private companion object {
        const val PREFERENCES_NAME = "model-install-task-ownership"
        const val KEY_CURRENT_TASK_ID = "current-task-id"
        const val KEY_OWNER_PREFIX = "owner:"
        const val TASK_ID_UNKNOWN = -1
        const val SEPARATOR = "|"
    }
}

internal fun currentAppTaskIds(context: Context): Set<Int>? =
    runCatching {
        context.getSystemService(ActivityManager::class.java)
            .appTasks
            .mapTo(linkedSetOf()) { appTask -> appTask.taskInfo.taskId }
    }.getOrNull()

internal fun shouldInterruptForMissingOwnerTask(
    owner: ModelInstallTaskOwner?,
    record: ModelInstallJournalRecord,
    activeTaskIds: Set<Int>,
): Boolean =
    owner != null &&
        owner.operationId == record.operationId &&
        record.origin == ModelInstallOrigin.MANUAL &&
        !record.phase.terminal &&
        !record.requiresUserResume &&
        record.executorKind != ModelInstallExecutorKind.NONE &&
        owner.executorGeneration == record.executorGeneration &&
        owner.taskId !in activeTaskIds
