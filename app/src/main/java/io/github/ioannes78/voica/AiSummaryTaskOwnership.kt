package io.github.ioannes78.voica

import android.content.Context

internal class AiSummaryTaskOwnershipStore(
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

    fun isTaskActive(taskId: Int): Boolean {
        if (taskId < 0) return false
        return currentAppTaskIds(appContext)?.contains(taskId) == true
    }

    private companion object {
        const val PREFERENCES_NAME = "ai-summary-task-ownership"
        const val KEY_CURRENT_TASK_ID = "current-task-id"
        const val TASK_ID_UNKNOWN = -1
    }
}
