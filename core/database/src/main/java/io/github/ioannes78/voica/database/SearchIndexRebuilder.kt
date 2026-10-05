package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.AiSummaryRevisionCodec
import io.github.ioannes78.voica.ai.SummaryResultCodec

class SearchIndexRebuilder(
    private val database: VoicaDatabase,
    private val searchRepository: UnifiedSearchRepository = UnifiedSearchRepository(database),
) {
    private val recordingDao = database.recordingDao()
    private val transcriptionDao = database.transcriptionDao()
    private val aiSummaryDao = database.aiSummaryDao()
    private val contentDao = database.stage12cContentDao()
    private val searchDao = database.searchDao()

    suspend fun rebuildIfRequired() {
        val state = searchRepository.state()
        if (state?.status != SearchIndexStatusValue.READY || !isEffectiveOnlyIndex()) {
            rebuildAll()
        }
    }

    suspend fun rebuildAll() {
        searchRepository.clearForRebuild()
        var count = 0L
        try {
            recordingDao.allRecordings()
                .filter { it.state == RecordingState.ACTIVE }
                .forEach { recording ->
                    indexRecording(recording)
                    count += 1L
                    resolveCurrentTranscription(recording.id)?.let { transcription ->
                        count += reindexTranscriptionInternal(transcription)
                    }
                    resolveCurrentAiSummary(recording.id)?.let { summary ->
                        count += reindexAiSummaryInternal(summary)
                    }
                }

            searchRepository.markReady(count)
        } catch (error: Throwable) {
            searchRepository.markFailed(error.message)
            throw error
        }
    }

    suspend fun reindexRecording(recordingId: String) {
        reindexEffectiveRecording(recordingId)
    }

    suspend fun reindexEffectiveRecording(recordingId: String) {
        searchRepository.deleteForRecording(recordingId)
        val recording =
            recordingDao.allRecordings().firstOrNull {
                it.id == recordingId && it.state == RecordingState.ACTIVE
            } ?: return
        indexRecording(recording)
        resolveCurrentTranscription(recordingId)?.let { reindexTranscriptionInternal(it) }
        resolveCurrentAiSummary(recordingId)?.let { reindexAiSummaryInternal(it) }
    }

    suspend fun reindexFolder(folderId: String) {
        // QA6 ordinary search intentionally contains only Recording + current/effective
        // Transcription + current/effective Summary. Remove any legacy folder row.
        searchRepository.deleteForFolder(folderId)
    }

    suspend fun reindexTag(tagId: String) {
        // QA6 ordinary search intentionally contains only Recording + current/effective
        // Transcription + current/effective Summary. Remove any legacy tag row.
        searchRepository.deleteForTag(tagId)
    }

    suspend fun reindexTranscription(transcriptionId: String) {
        searchRepository.deleteForTranscription(transcriptionId)
        val transcription = transcriptionDao.findTranscription(transcriptionId) ?: return
        if (transcription.state != TranscriptionStateValue.COMPLETED) return
        if (resolveCurrentTranscription(transcription.recordingId)?.id == transcription.id) {
            reindexTranscriptionInternal(transcription)
        }
    }

    suspend fun reindexAiSummary(summaryId: String) {
        searchRepository.deleteForAiSummary(summaryId)
        val summary = aiSummaryDao.findSummary(summaryId) ?: return
        if (summary.status != AiSummaryStateValue.COMPLETED) return
        if (resolveCurrentAiSummary(summary.recordingId)?.id == summary.id) {
            reindexAiSummaryInternal(summary)
        }
    }

    suspend fun removeRecording(recordingId: String) {
        searchRepository.deleteForRecording(recordingId)
    }

    suspend fun removeTranscription(transcriptionId: String) {
        val transcription = transcriptionDao.findTranscription(transcriptionId)
        searchRepository.deleteForTranscription(transcriptionId)
        transcription?.recordingId?.let { reindexEffectiveRecording(it) }
    }

    suspend fun removeAiSummary(summaryId: String) {
        val summary = aiSummaryDao.findSummary(summaryId)
        searchRepository.deleteForAiSummary(summaryId)
        summary?.recordingId?.let { reindexEffectiveRecording(it) }
    }

    private suspend fun isEffectiveOnlyIndex(): Boolean {
        val activeRecordings = recordingDao.allRecordings().filter { it.state == RecordingState.ACTIVE }
        val expectedTranscriptions =
            activeRecordings.mapNotNull { resolveCurrentTranscription(it.id)?.id }.toSet()
        val expectedSummaries =
            activeRecordings.mapNotNull { resolveCurrentAiSummary(it.id)?.id }.toSet()
        val indexedTranscriptions = searchDao.findIndexedTranscriptionIds().toSet()
        val indexedSummaries = searchDao.findIndexedAiSummaryIds().toSet()
        return indexedTranscriptions == expectedTranscriptions &&
            indexedSummaries == expectedSummaries
    }

    private suspend fun resolveCurrentTranscription(recordingId: String): TranscriptionEntity? {
        val selectedId = contentDao.findContentSelection(recordingId)?.currentTranscriptionId
        val selected = selectedId?.let { transcriptionDao.findTranscription(it) }
        if (selected?.recordingId == recordingId && selected.state == TranscriptionStateValue.COMPLETED) {
            return selected
        }
        return transcriptionDao.findLatestCompleted(recordingId)
    }

    private suspend fun resolveCurrentAiSummary(recordingId: String): AiSummaryEntity? {
        val selectedId = contentDao.findContentSelection(recordingId)?.currentAiSummaryId
        val selected = selectedId?.let { aiSummaryDao.findSummary(it) }
        if (selected?.recordingId == recordingId && selected.status == AiSummaryStateValue.COMPLETED) {
            return selected
        }
        return aiSummaryDao.findLatestCompleted(recordingId)
    }

    private suspend fun indexRecording(recording: RecordingEntity) {
        searchRepository.upsert(
            SearchDocumentDraft(
                documentId = "recording:" + recording.id,
                documentType = SearchDocumentTypeValue.RECORDING,
                recordingId = recording.id,
                displayTitle = recording.displayName,
                displayText =
                    listOfNotNull(
                        recording.originalFilename.takeIf { it != recording.displayName },
                        recording.recordedAtLocalIso,
                    ).joinToString(" · "),
                updatedAtMs = recording.updatedAtMs,
            ),
        )
    }

    private suspend fun reindexTranscriptionInternal(
        transcription: TranscriptionEntity,
    ): Long {
        val metadata = contentDao.findTranscriptionMetadata(transcription.id)
        val currentRevisionId = metadata?.currentRevisionId
        val units =
            if (currentRevisionId == null) {
                transcriptionDao.loadSegments(transcription.id).map { segment ->
                    TranscriptSearchUnit(
                        stableId = segment.id,
                        text = segment.finalText,
                        startSampleIndex = segment.startSampleIndex,
                    )
                }
            } else {
                val revision =
                    contentDao.findTranscriptionRevision(currentRevisionId)
                        ?.takeIf { it.transcriptionId == transcription.id }
                if (revision == null) {
                    transcriptionDao.loadSegments(transcription.id).map { segment ->
                        TranscriptSearchUnit(
                            stableId = segment.id,
                            text = segment.finalText,
                            startSampleIndex = segment.startSampleIndex,
                        )
                    }
                } else {
                    contentDao.loadTranscriptionRevisionParagraphs(revision.id).map { paragraph ->
                        TranscriptSearchUnit(
                            stableId = paragraph.id,
                            text = paragraph.text,
                            startSampleIndex = paragraph.anchorStartSampleIndex,
                        )
                    }
                }
            }

        val versionTitle =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: "转写"

        units.forEachIndexed { index, unit ->
            searchRepository.upsert(
                SearchDocumentDraft(
                    documentId =
                        "transcription:" +
                            transcription.id +
                            ":" +
                            unit.stableId,
                    documentType = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                    recordingId = transcription.recordingId,
                    transcriptionId = transcription.id,
                    revisionId = currentRevisionId,
                    sourceAnchorId = unit.stableId,
                    displayTitle = versionTitle,
                    displayText = unit.text,
                    updatedAtMs =
                        (transcription.completedAtMs ?: transcription.updatedAtMs) +
                            index.coerceAtMost(999),
                ),
            )
        }
        return units.size.toLong()
    }

    private suspend fun reindexAiSummaryInternal(summary: AiSummaryEntity): Long {
        val evidenceRefs =
            aiSummaryDao.loadEvidence(summary.id)
                .mapTo(linkedSetOf()) { it.sourceRef }

        val metadata = contentDao.findAiSummaryMetadata(summary.id)
        val currentRevisionId = metadata?.currentRevisionId
        val revisionDocument =
            currentRevisionId
                ?.let { contentDao.findAiSummaryRevision(it) }
                ?.takeIf { it.aiSummaryId == summary.id }
                ?.let { runCatching { AiSummaryRevisionCodec.decode(it.revisionPayloadJson) }.getOrNull() }

        val effective =
            revisionDocument ?: summary.structuredPayloadJson
                ?.let { raw ->
                    runCatching {
                        AiSummaryRevisionCodec.fromOriginal(
                            SummaryResultCodec.decode(
                                raw = raw,
                                allowedEvidenceRefs = evidenceRefs,
                            ),
                        )
                    }.getOrNull()
                }

        if (effective == null) {
            val fallback = summary.displayText?.trim().orEmpty()
            if (fallback.isNotEmpty()) {
                searchRepository.upsert(
                    SearchDocumentDraft(
                        documentId = "summary:" + summary.id + ":fallback",
                        documentType = SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                        recordingId = summary.recordingId,
                        transcriptionId = summary.transcriptionId,
                        aiSummaryId = summary.id,
                        revisionId = currentRevisionId,
                        displayTitle = "总结",
                        displayText = fallback,
                        updatedAtMs = summary.completedAtMs ?: summary.updatedAtMs,
                    ),
                )
                return 1L
            }
            return 0L
        }

        var count = 0L
        searchRepository.upsert(
            SearchDocumentDraft(
                documentId = "summary:" + summary.id + ":overview",
                documentType = SearchDocumentTypeValue.SUMMARY_TITLE_OVERVIEW,
                recordingId = summary.recordingId,
                transcriptionId = summary.transcriptionId,
                aiSummaryId = summary.id,
                revisionId = currentRevisionId,
                displayTitle = effective.title.ifBlank { "总结" },
                displayText = effective.overview,
                updatedAtMs = summary.completedAtMs ?: summary.updatedAtMs,
            ),
        )
        count += 1L

        effective.sections.forEach { section ->
            section.items.forEachIndexed { index, item ->
                searchRepository.upsert(
                    SearchDocumentDraft(
                        documentId =
                            "summary:" + summary.id + ":item:" + item.id,
                        documentType = SearchDocumentTypeValue.SUMMARY_ITEM,
                        recordingId = summary.recordingId,
                        transcriptionId = summary.transcriptionId,
                        aiSummaryId = summary.id,
                        revisionId = currentRevisionId,
                        sectionId = section.id,
                        itemId = item.id,
                        displayTitle = section.label,
                        displayText = item.text,
                        updatedAtMs =
                            (summary.completedAtMs ?: summary.updatedAtMs) +
                                index.coerceAtMost(999),
                    ),
                )
                count += 1L
            }
        }
        return count
    }

    private data class TranscriptSearchUnit(
        val stableId: String,
        val text: String,
        val startSampleIndex: Long?,
    )
}