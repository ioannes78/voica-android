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
    val retryOfSummaryId: String? = null,
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

    fun observeDurableTasks(): Flow<List<AiSummaryEntity>> =
        dao.observeDurableTasks(
            activeStates = AiSummaryStateValue.ACTIVE,
            terminalAttentionStates = ATTENTION_TERMINAL_STATES,
        )

    suspend fun loadActiveSummaries(): List<AiSummaryEntity> =
        dao.loadActiveSummaries(AiSummaryStateValue.ACTIVE)

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
        require(request.retryOfSummaryId == null || request.retryOfSummaryId.isNotBlank())

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
                executionGeneration = 1L,
                remoteDispatchState = AiSummaryRemoteDispatchStateValue.NONE,
                remoteCallOrdinal = 0,
                retryOfSummaryId = request.retryOfSummaryId,
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

    suspend fun transitionForGeneration(
        summaryId: String,
        generation: Long,
        status: String,
        errorCode: String? = null,
        sanitizedErrorMessage: String? = null,
    ): Boolean {
        require(generation >= 1L)
        require(status in ALL_STATES)
        val current = dao.findSummary(summaryId) ?: return false
        if (current.executionGeneration != generation) return false
        if (current.status == status) return true
        if (current.status !in AiSummaryStateValue.ACTIVE) return false

        val now = nowMs()
        val terminal = status in TERMINAL_STATES
        return dao.updateStateForGeneration(
            summaryId = summaryId,
            generation = generation,
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
        validateCompletion(contentType, classificationConfidence, structuredPayloadJson, displayText, evidence)
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
                insertEvidence(summaryId, evidence)
                true
            }
        if (completed) onCompleted(summaryId)
        return completed
    }

    suspend fun persistCompletedForGeneration(
        summaryId: String,
        generation: Long,
        contentType: String,
        classificationConfidence: Double?,
        structuredPayloadJson: String,
        displayText: String,
        usageSnapshot: String?,
        evidence: List<AiSummaryEvidenceWrite>,
    ): Boolean {
        require(generation >= 1L)
        validateCompletion(contentType, classificationConfidence, structuredPayloadJson, displayText, evidence)
        val now = nowMs()
        val completed =
            database.withTransaction {
                val updated =
                    dao.completeForGeneration(
                        summaryId = summaryId,
                        generation = generation,
                        contentType = contentType,
                        classificationConfidence = classificationConfidence,
                        structuredPayloadJson = structuredPayloadJson,
                        displayText = displayText,
                        usageSnapshot = usageSnapshot,
                        completedAtMs = now,
                        activeStates = AiSummaryStateValue.ACTIVE,
                    )
                if (updated != 1) return@withTransaction false
                insertEvidence(summaryId, evidence)
                true
            }
        if (completed) onCompleted(summaryId)
        return completed
    }

    suspend fun loadEvidence(summaryId: String): List<AiSummaryEvidenceEntity> =
        dao.loadEvidence(summaryId)

    suspend fun prepareRemoteCall(
        summaryId: String,
        generation: Long,
        requestId: String,
        stepKind: String,
        stepKey: String,
    ): Boolean {
        require(generation >= 1L)
        require(requestId.isNotBlank())
        require(stepKind.isNotBlank())
        require(stepKey.isNotBlank())
        return dao.prepareRemoteCall(
            summaryId = summaryId,
            generation = generation,
            requestId = requestId,
            stepKind = stepKind,
            stepKey = stepKey,
            nowMs = nowMs(),
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun markRemoteRequestInFlight(
        summaryId: String,
        generation: Long,
        requestId: String,
        stepKind: String,
        stepKey: String,
    ): Boolean {
        require(generation >= 1L)
        require(requestId.isNotBlank())
        require(stepKind.isNotBlank())
        require(stepKey.isNotBlank())
        return dao.markRemoteRequestInFlight(
            summaryId = summaryId,
            generation = generation,
            requestId = requestId,
            stepKind = stepKind,
            stepKey = stepKey,
            nowMs = nowMs(),
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun replaceRemoteRequestInFlight(
        summaryId: String,
        generation: Long,
        expectedRequestId: String,
        newRequestId: String,
        stepKind: String,
        stepKey: String,
    ): Boolean {
        require(generation >= 1L)
        require(expectedRequestId.isNotBlank())
        require(newRequestId.isNotBlank())
        require(stepKind.isNotBlank())
        require(stepKey.isNotBlank())
        return dao.replaceRemoteRequestInFlight(
            summaryId = summaryId,
            generation = generation,
            expectedRequestId = expectedRequestId,
            newRequestId = newRequestId,
            stepKind = stepKind,
            stepKey = stepKey,
            nowMs = nowMs(),
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun resetPreparedRemoteCall(
        summaryId: String,
        generation: Long,
        requestId: String,
    ): Boolean {
        require(generation >= 1L)
        require(requestId.isNotBlank())
        return dao.resetPreparedRemoteCall(
            summaryId = summaryId,
            generation = generation,
            requestId = requestId,
            nowMs = nowMs(),
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun clearRemoteDispatch(
        summaryId: String,
        generation: Long,
        requestId: String,
    ): Boolean {
        require(generation >= 1L)
        require(requestId.isNotBlank())
        return dao.clearRemoteDispatch(
            summaryId = summaryId,
            generation = generation,
            requestId = requestId,
            nowMs = nowMs(),
            resolvableStates = REMOTE_RESOLVABLE_STATES,
        ) == 1
    }

    suspend fun markAmbiguousRemoteResult(
        summaryId: String,
        generation: Long,
        requestId: String,
    ): Boolean {
        require(generation >= 1L)
        require(requestId.isNotBlank())
        return dao.markAmbiguousRemoteResult(
            summaryId = summaryId,
            generation = generation,
            requestId = requestId,
            nowMs = nowMs(),
            sanitizedErrorMessage = AMBIGUOUS_REMOTE_MESSAGE,
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun markAmbiguousRemoteResult(
        summaryId: String,
        generation: Long,
    ): Boolean {
        val current = dao.findSummary(summaryId) ?: return false
        val requestId = current.remoteRequestId ?: return false
        return markAmbiguousRemoteResult(summaryId, generation, requestId)
    }

    suspend fun cancelForGeneration(
        summaryId: String,
        generation: Long,
    ): Boolean {
        require(generation >= 1L)
        return dao.cancelForGeneration(
            summaryId = summaryId,
            generation = generation,
            nowMs = nowMs(),
            sanitizedErrorMessage = "AI 总结生成已取消。",
            activeStates = AiSummaryStateValue.ACTIVE,
        ) == 1
    }

    suspend fun acknowledgeTerminal(summaryId: String): Boolean =
        dao.acknowledgeTerminal(
            summaryId = summaryId,
            nowMs = nowMs(),
            terminalStates = ATTENTION_TERMINAL_STATES,
        ) == 1

    suspend fun resumeInterrupted(summaryId: String): Boolean =
        dao.resumeInterrupted(summaryId, nowMs()) == 1

    suspend fun findChunk(
        summaryId: String,
        level: Int,
        chunkIndex: Int,
    ): AiSummaryChunkEntity? =
        dao.findChunk(summaryId, level, chunkIndex)

    suspend fun findChunkForGeneration(
        summaryId: String,
        generation: Long,
        level: Int,
        chunkIndex: Int,
    ): AiSummaryChunkEntity? =
        database.withTransaction {
            val summary = dao.findSummary(summaryId) ?: return@withTransaction null
            if (summary.executionGeneration != generation || summary.status !in AiSummaryStateValue.ACTIVE) {
                return@withTransaction null
            }
            dao.findChunk(summaryId, level, chunkIndex)
        }

    suspend fun loadChunks(summaryId: String): List<AiSummaryChunkEntity> =
        dao.loadChunks(summaryId)

    suspend fun upsertChunk(chunk: AiSummaryChunkEntity) {
        validateChunk(chunk)
        dao.upsertChunk(chunk)
    }

    suspend fun upsertChunkForGeneration(
        summaryId: String,
        generation: Long,
        chunk: AiSummaryChunkEntity,
    ): Boolean {
        require(generation >= 1L)
        require(chunk.aiSummaryId == summaryId)
        validateChunk(chunk)
        return database.withTransaction {
            val summary = dao.findSummary(summaryId) ?: return@withTransaction false
            if (summary.executionGeneration != generation || summary.status !in AiSummaryStateValue.ACTIVE) {
                return@withTransaction false
            }
            dao.upsertChunk(chunk)
            true
        }
    }

    suspend fun reconcileInterruptedOnStartup(): Int =
        dao.markActiveInterrupted(
            activeStates = AiSummaryStateValue.ACTIVE,
            nowMs = nowMs(),
        )

    private fun validateCompletion(
        contentType: String,
        classificationConfidence: Double?,
        structuredPayloadJson: String,
        displayText: String,
        evidence: List<AiSummaryEvidenceWrite>,
    ) {
        require(contentType.isNotBlank())
        require(classificationConfidence == null || classificationConfidence in 0.0..1.0)
        require(structuredPayloadJson.isNotBlank())
        require(displayText.isNotBlank())
        validateEvidence(evidence)
    }

    private suspend fun insertEvidence(
        summaryId: String,
        evidence: List<AiSummaryEvidenceWrite>,
    ) {
        if (evidence.isEmpty()) return
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

    private suspend fun onCompleted(summaryId: String) {
        try {
            val summary = dao.findSummary(summaryId)
            if (summary != null) {
                Stage12CContentRepository(database).onAiSummaryCompleted(
                    recordingId = summary.recordingId,
                    summaryId = summaryId,
                )
            }
        } catch (_: CancellationException) {
            // Completion is already committed atomically. Current-selection/search maintenance
            // is rebuildable and must not turn a completed summary back into a cancellation race.
        }
    }

    private fun validateChunk(chunk: AiSummaryChunkEntity) {
        require(chunk.aiSummaryId.isNotBlank())
        require(chunk.level >= 0)
        require(chunk.chunkIndex >= 0)
        require(chunk.sourceStartOrdinal >= 0)
        require(chunk.sourceEndOrdinalExclusive > chunk.sourceStartOrdinal)
        require(chunk.startSampleIndex >= 0)
        require(chunk.endSampleIndexExclusive > chunk.startSampleIndex)
        require(chunk.inputDigest.isNotBlank())
        require(chunk.attempt >= 1)
    }

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
                AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
            )
        val ATTENTION_TERMINAL_STATES =
            listOf(
                AiSummaryStateValue.COMPLETED,
                AiSummaryStateValue.FAILED,
                AiSummaryStateValue.AMBIGUOUS_REMOTE_RESULT,
            )
        val REMOTE_RESOLVABLE_STATES =
            AiSummaryStateValue.ACTIVE + AiSummaryStateValue.COMPLETED
        const val AMBIGUOUS_REMOTE_MESSAGE =
            "上一次请求状态无法确认，需要手动重试。"
        val ALL_STATES = AiSummaryStateValue.ACTIVE.toSet() + TERMINAL_STATES
    }
}
