package io.github.ioannes78.voica

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.CancellationException

internal interface AiSummaryWorkScheduler {
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

internal object AiSummaryWorkNames {
    const val KEY_SUMMARY_ID = "ai-summary-id"
    const val KEY_GENERATION = "ai-summary-generation"

    fun unique(summaryId: String): String = "ai-summary:$summaryId"

    fun tag(summaryId: String): String = "ai-summary-task:$summaryId"
}
