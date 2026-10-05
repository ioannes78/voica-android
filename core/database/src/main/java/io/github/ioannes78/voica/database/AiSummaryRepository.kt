package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import java.util.concurrent.CancellationException
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

data class SaveAiCustomTemplateRequest(
    val id: String? = null,
    val name: String,
    val schemaVersion: Int = 1,
    val sectionsConfigJson: String,
    val focus: String?,
    val userInstruction: String?,
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

    suspend fun recordingIdForTranscription(transcriptionId: String): String? =
        transcriptionDao.findTranscription(transcriptionId)?.recordingId

    fun observeCustomTemplates(): Flow<List<AiCustomTemplateEntity>> =
        dao.observeCustomTemplates()

    suspend fun findCustomTemplate(templateId: String): AiCustomTemplateEntity? =
        dao.findCustomTemplate(templateId)

    suspend fun saveCustomTemplate(request: SaveAiCustomTemplateRequest): String {
        require(request.name.isNotBlank())
        require(request.schemaVersion >= 1)
        require(request.sectionsConfigJson.isNotBlank())
        require(request.userInstruction == null || request.userInstruction.length <= 4_000)
        val now = nowMs()
        val id = request.id?.takeIf { it.isNotBlank() } ?: newId()
        val existing = dao.findCustomTemplate(id)
        dao.upsertCustomTemplate(
            AiCustomTemplateEntity(
                id = id,
                name = request.name.trim().take(120),
                schemaVersion = request.schemaVersion,
                sectionsConfigJson = request.sectionsConfigJson,
                focus = request.focus?.trim()?.take(2_000),
                userInstruction = request.userInstruction?.trim()?.take(4_000),
                createdAtMs = existing?.createdAtMs ?: now,
                updatedAtMs = now,
            ),
        )
        return id
    }

    suspend fun deleteCustomTemplate(templateId: String): Boolean =
        dao.deleteCustomTemplate(templateId) == 1

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
    ): Boolean {
        require(status in ALL_STATES)
        val current = dao.findSummary(summaryId) ?: error("AI summary not found")
        if (current.status == status) return true
        if (current.status !in AiSummaryStateValue.ACTIVE) return false

        val now = nowMs()
        val terminal = status in TERMINAL_STATES
        return dao.updateState(
            summaryId = summaryId,
            status = status,
            startedAtMs = current.startedAtMs ?: now,
            updatedAtMs = now,
            completedAtMs = if (terminal) now else null,
            errorCode = errorCode,
            sanitizedErrorMessage = sanitizedErrorMessage,
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun persistCompleted(
        summaryId: String,
        contentType: String,
        classificationConfidence: Double?,
        structuredPayloadJson: String,
        displayText: String,
        usageSnapshot: String?,
        evidence: List<AiSummaryEvidenceWrite>,
    ): Boolean {
        require(contentType.isNotBlank())
        require(classificationConfidence == null || classificationConfidence in 0.0..1.0)
        require(structuredPayloadJson.isNotBlank())
        require(displayText.isNotBlank())
        validateEvidence(evidence)

        val now = nowMs()
        val completed =
            database.withTransaction {
                val updated =
                    dao.complete(
                        summaryId = summaryId,
                        contentType = contentType,
                        classificationConfidence = classificationConfidence,
                        structuredPayloadJson = structuredPayloadJson,
                        displayText = displayText,
                        usageSnapshot = usageSnapshot,
                        completedAtMs = now,
                        activeStates = AiSummaryStateValue.ACTIVE,
                    )
                if (updated != 1) return@withTransaction false
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
                true
            }
        if (completed) {
            try {
                SearchIndexRebuilder(database).reindexAiSummary(summaryId)
            } catch (_: CancellationException) {
                // Completion is already committed atomically. Search indexing is rebuildable and
                // must not turn a completed summary back into a cancellation race.
            }
        }
        return completed
    }

    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity> =
        dao.loadEvidence(summaryId)

    suspend fun resumeInterrupted(summaryId: String): Boolean =
        dao.resumeInterrupted(summaryId, nowMs()) == 1

    suspend fun findChunk(
        summaryId: String,
        level: Int,
        chunkIndex: Int,
    ): AiSummaryChunkEntity? =
        dao.findChunk(summaryId, level, chunkIndex)

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
