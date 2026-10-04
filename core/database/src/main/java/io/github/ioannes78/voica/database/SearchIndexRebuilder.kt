package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.AiSummaryRevisionCodec
import io.github.ioannes78.voica.ai.SummaryResultCodec
import kotlinx.coroutines.flow.first

class SearchIndexRebuilder(
    private val database: VoicaDatabase,
    private val searchRepository: UnifiedSearchRepository = UnifiedSearchRepository(database),
) {
    private val recordingDao = database.recordingDao()
    private val transcriptionDao = database.transcriptionDao()
    private val aiSummaryDao = database.aiSummaryDao()
    private val contentDao = database.stage12cContentDao()

    suspend fun rebuildIfRequired() {
        val state = searchRepository.state()
        if (state?.status != SearchIndexStatusValue.READY) {
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
                    reindexRecording(recording)
                    count += 1L
                }

            recordingDao.observeFolders().first().forEach { folder ->
                reindexFolder(folder)
                count += 1L
            }

            recordingDao.observeTags().first().forEach { tag ->
                reindexTag(tag)
                count += 1L
            }

            transcriptionDao.loadAllCompleted().forEach { transcription ->
                count += reindexTranscriptionInternal(transcription)
            }

            aiSummaryDao.loadAllCompleted().forEach { summary ->
                count += reindexAiSummaryInternal(summary)
            }

            searchRepository.markReady(count)
        } catch (error: Throwable) {
            searchRepository.markFailed(error.message)
            throw error
        }
    }

    suspend fun reindexRecording(recordingId: String) {
        searchRepository.delete("recording:" + recordingId)
        val recording =
            recordingDao.allRecordings().firstOrNull {
                it.id == recordingId && it.state == RecordingState.ACTIVE
            } ?: return
        reindexRecording(recording)
    }

    suspend fun reindexFolder(folderId: String) {
        searchRepository.delete("folder:" + folderId)
        recordingDao.findFolder(folderId)?.let { reindexFolder(it) }
    }

    suspend fun reindexTag(tagId: String) {
        searchRepository.delete("tag:" + tagId)
        recordingDao.findTag(tagId)?.let { reindexTag(it) }
    }

    suspend fun reindexTranscription(transcriptionId: String) {
        searchRepository.deleteForTranscription(transcriptionId)
        val transcription = transcriptionDao.findTranscription(transcriptionId)
        if (transcription?.state == TranscriptionStateValue.COMPLETED) {
            reindexTranscriptionInternal(transcription)
        }
    }

    suspend fun reindexAiSummary(summaryId: String) {
        searchRepository.deleteForAiSummary(summaryId)
        val summary = aiSummaryDao.findSummary(summaryId)
        if (summary?.status == AiSummaryStateValue.COMPLETED) {
            reindexAiSummaryInternal(summary)
        }
    }

    suspend fun removeRecording(recordingId: String) {
        searchRepository.deleteForRecording(recordingId)
    }

    suspend fun removeTranscription(transcriptionId: String) {
        searchRepository.deleteForTranscription(transcriptionId)
    }

    suspend fun removeAiSummary(summaryId: String) {
        searchRepository.deleteForAiSummary(summaryId)
    }

    private suspend fun reindexRecording(recording: RecordingEntity) {
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

    private suspend fun reindexFolder(folder: FolderEntity) {
        searchRepository.upsert(
            SearchDocumentDraft(
                documentId = "folder:" + folder.folderId,
                documentType = SearchDocumentTypeValue.FOLDER,
                folderId = folder.folderId,
                displayTitle = folder.name,
                displayText = "文件夹",
                updatedAtMs = folder.updatedAtMs,
            ),
        )
    }

    private suspend fun reindexTag(tag: TagEntity) {
        searchRepository.upsert(
            SearchDocumentDraft(
                documentId = "tag:" + tag.tagId,
                documentType = SearchDocumentTypeValue.TAG,
                tagId = tag.tagId,
                displayTitle = tag.name,
                displayText = "标签",
                updatedAtMs = tag.updatedAtMs,
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
                ?: when (transcription.mode) {
                    "FAST" -> "快速转写"
                    "HIGH_QUALITY" -> "高质量转写"
                    else -> "转写"
                }

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
                        displayTitle = "AI 总结",
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
                displayTitle = effective.title.ifBlank { "AI 总结" },
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
