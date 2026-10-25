package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.OfflineSummaryDisplayFormatter
import io.github.ioannes78.voica.ai.OfflineSummaryFailureCode
import io.github.ioannes78.voica.ai.OfflineSummaryPromptContract
import io.github.ioannes78.voica.ai.OfflineSummarySourceLineageCodec
import io.github.ioannes78.voica.ai.StructuredTranscriptInput
import io.github.ioannes78.voica.ai.SummaryResultCodec
import io.github.ioannes78.voica.ai.TranscriptEvidenceSourceKind
import java.util.UUID

data class OfflineSummaryEvidencePersistenceDraft(
    val summaryItemId: String,
    val sourceRef: String,
    val sourceKind: TranscriptEvidenceSourceKind,
    val sourceId: String,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val speakerId: String?,
    val assignmentQuality: String?,
) {
    init {
        require(summaryItemId.isNotBlank())
        require(sourceRef.matches(Regex("""S\d{5,}""")))
        require(sourceId.isNotBlank())
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }
}

/**
 * Write-oriented persistence boundary for Stage 17.2 local summaries.
 *
 * This class intentionally reuses the existing Room v16 entities. No schema extension is required.
 */
class OfflineSummaryPersistenceRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.aiSummaryDao()

    suspend fun create(
        input: StructuredTranscriptInput,
        modelIdentitySnapshot: String,
    ): AiSummaryEntity {
        require(modelIdentitySnapshot.isNotBlank())
        val now = nowMs()
        val entity =
            AiSummaryEntity(
                id = idFactory(),
                recordingId = input.recordingId,
                transcriptionId = input.transcriptionId,
                inputMode = AiSummaryInputModeValue.TRANSCRIPT_TEXT,
                mode = AiSummaryModeValue.SMART,
                model = modelIdentitySnapshot,
                promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
                resultSchemaVersion = OfflineSummaryPromptContract.RESULT_SCHEMA_VERSION,
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
                alignmentIdSnapshot = input.alignmentId,
                sourceLineageSnapshot = OfflineSummarySourceLineageCodec.encode(input),
            )
        dao.insertSummary(entity)
        return entity
    }

    suspend fun markPreparing(summaryId: String): AiSummaryEntity =
        transition(
            summaryId = summaryId,
            expectedStates = setOf(AiSummaryStateValue.CREATED),
            targetState = AiSummaryStateValue.PREPARING,
            setStartedAt = true,
        )

    suspend fun markAnalyzing(summaryId: String): AiSummaryEntity =
        transition(
            summaryId = summaryId,
            expectedStates = setOf(AiSummaryStateValue.PREPARING),
            targetState = AiSummaryStateValue.ANALYZING,
        )

    suspend fun markValidating(summaryId: String): AiSummaryEntity =
        transition(
            summaryId = summaryId,
            expectedStates = setOf(AiSummaryStateValue.ANALYZING),
            targetState = AiSummaryStateValue.VALIDATING,
        )

    suspend fun complete(
        summaryId: String,
        result: AiSummaryResult,
        evidence: List<OfflineSummaryEvidencePersistenceDraft>,
    ): AiSummaryEntity =
        database.withTransaction {
            val current = requireStage172Summary(summaryId)
            require(current.status == AiSummaryStateValue.VALIDATING) {
                "summary must be VALIDATING before completion"
            }
            require(result.schemaVersion == current.resultSchemaVersion) {
                "summary result schema does not match generation snapshot"
            }

            val expectedPairs =
                result.sections
                    .asSequence()
                    .flatMap { it.items.asSequence() }
                    .flatMap { item -> item.evidenceRefs.asSequence().map { ref -> item.id to ref } }
                    .toCollection(LinkedHashSet())
            val persistedPairs = evidence.map { it.summaryItemId to it.sourceRef }
            require(persistedPairs.size == persistedPairs.toSet().size) {
                "duplicate persisted summary evidence"
            }
            require(persistedPairs.toSet() == expectedPairs) {
                "persisted evidence must exactly cover validated result evidence"
            }

            val allowedEvidenceRefs = evidence.mapTo(LinkedHashSet()) { it.sourceRef }
            val encoded = SummaryResultCodec.encode(result)
            val validated = SummaryResultCodec.decode(encoded, allowedEvidenceRefs)
            val displayText = OfflineSummaryDisplayFormatter.format(validated)
            require(displayText.isNotBlank()) { "completed summary display text must not be blank" }

            val now = nowMs()
            if (evidence.isNotEmpty()) {
                dao.insertEvidence(
                    evidence.map { draft ->
                        AiSummaryEvidenceEntity(
                            id = idFactory(),
                            aiSummaryId = summaryId,
                            summaryItemId = draft.summaryItemId,
                            sourceRef = draft.sourceRef,
                            sourceKind = draft.sourceKind.name,
                            sourceId = draft.sourceId,
                            startSampleIndex = draft.startSampleIndex,
                            endSampleIndexExclusive = draft.endSampleIndexExclusive,
                            speakerId = draft.speakerId,
                            assignmentQuality = draft.assignmentQuality,
                        )
                    },
                )
            }
            val completed =
                current.copy(
                    contentType = validated.contentType.name,
                    classificationConfidence = validated.classificationConfidence,
                    structuredPayloadJson = encoded,
                    displayText = displayText,
                    status = AiSummaryStateValue.COMPLETED,
                    updatedAtMs = now,
                    completedAtMs = now,
                    errorCode = null,
                    sanitizedErrorMessage = null,
                )
            require(dao.updateSummary(completed) == 1) { "failed to persist completed summary" }
            completed
        }

    suspend fun fail(
        summaryId: String,
        code: OfflineSummaryFailureCode,
        sanitizedMessage: String?,
    ): AiSummaryEntity =
        finishActive(
            summaryId = summaryId,
            targetState = AiSummaryStateValue.FAILED,
            errorCode = code.name,
            sanitizedMessage = sanitizedMessage,
        )

    suspend fun cancel(summaryId: String): AiSummaryEntity =
        finishActive(
            summaryId = summaryId,
            targetState = AiSummaryStateValue.CANCELLED,
            errorCode = OfflineSummaryFailureCode.GENERATION_CANCELLED.name,
            sanitizedMessage = null,
        )

    suspend fun interrupt(summaryId: String): AiSummaryEntity =
        finishActive(
            summaryId = summaryId,
            targetState = AiSummaryStateValue.INTERRUPTED,
            errorCode = OfflineSummaryFailureCode.INTERRUPTED.name,
            sanitizedMessage = "应用进程中断，离线总结未完成",
        )

    suspend fun loadActive(): List<AiSummaryEntity> =
        dao.loadByPromptVersionAndStatuses(
            promptVersion = OfflineSummaryPromptContract.PROMPT_VERSION,
            statuses = STAGE17_2_ACTIVE_STATES.toList(),
        )

    suspend fun interruptAllActive(): List<AiSummaryEntity> {
        val active = loadActive()
        return active.map { interrupt(it.id) }
    }

    suspend fun find(summaryId: String): AiSummaryEntity? = dao.findSummary(summaryId)

    private suspend fun transition(
        summaryId: String,
        expectedStates: Set<String>,
        targetState: String,
        setStartedAt: Boolean = false,
    ): AiSummaryEntity =
        database.withTransaction {
            val current = requireStage172Summary(summaryId)
            require(current.status in expectedStates) {
                "invalid offline summary transition ${current.status} -> $targetState"
            }
            require(targetState in STAGE17_2_ACTIVE_STATES) {
                "target state is not active Stage 17.2 state"
            }
            val now = nowMs()
            val updated =
                current.copy(
                    status = targetState,
                    startedAtMs =
                        if (setStartedAt) current.startedAtMs ?: now else current.startedAtMs,
                    updatedAtMs = now,
                    errorCode = null,
                    sanitizedErrorMessage = null,
                )
            require(dao.updateSummary(updated) == 1) { "failed to update offline summary state" }
            updated
        }

    private suspend fun finishActive(
        summaryId: String,
        targetState: String,
        errorCode: String,
        sanitizedMessage: String?,
    ): AiSummaryEntity =
        database.withTransaction {
            val current = requireStage172Summary(summaryId)
            require(current.status in STAGE17_2_ACTIVE_STATES) {
                "only an active Stage 17.2 summary can enter $targetState"
            }
            val now = nowMs()
            val updated =
                current.copy(
                    status = targetState,
                    updatedAtMs = now,
                    completedAtMs = null,
                    errorCode = errorCode,
                    sanitizedErrorMessage = sanitizeMessage(sanitizedMessage),
                )
            require(dao.updateSummary(updated) == 1) { "failed to persist terminal summary state" }
            updated
        }

    private suspend fun requireStage172Summary(summaryId: String): AiSummaryEntity {
        val entity = dao.findSummary(summaryId) ?: error("AI summary not found")
        require(entity.promptVersion == OfflineSummaryPromptContract.PROMPT_VERSION) {
            "summary does not belong to Stage 17.2 offline prompt contract"
        }
        return entity
    }

    private fun sanitizeMessage(message: String?): String? =
        message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_SANITIZED_ERROR_CHARS)

    private companion object {
        val STAGE17_2_ACTIVE_STATES =
            linkedSetOf(
                AiSummaryStateValue.CREATED,
                AiSummaryStateValue.PREPARING,
                AiSummaryStateValue.ANALYZING,
                AiSummaryStateValue.VALIDATING,
            )
        const val MAX_SANITIZED_ERROR_CHARS = 240
    }
}
