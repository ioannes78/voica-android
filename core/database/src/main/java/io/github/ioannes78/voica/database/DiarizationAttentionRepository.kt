package io.github.ioannes78.voica.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class DiarizationAttentionKind {
    DIARIZATION,
    ALIGNMENT,
}

data class DiarizationRunAttentionRow(
    val id: String,
    val recordingId: String,
    val state: String,
    val configSnapshot: String,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class DiarizationAlignmentAttentionRow(
    val id: String,
    val recordingId: String,
    val transcriptionId: String,
    val diarizationRunId: String,
    val state: String,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class DiarizationAttentionItem(
    val kind: DiarizationAttentionKind,
    val id: String,
    val recordingId: String,
    val state: String,
    val completedAtMs: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val configSnapshot: String? = null,
    val transcriptionId: String? = null,
    val diarizationRunId: String? = null,
)

/**
 * Durable attention projection for Stage 13C speaker work.
 *
 * Room remains the fact source. Only interrupted/failed terminal states that have not been
 * acknowledged are surfaced. User cancellation is intentionally excluded.
 */
class DiarizationAttentionRepository(
    database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.diarizationDao()

    fun observeAll(): Flow<List<DiarizationAttentionItem>> =
        combine(
            dao.observeRunAttention(),
            dao.observeAlignmentAttention(),
        ) { runs, alignments ->
            merge(runs, alignments)
        }

    fun observeForRecording(recordingId: String): Flow<List<DiarizationAttentionItem>> =
        combine(
            dao.observeRunAttention(recordingId),
            dao.observeAlignmentAttention(recordingId),
        ) { runs, alignments ->
            merge(runs, alignments)
        }

    suspend fun acknowledge(item: DiarizationAttentionItem): Boolean =
        when (item.kind) {
            DiarizationAttentionKind.DIARIZATION ->
                dao.acknowledgeRunAttention(item.id, nowMs()) == 1
            DiarizationAttentionKind.ALIGNMENT ->
                dao.acknowledgeAlignmentAttention(item.id, nowMs()) == 1
        }

    suspend fun acknowledgeSupersededRun(
        recordingId: String,
        replacementRunId: String,
    ): Int =
        dao.acknowledgeSupersededRunAttention(
            recordingId = recordingId,
            replacementRunId = replacementRunId,
            nowMs = nowMs(),
        )

    suspend fun acknowledgeSupersededAlignment(
        transcriptionId: String,
        diarizationRunId: String,
        replacementAlignmentId: String,
    ): Int =
        dao.acknowledgeSupersededAlignmentAttention(
            transcriptionId = transcriptionId,
            diarizationRunId = diarizationRunId,
            replacementAlignmentId = replacementAlignmentId,
            nowMs = nowMs(),
        )

    private fun merge(
        runs: List<DiarizationRunAttentionRow>,
        alignments: List<DiarizationAlignmentAttentionRow>,
    ): List<DiarizationAttentionItem> =
        buildList {
            runs.forEach { run ->
                add(
                    DiarizationAttentionItem(
                        kind = DiarizationAttentionKind.DIARIZATION,
                        id = run.id,
                        recordingId = run.recordingId,
                        state = run.state,
                        completedAtMs = run.completedAtMs,
                        errorCode = run.errorCode,
                        errorMessage = run.errorMessage,
                        configSnapshot = run.configSnapshot,
                    ),
                )
            }
            alignments.forEach { alignment ->
                add(
                    DiarizationAttentionItem(
                        kind = DiarizationAttentionKind.ALIGNMENT,
                        id = alignment.id,
                        recordingId = alignment.recordingId,
                        state = alignment.state,
                        completedAtMs = alignment.completedAtMs,
                        errorCode = alignment.errorCode,
                        errorMessage = alignment.errorMessage,
                        transcriptionId = alignment.transcriptionId,
                        diarizationRunId = alignment.diarizationRunId,
                    ),
                )
            }
        }.sortedByDescending { it.completedAtMs ?: Long.MIN_VALUE }
}
