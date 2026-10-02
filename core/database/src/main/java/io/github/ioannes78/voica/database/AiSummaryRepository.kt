package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class NewAiSummaryRequest(
    val recordingId: String,
    val transcriptionId: String,
    val mode: String,
    val templateId: String?,
    val templateSnapshot: String?,
    val providerProfileId: String,
    val providerNameSnapshot: String,
    val baseUrlSnapshot: String,
    val model: String,
    val promptVersion: Int,
    val resultSchemaVersion: Int,
    val requestConfigSnapshot: String,
    val alignmentIdSnapshot: String?,
    val sourceLineageSnapshot: String,
)

data class AiSummaryEvidenceWrite(
    val summaryItemId: String,
    val sourceRef: String,
    val sourceKind: String,
    val sourceId: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val speakerId: String?,
    val assignmentQuality: String?,
)

class AiSummaryRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.aiSummaryDao()
    private val transcriptionDao = database.transcriptionDao()

    fun observeForRecording(recordingId: String): Flow<List<AiSummaryEntity>> =
        dao.observeForRecording(recordingId)

    fun observeForTranscription(transcriptionId: String): Flow<List<AiSummaryEntity>> =
        dao.observeForTranscription(transcriptionId)

    suspend fun find(summaryId: String): AiSummaryEntity? = dao.findSummary(summaryId)

    suspend fun create(request: NewAiSummaryRequest): String {
        require(request.recordingId.isNotBlank())
        require(request.transcriptionId.isNotBlank())
        require(request.mode in setOf(AiSummaryModeValue.SMART, AiSummaryModeValue.PRESET, AiSummaryModeValue.CUSTOM))
        require(request.providerProfileId.isNotBlank())
        require(request.providerNameSnapshot.isNotBlank())
        require(request.baseUrlSnapshot.isNotBlank())
        require(request.model.isNotBlank())
        require(request.promptVersion >= 1)
        require(request.resultSchemaVersion >= 1)
        require(request.requestConfigSnapshot.isNotBlank())
        require(request.sourceLineageSnapshot.isNotBlank())

        val transcription =
            transcriptionDao.findTranscription(request.transcriptionId)
                ?: error("transcription not found")
        require(transcription.recordingId == request.recordingId)
        require(transcription.state == TranscriptionStateValue.COMPLETED)

        val now = nowMs()
        val id = newId()
        dao.insertSummary(
            AiSummaryEntity(
                id = id,
                recordingId = request.recordingId,
                transcriptionId = request.transcriptionId,
                inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
                mode = request.mode,
                templateId = request.templateId,
                templateSnapshot = request.templateSnapshot,
                providerProfileId = request.providerProfileId,
                providerNameSnapshot = request.providerNameSnapshot,
                baseUrlSnapshot = request.baseUrlSnapshot,
                model = request.model,
                promptVersion = request.promptVersion,
                resultSchemaVersion = request.resultSchemaVersion,
                contentType = null,
                classificationConfidence = null,
                structuredPayloadJson = null,
                displayText = null,
                status = AiSummaryStateValue.CREATED,
                createdAtMs = now,
                startedAtMs = null,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = null,
                sanitizedErrorMessage = null,
                requestConfigSnapshot = request.requestConfigSnapshot,
                usageSnapshot = null,
                alignmentIdSnapshot = request.alignmentIdSnapshot,
                sourceLineageSnapshot = request.sourceLineageSnapshot,
            ),
        )
        return id
    }

    suspend fun transition(
        summaryId: String,
        status: String,
        errorCode: String? = null,
        sanitizedErrorMessage: String? = null,
    ) {
        require(status in ALL_STATES)
        val current = dao.findSummary(summaryId) ?: error("AI summary not found")
        if (current.status == status) return
        require(current.status in AiSummaryStateValue.ACTIVE) {
            "terminal AI summary cannot transition"
        }

        val now = nowMs()
        val terminal = status in TERMINAL_STATES
        check(
            dao.updateState(
                summaryId = summaryId,
                status = status,
                startedAtMs = current.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = if (terminal) now else null,
                errorCode = errorCode,
                sanitizedErrorMessage = sanitizedErrorMessage,
            ) == 1,
        )
    }

    suspend fun persistCompleted(
        summaryId: String,
        contentType: String,
        classificationConfidence: Double?,
        structuredPayloadJson: String,
        displayText: String,
        usageSnapshot: String?,
        evidence: List<AiSummaryEvidenceWrite>,
    ) {
        require(contentType.isNotBlank())
        require(classificationConfidence == null || classificationConfidence in 0.0..1.0)
        require(structuredPayloadJson.isNotBlank())
        require(displayText.isNotBlank())

        val summary = dao.findSummary(summaryId) ?: error("AI summary not found")
        require(summary.status in AiSummaryStateValue.ACTIVE)
        validateEvidence(evidence)

        val now = nowMs()
        database.withTransaction {
            if (evidence.isNotEmpty()) {
                dao.insertEvidence(
                    evidence.map { item ->
                        AiSummaryEvidenceEntity(
                            id = newId(),
                            aiSummaryId = summaryId,
                            summaryItemId = item.summaryItemId,
                            sourceRef = item.sourceRef,
                            sourceKind = item.sourceKind,
                            sourceId = item.sourceId,
                            startSampleIndex = item.startSampleIndex,
                            endSampleIndexExclusive = item.endSampleIndexExclusive,
                            speakerId = item.speakerId,
                            assignmentQuality = item.assignmentQuality,
                        )
                    },
                )
            }
            check(
                dao.complete(
                    summaryId = summaryId,
                    contentType = contentType,
                    classificationConfidence = classificationConfidence,
                    structuredPayloadJson = structuredPayloadJson,
                    displayText = displayText,
                    usageSnapshot = usageSnapshot,
                    completedAtMs = now,
                ) == 1,
            )
        }
    }

    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity> =
        dao.loadEvidence(summaryId)

    suspend fun loadChunks(summaryId: String): List<AiSummaryChunkEntity> =
        dao.loadChunks(summaryId)

    suspend fun upsertChunk(chunk: AiSummaryChunkEntity) {
        require(chunk.aiSummaryId.isNotBlank())
        require(chunk.level >= 0)
        require(chunk.chunkIndex >= 0)
        require(chunk.sourceStartOrdinal >= 0)
        require(chunk.sourceEndOrdinalExclusive > chunk.sourceStartOrdinal)
        require(chunk.startSampleIndex >= 0)
        require(chunk.endSampleIndexExclusive > chunk.startSampleIndex)
        require(chunk.inputDigest.isNotBlank())
        require(chunk.attempt >= 1)
        dao.upsertChunk(chunk)
    }

    suspend fun reconcileInterruptedOnStartup(): Int =
        dao.markActiveInterrupted(
            activeStates = AiSummaryStateValue.ACTIVE,
            nowMs = nowMs(),
        )

    private fun validateEvidence(evidence: List<AiSummaryEvidenceWrite>) {
        val unique = mutableSetOf<Pair<String, String>>()
        evidence.forEach { item ->
            require(item.summaryItemId.isNotBlank())
            require(SOURCE_REF.matches(item.sourceRef))
            require(item.sourceKind.isNotBlank())
            require(item.sourceId.isNotBlank())
            require(item.startSampleIndex >= 0)
            require(item.endSampleIndexExclusive > item.startSampleIndex)
            require(unique.add(item.summaryItemId to item.sourceRef)) {
                "duplicate summary item evidence ref"
            }
        }
    }

    private fun newId(): String = idFactory().also { require(it.isNotBlank()) }

    private companion object {
        val SOURCE_REF = Regex("""S\d{5,}""")
        val TERMINAL_STATES =
            setOf(
                AiSummaryStateValue.COMPLETED,
                AiSummaryStateValue.FAILED,
                AiSummaryStateValue.CANCELLED,
                AiSummaryStateValue.INTERRUPTED,
            )
        val ALL_STATES = AiSummaryStateValue.ACTIVE.toSet() + TERMINAL_STATES
    }
}
