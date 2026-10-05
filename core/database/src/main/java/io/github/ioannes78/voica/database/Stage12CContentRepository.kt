package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

data class TranscriptionRevisionParagraphDraft(
    val text: String,
    val sourceAnchorRefsJson: String,
    val anchorStartSampleIndex: Long?,
    val anchorEndSampleIndexExclusive: Long?,
    val speakerId: String? = null,
    val speakerDisplayMode: String = TranscriptRevisionSpeakerDisplayModeValue.INHERIT,
    val timingQuality: String,
    val isUserModified: Boolean,
)

data class EffectiveTranscriptParagraph(
    val stableId: String,
    val text: String,
    val sourceAnchorRefsJson: String,
    val anchorStartSampleIndex: Long?,
    val anchorEndSampleIndexExclusive: Long?,
    val speakerId: String?,
    val speakerDisplayMode: String,
    val timingQuality: String,
    val isUserModified: Boolean,
)

data class EffectiveTranscriptionRef(
    val transcriptionId: String,
    val revisionId: String?,
)

data class EffectiveAiSummaryRef(
    val summaryId: String,
    val revisionId: String?,
)

data class ContentCompletionDecision(
    val currentId: String?,
    val candidateId: String?,
    val adopted: Boolean,
)

sealed interface ContentVersionDeleteResult {
    data object NotFound : ContentVersionDeleteResult
    data object ActiveTask : ContentVersionDeleteResult
    data class ReferencedByAiSummaries(val count: Int) : ContentVersionDeleteResult
    data class Deleted(val fallbackVersionId: String?) : ContentVersionDeleteResult
}

class Stage12CContentRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.stage12cContentDao()
    private val transcriptionDao = database.transcriptionDao()
    private val aiSummaryDao = database.aiSummaryDao()
    private val searchIndexRebuilder = SearchIndexRebuilder(database)

    fun observeTranscriptionMetadata(transcriptionId: String): Flow<TranscriptionUserMetadataEntity?> =
        dao.observeTranscriptionMetadata(transcriptionId)

    fun observeTranscriptionRevisions(transcriptionId: String): Flow<List<TranscriptionRevisionEntity>> =
        dao.observeTranscriptionRevisions(transcriptionId)

    fun observeAiSummaryMetadata(summaryId: String): Flow<AiSummaryUserMetadataEntity?> =
        dao.observeAiSummaryMetadata(summaryId)

    fun observeAiSummaryRevisions(summaryId: String): Flow<List<AiSummaryRevisionEntity>> =
        dao.observeAiSummaryRevisions(summaryId)

    fun observeContentSelection(recordingId: String): Flow<RecordingContentSelectionEntity?> =
        dao.observeContentSelection(recordingId)

    suspend fun loadTranscriptionMetadata(transcriptionId: String): TranscriptionUserMetadataEntity? =
        dao.findTranscriptionMetadata(transcriptionId)

    suspend fun loadTranscriptionRevisions(transcriptionId: String): List<TranscriptionRevisionEntity> =
        dao.observeTranscriptionRevisions(transcriptionId).first()

    suspend fun loadTranscriptionRevisionParagraphs(revisionId: String): List<TranscriptionRevisionParagraphEntity> =
        dao.loadTranscriptionRevisionParagraphs(revisionId)

    suspend fun loadAiSummaryMetadata(summaryId: String): AiSummaryUserMetadataEntity? =
        dao.findAiSummaryMetadata(summaryId)

    suspend fun loadAiSummaryRevisions(summaryId: String): List<AiSummaryRevisionEntity> =
        dao.observeAiSummaryRevisions(summaryId).first()

    suspend fun loadAiSummaryRevision(revisionId: String): AiSummaryRevisionEntity? =
        dao.findAiSummaryRevision(revisionId)

    suspend fun renameTranscriptionVersion(transcriptionId: String, displayName: String?) {
        val transcription = transcriptionDao.findTranscription(transcriptionId) ?: error("transcription not found")
        val current = dao.findTranscriptionMetadata(transcriptionId)
        dao.upsertTranscriptionMetadata(
            TranscriptionUserMetadataEntity(
                transcriptionId = transcription.id,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(120),
                currentRevisionId = current?.currentRevisionId,
                updatedAtMs = nowMs(),
            ),
        )
        searchIndexRebuilder.reindexEffectiveRecording(transcription.recordingId)
    }

    suspend fun createTranscriptionRevision(
        transcriptionId: String,
        paragraphs: List<TranscriptionRevisionParagraphDraft>,
    ): String {
        val transcription = transcriptionDao.findTranscription(transcriptionId) ?: error("transcription not found")
        require(transcription.state == TranscriptionStateValue.COMPLETED) {
            "only completed transcription can be revised"
        }
        validateParagraphs(paragraphs, transcription.totalSampleCount)

        val revisionId = newId()
        val now = nowMs()
        database.withTransaction {
            val metadata = dao.findTranscriptionMetadata(transcriptionId)
            val revisionNumber = dao.maxTranscriptionRevisionNumber(transcriptionId) + 1
            dao.insertTranscriptionRevision(
                TranscriptionRevisionEntity(
                    id = revisionId,
                    transcriptionId = transcriptionId,
                    revisionNumber = revisionNumber,
                    parentRevisionId = metadata?.currentRevisionId,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            dao.insertTranscriptionRevisionParagraphs(
                paragraphs.mapIndexed { index, paragraph ->
                    TranscriptionRevisionParagraphEntity(
                        id = newId(),
                        revisionId = revisionId,
                        paragraphIndex = index,
                        text = paragraph.text,
                        sourceAnchorRefsJson = paragraph.sourceAnchorRefsJson,
                        anchorStartSampleIndex = paragraph.anchorStartSampleIndex,
                        anchorEndSampleIndexExclusive = paragraph.anchorEndSampleIndexExclusive,
                        speakerId = paragraph.speakerId,
                        speakerDisplayMode = paragraph.speakerDisplayMode,
                        timingQuality = paragraph.timingQuality,
                        isUserModified = paragraph.isUserModified,
                    )
                },
            )
            dao.upsertTranscriptionMetadata(
                TranscriptionUserMetadataEntity(
                    transcriptionId = transcriptionId,
                    displayName = metadata?.displayName,
                    currentRevisionId = revisionId,
                    updatedAtMs = now,
                ),
            )
        }
        searchIndexRebuilder.reindexEffectiveRecording(transcription.recordingId)
        return revisionId
    }

    suspend fun loadEffectiveTranscriptParagraphs(transcriptionId: String): List<EffectiveTranscriptParagraph> {
        val transcription = transcriptionDao.findTranscription(transcriptionId) ?: return emptyList()
        val currentRevisionId = validCurrentTranscriptionRevisionId(transcriptionId)
        if (currentRevisionId != null) {
            return dao.loadTranscriptionRevisionParagraphs(currentRevisionId).map { paragraph ->
                EffectiveTranscriptParagraph(
                    stableId = paragraph.id,
                    text = paragraph.text,
                    sourceAnchorRefsJson = paragraph.sourceAnchorRefsJson,
                    anchorStartSampleIndex = paragraph.anchorStartSampleIndex,
                    anchorEndSampleIndexExclusive = paragraph.anchorEndSampleIndexExclusive,
                    speakerId = paragraph.speakerId,
                    speakerDisplayMode = paragraph.speakerDisplayMode,
                    timingQuality = paragraph.timingQuality,
                    isUserModified = paragraph.isUserModified,
                )
            }
        }
        return transcriptionDao.loadSegments(transcription.id).map { segment ->
            EffectiveTranscriptParagraph(
                stableId = segment.id,
                text = segment.finalText,
                sourceAnchorRefsJson = "[\"SEGMENT:${segment.id}\"]",
                anchorStartSampleIndex = segment.startSampleIndex,
                anchorEndSampleIndexExclusive = segment.endSampleIndexExclusive,
                speakerId = null,
                speakerDisplayMode = TranscriptRevisionSpeakerDisplayModeValue.INHERIT,
                timingQuality = TranscriptRevisionTimingQualityValue.EXACT,
                isUserModified = false,
            )
        }
    }

    suspend fun setCurrentTranscriptionRevision(transcriptionId: String, revisionId: String?) {
        val transcription = transcriptionDao.findTranscription(transcriptionId) ?: error("transcription not found")
        val revision = revisionId?.let { dao.findTranscriptionRevision(it) }
        require(revisionId == null || revision?.transcriptionId == transcriptionId) {
            "revision does not belong to transcription"
        }
        val metadata = dao.findTranscriptionMetadata(transcriptionId)
        dao.upsertTranscriptionMetadata(
            TranscriptionUserMetadataEntity(
                transcriptionId = transcription.id,
                displayName = metadata?.displayName,
                currentRevisionId = revisionId,
                updatedAtMs = nowMs(),
            ),
        )
        searchIndexRebuilder.reindexEffectiveRecording(transcription.recordingId)
    }

    suspend fun deleteTranscriptionRevision(revisionId: String): Boolean {
        val revision = dao.findTranscriptionRevision(revisionId) ?: return false
        val transcription = transcriptionDao.findTranscription(revision.transcriptionId)
        val deleted = database.withTransaction {
            val metadata = dao.findTranscriptionMetadata(revision.transcriptionId)
            val wasCurrent = metadata?.currentRevisionId == revisionId
            val deleted = dao.deleteTranscriptionRevision(revisionId) == 1
            if (deleted && wasCurrent) {
                val fallback = dao.findLatestTranscriptionRevision(revision.transcriptionId)
                dao.upsertTranscriptionMetadata(
                    TranscriptionUserMetadataEntity(
                        transcriptionId = revision.transcriptionId,
                        displayName = metadata?.displayName,
                        currentRevisionId = fallback?.id,
                        updatedAtMs = nowMs(),
                    ),
                )
            }
            deleted
        }
        if (deleted && transcription != null) {
            searchIndexRebuilder.reindexEffectiveRecording(transcription.recordingId)
        }
        return deleted
    }

    suspend fun createAiSummaryRevision(
        summaryId: String,
        revisionPayloadJson: String,
        revisionSchemaVersion: Int = 1,
    ): String {
        require(revisionPayloadJson.isNotBlank())
        require(revisionSchemaVersion >= 1)
        val summary = aiSummaryDao.findSummary(summaryId) ?: error("AI summary not found")
        require(summary.status == AiSummaryStateValue.COMPLETED) {
            "only completed AI summary can be revised"
        }

        val revisionId = newId()
        val now = nowMs()
        database.withTransaction {
            val metadata = dao.findAiSummaryMetadata(summaryId)
            val revisionNumber = dao.maxAiSummaryRevisionNumber(summaryId) + 1
            dao.insertAiSummaryRevision(
                AiSummaryRevisionEntity(
                    id = revisionId,
                    aiSummaryId = summaryId,
                    revisionNumber = revisionNumber,
                    parentRevisionId = metadata?.currentRevisionId,
                    revisionSchemaVersion = revisionSchemaVersion,
                    revisionPayloadJson = revisionPayloadJson,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            )
            dao.upsertAiSummaryMetadata(
                AiSummaryUserMetadataEntity(
                    aiSummaryId = summaryId,
                    currentRevisionId = revisionId,
                    updatedAtMs = now,
                ),
            )
        }
        searchIndexRebuilder.reindexEffectiveRecording(summary.recordingId)
        return revisionId
    }

    suspend fun setCurrentAiSummaryRevision(summaryId: String, revisionId: String?) {
        val summary = aiSummaryDao.findSummary(summaryId) ?: error("AI summary not found")
        val revision = revisionId?.let { dao.findAiSummaryRevision(it) }
        require(revisionId == null || revision?.aiSummaryId == summaryId) {
            "revision does not belong to AI summary"
        }
        dao.upsertAiSummaryMetadata(
            AiSummaryUserMetadataEntity(
                aiSummaryId = summaryId,
                currentRevisionId = revisionId,
                updatedAtMs = nowMs(),
            ),
        )
        searchIndexRebuilder.reindexEffectiveRecording(summary.recordingId)
    }

    suspend fun deleteAiSummaryRevision(revisionId: String): Boolean {
        val revision = dao.findAiSummaryRevision(revisionId) ?: return false
        val summary = aiSummaryDao.findSummary(revision.aiSummaryId)
        val deleted = database.withTransaction {
            val metadata = dao.findAiSummaryMetadata(revision.aiSummaryId)
            val wasCurrent = metadata?.currentRevisionId == revisionId
            val deleted = dao.deleteAiSummaryRevision(revisionId) == 1
            if (deleted && wasCurrent) {
                val fallback = dao.findLatestAiSummaryRevision(revision.aiSummaryId)
                dao.upsertAiSummaryMetadata(
                    AiSummaryUserMetadataEntity(
                        aiSummaryId = revision.aiSummaryId,
                        currentRevisionId = fallback?.id,
                        updatedAtMs = nowMs(),
                    ),
                )
            }
            deleted
        }
        if (deleted && summary != null) {
            searchIndexRebuilder.reindexEffectiveRecording(summary.recordingId)
        }
        return deleted
    }

    suspend fun resolveCurrentTranscriptionId(recordingId: String): String? {
        val selectedId = dao.findContentSelection(recordingId)?.currentTranscriptionId
        val selected = selectedId?.let { transcriptionDao.findTranscription(it) }
        if (selected?.recordingId == recordingId && selected.state == TranscriptionStateValue.COMPLETED) {
            return selected.id
        }
        return transcriptionDao.findLatestCompleted(recordingId)?.id
    }

    suspend fun resolveEffectiveTranscription(recordingId: String): EffectiveTranscriptionRef? {
        val transcriptionId = resolveCurrentTranscriptionId(recordingId) ?: return null
        return EffectiveTranscriptionRef(
            transcriptionId = transcriptionId,
            revisionId = validCurrentTranscriptionRevisionId(transcriptionId),
        )
    }

    suspend fun resolveCurrentAiSummaryId(recordingId: String): String? {
        val selectedId = dao.findContentSelection(recordingId)?.currentAiSummaryId
        val selected = selectedId?.let { aiSummaryDao.findSummary(it) }
        if (selected?.recordingId == recordingId && selected.status == AiSummaryStateValue.COMPLETED) {
            return selected.id
        }
        return aiSummaryDao.findLatestCompleted(recordingId)?.id
    }

    suspend fun resolveEffectiveAiSummary(recordingId: String): EffectiveAiSummaryRef? {
        val summaryId = resolveCurrentAiSummaryId(recordingId) ?: return null
        return EffectiveAiSummaryRef(
            summaryId = summaryId,
            revisionId = validCurrentAiSummaryRevisionId(summaryId),
        )
    }

    suspend fun resolveTranscriptionCandidateId(recordingId: String): String? {
        val currentId = resolveCurrentTranscriptionId(recordingId) ?: return null
        val current = transcriptionDao.findTranscription(currentId) ?: return null
        val candidate = transcriptionDao.findLatestCompletedExcluding(recordingId, currentId) ?: return null
        return candidate.id.takeIf {
            isNewer(
                candidate.completedAtMs,
                candidate.createdAtMs,
                current.completedAtMs,
                current.createdAtMs,
            )
        }
    }

    suspend fun resolveAiSummaryCandidateId(recordingId: String): String? {
        val currentId = resolveCurrentAiSummaryId(recordingId) ?: return null
        val current = aiSummaryDao.findSummary(currentId) ?: return null
        val candidate = aiSummaryDao.findLatestCompletedExcluding(recordingId, currentId) ?: return null
        return candidate.id.takeIf {
            isNewer(
                candidate.completedAtMs,
                candidate.createdAtMs,
                current.completedAtMs,
                current.createdAtMs,
            )
        }
    }

    suspend fun onTranscriptionCompleted(
        recordingId: String,
        transcriptionId: String,
    ): ContentCompletionDecision {
        val completed = transcriptionDao.findTranscription(transcriptionId) ?: error("transcription not found")
        require(completed.recordingId == recordingId && completed.state == TranscriptionStateValue.COMPLETED)

        val decision = database.withTransaction {
            val selection = dao.findContentSelection(recordingId)
            val persisted = selection?.currentTranscriptionId?.let { transcriptionDao.findTranscription(it) }
            val validPersisted =
                persisted?.takeIf {
                    it.recordingId == recordingId && it.state == TranscriptionStateValue.COMPLETED
                }
            val currentId =
                when {
                    validPersisted != null -> validPersisted.id
                    else ->
                        transcriptionDao.findLatestCompletedExcluding(recordingId, transcriptionId)?.id
                            ?: transcriptionId
                }
            val row =
                (selection ?: RecordingContentSelectionEntity(
                    recordingId = recordingId,
                    currentTranscriptionId = null,
                    currentAiSummaryId = null,
                    updatedAtMs = nowMs(),
                )).copy(
                    currentTranscriptionId = currentId,
                    updatedAtMs = nowMs(),
                )
            dao.upsertContentSelection(row)
            ContentCompletionDecision(
                currentId = currentId,
                candidateId = transcriptionId.takeIf { it != currentId },
                adopted = currentId == transcriptionId,
            )
        }
        searchIndexRebuilder.reindexEffectiveRecording(recordingId)
        return decision
    }

    suspend fun onAiSummaryCompleted(
        recordingId: String,
        summaryId: String,
    ): ContentCompletionDecision {
        val completed = aiSummaryDao.findSummary(summaryId) ?: error("AI summary not found")
        require(completed.recordingId == recordingId && completed.status == AiSummaryStateValue.COMPLETED)

        val decision = database.withTransaction {
            val selection = dao.findContentSelection(recordingId)
            val persisted = selection?.currentAiSummaryId?.let { aiSummaryDao.findSummary(it) }
            val validPersisted =
                persisted?.takeIf {
                    it.recordingId == recordingId && it.status == AiSummaryStateValue.COMPLETED
                }
            val currentId =
                when {
                    validPersisted != null -> validPersisted.id
                    else ->
                        aiSummaryDao.findLatestCompletedExcluding(recordingId, summaryId)?.id
                            ?: summaryId
                }
            val row =
                (selection ?: RecordingContentSelectionEntity(
                    recordingId = recordingId,
                    currentTranscriptionId = null,
                    currentAiSummaryId = null,
                    updatedAtMs = nowMs(),
                )).copy(
                    currentAiSummaryId = currentId,
                    updatedAtMs = nowMs(),
                )
            dao.upsertContentSelection(row)
            ContentCompletionDecision(
                currentId = currentId,
                candidateId = summaryId.takeIf { it != currentId },
                adopted = currentId == summaryId,
            )
        }
        searchIndexRebuilder.reindexEffectiveRecording(recordingId)
        return decision
    }

    suspend fun setCurrentTranscriptionVersion(recordingId: String, transcriptionId: String?) {
        val selected = transcriptionId?.let { transcriptionDao.findTranscription(it) }
        require(
            transcriptionId == null ||
                (selected?.recordingId == recordingId && selected.state == TranscriptionStateValue.COMPLETED),
        )
        val current = dao.findContentSelection(recordingId)
        dao.upsertContentSelection(
            RecordingContentSelectionEntity(
                recordingId = recordingId,
                currentTranscriptionId = transcriptionId,
                currentAiSummaryId = current?.currentAiSummaryId,
                updatedAtMs = nowMs(),
            ),
        )
        searchIndexRebuilder.reindexEffectiveRecording(recordingId)
    }

    suspend fun setCurrentAiSummaryVersion(recordingId: String, summaryId: String?) {
        val selected = summaryId?.let { aiSummaryDao.findSummary(it) }
        require(
            summaryId == null ||
                (selected?.recordingId == recordingId && selected.status == AiSummaryStateValue.COMPLETED),
        )
        val current = dao.findContentSelection(recordingId)
        dao.upsertContentSelection(
            RecordingContentSelectionEntity(
                recordingId = recordingId,
                currentTranscriptionId = current?.currentTranscriptionId,
                currentAiSummaryId = summaryId,
                updatedAtMs = nowMs(),
            ),
        )
        searchIndexRebuilder.reindexEffectiveRecording(recordingId)
    }

    suspend fun deleteTranscriptionVersion(transcriptionId: String): ContentVersionDeleteResult {
        val transcription = transcriptionDao.findTranscription(transcriptionId)
            ?: return ContentVersionDeleteResult.NotFound
        if (transcription.state in TranscriptionStateValue.ACTIVE) {
            return ContentVersionDeleteResult.ActiveTask
        }
        val outcome = database.withTransaction {
            val dependentSummaries = aiSummaryDao.countForTranscription(transcriptionId)
            if (dependentSummaries > 0) {
                return@withTransaction ContentVersionDeleteResult.ReferencedByAiSummaries(
                    dependentSummaries,
                )
            }
            check(transcriptionDao.deleteVersion(transcriptionId) == 1)
            val fallback = transcriptionDao.findLatestCompleted(transcription.recordingId)?.id
            val selection = dao.findContentSelection(transcription.recordingId)
            if (selection?.currentTranscriptionId == transcriptionId) {
                dao.upsertContentSelection(
                    selection.copy(
                        currentTranscriptionId = fallback,
                        updatedAtMs = nowMs(),
                    ),
                )
            }
            ContentVersionDeleteResult.Deleted(fallback)
        }
        if (outcome is ContentVersionDeleteResult.Deleted) {
            searchIndexRebuilder.reindexEffectiveRecording(transcription.recordingId)
        }
        return outcome
    }

    suspend fun deleteAiSummaryVersion(summaryId: String): ContentVersionDeleteResult {
        val summary = aiSummaryDao.findSummary(summaryId)
            ?: return ContentVersionDeleteResult.NotFound
        if (summary.status in AiSummaryStateValue.ACTIVE) {
            return ContentVersionDeleteResult.ActiveTask
        }
        val outcome = database.withTransaction {
            check(aiSummaryDao.deleteVersion(summaryId) == 1)
            val fallback = aiSummaryDao.findLatestCompleted(summary.recordingId)?.id
            val selection = dao.findContentSelection(summary.recordingId)
            if (selection?.currentAiSummaryId == summaryId) {
                dao.upsertContentSelection(
                    selection.copy(
                        currentAiSummaryId = fallback,
                        updatedAtMs = nowMs(),
                    ),
                )
            }
            ContentVersionDeleteResult.Deleted(fallback)
        }
        if (outcome is ContentVersionDeleteResult.Deleted) {
            searchIndexRebuilder.reindexEffectiveRecording(summary.recordingId)
        }
        return outcome
    }

    private suspend fun validCurrentTranscriptionRevisionId(transcriptionId: String): String? {
        val revisionId = dao.findTranscriptionMetadata(transcriptionId)?.currentRevisionId ?: return null
        return dao.findTranscriptionRevision(revisionId)
            ?.takeIf { it.transcriptionId == transcriptionId }
            ?.id
    }

    private suspend fun validCurrentAiSummaryRevisionId(summaryId: String): String? {
        val revisionId = dao.findAiSummaryMetadata(summaryId)?.currentRevisionId ?: return null
        return dao.findAiSummaryRevision(revisionId)
            ?.takeIf { it.aiSummaryId == summaryId }
            ?.id
    }

    private fun isNewer(
        candidateCompletedAtMs: Long?,
        candidateCreatedAtMs: Long,
        currentCompletedAtMs: Long?,
        currentCreatedAtMs: Long,
    ): Boolean {
        val candidateTime = candidateCompletedAtMs ?: candidateCreatedAtMs
        val currentTime = currentCompletedAtMs ?: currentCreatedAtMs
        return candidateTime > currentTime ||
            (candidateTime == currentTime && candidateCreatedAtMs > currentCreatedAtMs)
    }

    private fun validateParagraphs(
        paragraphs: List<TranscriptionRevisionParagraphDraft>,
        totalSampleCount: Long,
    ) {
        require(paragraphs.isNotEmpty())
        paragraphs.forEach { paragraph ->
            require(paragraph.text.isNotBlank())
            require(paragraph.sourceAnchorRefsJson.isNotBlank())
            require(paragraph.speakerDisplayMode in TranscriptRevisionSpeakerDisplayModeValue.ALL)
            require(paragraph.timingQuality in TranscriptRevisionTimingQualityValue.ALL)
            val start = paragraph.anchorStartSampleIndex
            val end = paragraph.anchorEndSampleIndexExclusive
            require((start == null) == (end == null))
            if (start != null && end != null) {
                require(start >= 0L)
                require(end > start)
                require(end <= totalSampleCount)
            }
            if (paragraph.timingQuality == TranscriptRevisionTimingQualityValue.EXACT) {
                require(start != null && end != null)
            }
        }
    }

    private fun newId(): String = idFactory().also { require(it.isNotBlank()) }
}