package io.github.ioannes78.voica

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.AiSummaryStateValue
import java.util.concurrent.CancellationException

interface AiSummaryWorkScheduler {
    fun enqueue(
        summaryId: String,
        generation: Long,
        replaceExisting: Boolean = false,
    )

    fun cancel(summaryId: String)
}

internal class AndroidAiSummaryWorkScheduler(
    context: Context,
) : AiSummaryWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun enqueue(
        summaryId: String,
        generation: Long,
        replaceExisting: Boolean,
    ) {
        require(summaryId.isNotBlank())
        require(generation >= 1L)
        val request =
            OneTimeWorkRequestBuilder<AiSummaryWorker>()
                .setInputData(
                    workDataOf(
                        AiSummaryWorkNames.KEY_SUMMARY_ID to summaryId,
                        AiSummaryWorkNames.KEY_GENERATION to generation,
                    ),
                )
                .addTag(AiSummaryWorkNames.tag(summaryId))
                .build()
        workManager.enqueueUniqueWork(
            AiSummaryWorkNames.unique(summaryId),
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel(summaryId: String) {
        require(summaryId.isNotBlank())
        workManager.cancelUniqueWork(AiSummaryWorkNames.unique(summaryId))
    }
}

class AiSummaryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val summaryId =
            inputData.getString(AiSummaryWorkNames.KEY_SUMMARY_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return Result.failure()
        val generation = inputData.getLong(AiSummaryWorkNames.KEY_GENERATION, -1L)
        if (generation < 1L) return Result.failure()

        val application = applicationContext as? VoicaApplication ?: return Result.failure()
        val container = runCatching { application.container }.getOrNull() ?: return Result.failure()
        return try {
            if (!container.aiSummaryRepository.ensureOwnerTaskActive(summaryId, generation)) {
                return Result.success()
            }
            container.aiSummaryCoordinator.executeDurable(summaryId, generation)
            // Remote LLM requests are never replayed through WorkManager Result.retry().
            // Recovery eligibility is decided only from Room inside executeDurable().
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The coordinator persists a business failure when possible. A platform retry here
            // would be unsafe because it could resend a request whose remote outcome is unknown.
            Result.success()
        }
    }
}

internal suspend fun recoverAiSummaryWorkOnStartup(
    repository: AiSummaryRepository,
    scheduler: AiSummaryWorkScheduler,
) {
    repository.loadActiveSummaries().forEach { summary ->
        runCatching {
            if (summary.executionGeneration < 1L) {
                // A pre-v8 active row has no durable send boundary. Its remote outcome is unknown,
                // so upgrading must never turn it into an automatic provider replay.
                repository.transition(
                    summaryId = summary.id,
                    status = AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
                    errorCode = "REMOTE_RESULT_UNKNOWN",
                    sanitizedErrorMessage = "上一次请求状态无法确认，需要手动重试。",
                )
            } else if (repository.ensureOwnerTaskActive(summary.id, summary.executionGeneration)) {
                // KEEP is intentional: WorkManager may already be restoring this same unique work.
                // The Worker alone classifies NONE / READY_TO_SEND / REQUEST_IN_FLIGHT.
                scheduler.enqueue(summary.id, summary.executionGeneration)
            }
        }
    }
}

internal object AiSummaryWorkNames {
    const val KEY_SUMMARY_ID = "ai-summary-id"
    const val KEY_GENERATION = "ai-summary-generation"

    fun unique(summaryId: String): String = "ai-summary:$summaryId"

    fun tag(summaryId: String): String = "ai-summary-task:$summaryId"
}
