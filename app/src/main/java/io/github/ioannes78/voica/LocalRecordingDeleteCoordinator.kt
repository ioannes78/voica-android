package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.database.LibraryDeleteResult
import io.github.ioannes78.voica.database.LocalDeletePreparation
import io.github.ioannes78.voica.database.RecordingLibraryRepository

data class LocalRecordingDeleteItemResult(
    val recordingId: String,
    val deleted: Boolean,
    val failedPaths: List<String> = emptyList(),
    val lifecycleError: String? = null,
)

data class LocalRecordingBatchDeleteResult(
    val items: List<LocalRecordingDeleteItemResult>,
) {
    val deletedCount: Int
        get() = items.count { it.deleted }

    val failedCount: Int
        get() = items.size - deletedCount
}

interface RecordingDeletionHooks {
    suspend fun stopPlaybackIfTarget(recordingId: String)
    suspend fun cancelCanonicalAndAwait(recordingId: String)
    suspend fun cancelTranscriptionAndAwait(recordingId: String)
    suspend fun cancelDiarizationAndAwait(recordingId: String)
    suspend fun cancelAiSummaryAndAwait(recordingId: String)
}

class DefaultRecordingDeletionHooks(
    private val playbackController: PlaybackController,
    private val canonicalAudioCoordinator: CanonicalAudioCoordinator,
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val diarizationCoordinator: DiarizationCoordinator,
    private val aiSummaryCoordinator: AiSummaryCoordinator,
) : RecordingDeletionHooks {
    override suspend fun stopPlaybackIfTarget(recordingId: String) {
        if (playbackController.snapshot.value.recordingId == recordingId) {
            playbackController.unload()
        }
    }

    override suspend fun cancelCanonicalAndAwait(recordingId: String) {
        canonicalAudioCoordinator.cancelAndAwait(recordingId)
    }

    override suspend fun cancelTranscriptionAndAwait(recordingId: String) {
        transcriptionCoordinator.cancelAndAwait(recordingId)
    }

    override suspend fun cancelDiarizationAndAwait(recordingId: String) {
        diarizationCoordinator.cancelAndAwait(recordingId)
    }

    override suspend fun cancelAiSummaryAndAwait(recordingId: String) {
        aiSummaryCoordinator.cancelAndAwait(recordingId)
    }
}

class LocalRecordingDeleteCoordinator(
    private val repository: RecordingLibraryRepository,
    private val hooks: RecordingDeletionHooks,
) {
    suspend fun delete(recordingId: String): LocalRecordingDeleteItemResult {
        require(recordingId.isNotBlank())

        when (repository.prepareLocalDelete(recordingId)) {
            LocalDeletePreparation.MISSING ->
                return LocalRecordingDeleteItemResult(
                    recordingId = recordingId,
                    deleted = true,
                )

            LocalDeletePreparation.READY,
            LocalDeletePreparation.ALREADY_DELETING,
            -> Unit
        }

        val lifecycleFailure =
            runCatching {
                hooks.stopPlaybackIfTarget(recordingId)
                hooks.cancelCanonicalAndAwait(recordingId)
                hooks.cancelTranscriptionAndAwait(recordingId)
                hooks.cancelDiarizationAndAwait(recordingId)
                hooks.cancelAiSummaryAndAwait(recordingId)
            }.exceptionOrNull()

        if (lifecycleFailure != null) {
            return LocalRecordingDeleteItemResult(
                recordingId = recordingId,
                deleted = false,
                lifecycleError =
                    lifecycleFailure.message
                        ?: lifecycleFailure::class.java.simpleName,
            )
        }

        val result: LibraryDeleteResult = repository.finishLocalDelete(recordingId)
        return LocalRecordingDeleteItemResult(
            recordingId = recordingId,
            deleted = result.deleted,
            failedPaths = result.failedPaths,
        )
    }

    suspend fun deleteBatch(
        recordingIds: Collection<String>,
    ): LocalRecordingBatchDeleteResult {
        val results = ArrayList<LocalRecordingDeleteItemResult>()
        recordingIds
            .distinct()
            .forEach { recordingId ->
                results += delete(recordingId)
            }
        return LocalRecordingBatchDeleteResult(results)
    }
}
