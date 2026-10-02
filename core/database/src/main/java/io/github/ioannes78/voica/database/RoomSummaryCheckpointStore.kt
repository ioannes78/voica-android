package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.SummaryCheckpointRecord
import io.github.ioannes78.voica.ai.SummaryCheckpointStore

class RoomSummaryCheckpointStore(
    private val repository: AiSummaryRepository,
    private val summaryId: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SummaryCheckpointStore {
    init {
        require(summaryId.isNotBlank())
    }

    override suspend fun load(
        level: Int,
        chunkIndex: Int,
        inputDigest: String,
    ): String? {
        val chunk =
            repository.findChunk(
                summaryId = summaryId,
                level = level,
                chunkIndex = chunkIndex,
            ) ?: return null
        if (chunk.inputDigest != inputDigest || chunk.status != STATUS_COMPLETED) {
            return null
        }
        return chunk.structuredResultJson?.takeIf { it.isNotBlank() }
    }

    override suspend fun save(record: SummaryCheckpointRecord) {
        repository.upsertChunk(
            AiSummaryChunkEntity(
                id = deterministicId(record.level, record.chunkIndex),
                aiSummaryId = summaryId,
                level = record.level,
                chunkIndex = record.chunkIndex,
                sourceStartOrdinal = record.sourceStartOrdinal,
                sourceEndOrdinalExclusive = record.sourceEndOrdinalExclusive,
                startSampleIndex = record.startSampleIndex,
                endSampleIndexExclusive = record.endSampleIndexExclusive,
                inputDigest = record.inputDigest,
                status = STATUS_COMPLETED,
                attempt = 1,
                structuredResultJson = record.structuredResultJson,
                errorCode = null,
                sanitizedErrorMessage = null,
                updatedAtMs = nowMs(),
            ),
        )
    }

    private fun deterministicId(
        level: Int,
        chunkIndex: Int,
    ): String = "$summaryId:$level:$chunkIndex"

    private companion object {
        const val STATUS_COMPLETED = "COMPLETED"
    }
}
