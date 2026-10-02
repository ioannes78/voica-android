package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class NewDiarizationRunRequest(
    val recordingId: String,
    val sourceCanonicalAssetId: String,
    val sourceCanonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val pipelineVersion: Int,
    val runtimeId: String,
    val runtimeVersion: String,
    val vadModelId: String,
    val vadModelVersion: String,
    val vadModelRevision: Long,
    val segmentationModelId: String,
    val segmentationModelVersion: String,
    val segmentationModelRevision: Long,
    val embeddingModelId: String,
    val embeddingModelVersion: String,
    val embeddingModelRevision: Long,
    val modelManifestDigest: String,
    val configSnapshot: String,
)

data class SpeakerTurnWrite(
    val speakerIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val confidence: Float?,
    val overlap: Boolean,
    val overlapMetadata: String? = null,
)

data class NewTranscriptSpeakerAlignmentRequest(
    val transcriptionId: String,
    val diarizationRunId: String,
    val alignmentVersion: Int,
    val configSnapshot: String,
)

data class TranscriptSpeakerSpanWrite(
    val spanIndex: Int,
    val sourceSegmentIndex: Int,
    val speakerIndex: Int?,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val tokenSource: String?,
    val tokenStartIndex: Int?,
    val tokenEndIndexExclusive: Int?,
    val finalTextStartOffset: Int,
    val finalTextEndOffsetExclusive: Int,
    val assignmentQuality: String,
    val overlap: Boolean,
    val ambiguous: Boolean,
)

data class StartupDiarizationReconciliation(
    val interruptedRuns: Int,
    val interruptedAlignments: Int,
)

class DiarizationRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.diarizationDao()
    private val transcriptionDao = database.transcriptionDao()

    fun observeRuns(recordingId: String): Flow<List<DiarizationRunEntity>> =
        dao.observeRuns(recordingId)

    fun observeLatestCompletedRun(recordingId: String): Flow<DiarizationRunEntity?> =
        dao.observeLatestCompletedRun(recordingId)

    fun observeAlignments(transcriptionId: String): Flow<List<TranscriptSpeakerAlignmentEntity>> =
        dao.observeAlignments(transcriptionId)

    suspend fun findRun(runId: String): DiarizationRunEntity? = dao.findRun(runId)

    suspend fun findAlignment(alignmentId: String): TranscriptSpeakerAlignmentEntity? =
        dao.findAlignment(alignmentId)

    suspend fun loadSpeakers(runId: String): List<DiarizationSpeakerEntity> =
        dao.loadSpeakers(runId)

    suspend fun loadTurns(runId: String): List<SpeakerTurnEntity> =
        dao.loadTurns(runId)

    suspend fun loadSpans(alignmentId: String): List<TranscriptSpeakerSpanEntity> =
        dao.loadSpans(alignmentId)

    suspend fun createRun(request: NewDiarizationRunRequest): String {
        validateRunRequest(request)
        val id = newId()
        val now = nowMs()
        dao.insertRun(
            DiarizationRunEntity(
                id = id,
                recordingId = request.recordingId,
                state = DiarizationStateValue.PREPARING,
                sourceCanonicalAssetId = request.sourceCanonicalAssetId,
                sourceCanonicalSha256 = request.sourceCanonicalSha256.lowercase(),
                canonicalProfileId = request.canonicalProfileId,
                totalSampleCount = request.totalSampleCount,
                pipelineVersion = request.pipelineVersion,
                runtimeId = request.runtimeId,
                runtimeVersion = request.runtimeVersion,
                vadModelId = request.vadModelId,
                vadModelVersion = request.vadModelVersion,
                vadModelRevision = request.vadModelRevision,
                segmentationModelId = request.segmentationModelId,
                segmentationModelVersion = request.segmentationModelVersion,
                segmentationModelRevision = request.segmentationModelRevision,
                embeddingModelId = request.embeddingModelId,
                embeddingModelVersion = request.embeddingModelVersion,
                embeddingModelRevision = request.embeddingModelRevision,
                modelManifestDigest = request.modelManifestDigest.lowercase(),
                configSnapshot = request.configSnapshot,
                createdAtMs = now,
                startedAtMs = now,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = null,
                errorMessage = null,
            ),
        )
        return id
    }

    suspend fun transitionRun(runId: String, state: String) {
        val current = dao.findRun(runId) ?: error("diarization run not found")
        if (current.state == state) return
        require(isAllowedRunTransition(current.state, state)) {
            "invalid diarization state transition: ${current.state} -> $state"
        }
        val now = nowMs()
        check(
            dao.updateRunState(
                runId = runId,
                state = state,
                startedAtMs = current.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = if (state == DiarizationStateValue.COMPLETED) now else null,
                errorCode = null,
                errorMessage = null,
            ) == 1,
        )
    }

    suspend fun cancelRun(runId: String) {
        terminateRun(
            runId = runId,
            state = DiarizationStateValue.CANCELLED,
            errorCode = "USER_CANCELLED",
            errorMessage = "diarization cancelled by user",
        )
    }

    suspend fun failRunRecoverable(
        runId: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        terminateRun(
            runId = runId,
            state = DiarizationStateValue.FAILED_RECOVERABLE,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    suspend fun persistCompletedRun(
        runId: String,
        speakerCount: Int,
        turns: List<SpeakerTurnWrite>,
    ) {
        require(speakerCount >= 0)
        val before = dao.findRun(runId) ?: error("diarization run not found")
        if (before.state != DiarizationStateValue.PERSISTING) {
            transitionRun(runId, DiarizationStateValue.PERSISTING)
        }
        val run = dao.findRun(runId) ?: error("diarization run not found")
        require(run.state == DiarizationStateValue.PERSISTING)
        validateTurns(speakerCount, turns, run.totalSampleCount)

        val speakerIds = List(speakerCount) { newId() }
        val now = nowMs()

        database.withTransaction {
            if (speakerCount > 0) {
                dao.insertSpeakers(
                    speakerIds.mapIndexed { index, speakerId ->
                        DiarizationSpeakerEntity(
                            id = speakerId,
                            diarizationRunId = runId,
                            speakerOrdinal = index + 1,
                            displayName = null,
                        )
                    },
                )
            }
            if (turns.isNotEmpty()) {
                dao.insertTurns(
                    turns.mapIndexed { turnIndex, turn ->
                        SpeakerTurnEntity(
                            id = newId(),
                            diarizationRunId = runId,
                            turnIndex = turnIndex,
                            speakerId = speakerIds[turn.speakerIndex],
                            startSampleIndex = turn.startSampleIndex,
                            endSampleIndexExclusive = turn.endSampleIndexExclusive,
                            confidence = turn.confidence,
                            overlap = turn.overlap,
                            overlapMetadata = turn.overlapMetadata,
                        )
                    },
                )
            }
            check(
                dao.updateRunState(
                    runId = runId,
                    state = DiarizationStateValue.COMPLETED,
                    startedAtMs = run.startedAtMs ?: run.createdAtMs,
                    updatedAtMs = now,
                    completedAtMs = now,
                    errorCode = null,
                    errorMessage = null,
                ) == 1,
            )
        }
    }

    suspend fun createAlignment(request: NewTranscriptSpeakerAlignmentRequest): String {
        require(request.alignmentVersion >= 1)
        require(request.configSnapshot.isNotBlank())
        val transcription =
            transcriptionDao.findTranscription(request.transcriptionId)
                ?: error("transcription not found")
        val run =
            dao.findRun(request.diarizationRunId)
                ?: error("diarization run not found")

        require(transcription.state == TranscriptionStateValue.COMPLETED)
        require(run.state == DiarizationStateValue.COMPLETED)
        require(transcription.recordingId == run.recordingId)
        require(transcription.sourceCanonicalSha256 == run.sourceCanonicalSha256)
        require(transcription.canonicalProfileId == run.canonicalProfileId)
        require(transcription.totalSampleCount == run.totalSampleCount)

        val id = newId()
        val now = nowMs()
        dao.insertAlignment(
            TranscriptSpeakerAlignmentEntity(
                id = id,
                transcriptionId = request.transcriptionId,
                diarizationRunId = request.diarizationRunId,
                alignmentVersion = request.alignmentVersion,
                configSnapshot = request.configSnapshot,
                state = TranscriptSpeakerAlignmentStateValue.PREPARING,
                createdAtMs = now,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = null,
                errorMessage = null,
            ),
        )
        return id
    }

    suspend fun transitionAlignment(alignmentId: String, state: String) {
        val current = dao.findAlignment(alignmentId) ?: error("speaker alignment not found")
        if (current.state == state) return
        require(isAllowedAlignmentTransition(current.state, state)) {
            "invalid alignment state transition: ${current.state} -> $state"
        }
        val now = nowMs()
        check(
            dao.updateAlignmentState(
                alignmentId = alignmentId,
                state = state,
                updatedAtMs = now,
                completedAtMs =
                    if (state == TranscriptSpeakerAlignmentStateValue.COMPLETED) now else null,
                errorCode = null,
                errorMessage = null,
            ) == 1,
        )
    }

    suspend fun cancelAlignment(alignmentId: String) {
        terminateAlignment(
            alignmentId = alignmentId,
            state = TranscriptSpeakerAlignmentStateValue.CANCELLED,
            errorCode = "USER_CANCELLED",
            errorMessage = "speaker alignment cancelled by user",
        )
    }

    suspend fun failAlignmentRecoverable(
        alignmentId: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        terminateAlignment(
            alignmentId = alignmentId,
            state = TranscriptSpeakerAlignmentStateValue.FAILED_RECOVERABLE,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    suspend fun persistCompletedAlignment(
        alignmentId: String,
        spans: List<TranscriptSpeakerSpanWrite>,
    ) {
        val before = dao.findAlignment(alignmentId) ?: error("speaker alignment not found")
        if (before.state != TranscriptSpeakerAlignmentStateValue.PERSISTING) {
            transitionAlignment(alignmentId, TranscriptSpeakerAlignmentStateValue.PERSISTING)
        }
        val alignment = dao.findAlignment(alignmentId) ?: error("speaker alignment not found")
        val run = dao.findRun(alignment.diarizationRunId) ?: error("diarization run not found")
        val segments = transcriptionDao.loadSegments(alignment.transcriptionId)
        val speakers = dao.loadSpeakers(alignment.diarizationRunId)
        validateSpans(spans, segments, speakers, run.totalSampleCount)

        val segmentsByIndex = segments.associateBy { it.segmentIndex }
        val speakersByIndex = speakers.associateBy { it.speakerOrdinal - 1 }
        val now = nowMs()

        database.withTransaction {
            if (spans.isNotEmpty()) {
                dao.insertSpans(
                    spans.map { span ->
                        TranscriptSpeakerSpanEntity(
                            id = newId(),
                            alignmentId = alignmentId,
                            spanIndex = span.spanIndex,
                            sourceTranscriptSegmentId =
                                checkNotNull(segmentsByIndex[span.sourceSegmentIndex]).id,
                            speakerId =
                                span.speakerIndex?.let {
                                    checkNotNull(speakersByIndex[it]).id
                                },
                            startSampleIndex = span.startSampleIndex,
                            endSampleIndexExclusive = span.endSampleIndexExclusive,
                            tokenSource = span.tokenSource,
                            tokenStartIndex = span.tokenStartIndex,
                            tokenEndIndexExclusive = span.tokenEndIndexExclusive,
                            finalTextStartOffset = span.finalTextStartOffset,
                            finalTextEndOffsetExclusive = span.finalTextEndOffsetExclusive,
                            assignmentQuality = span.assignmentQuality,
                            overlap = span.overlap,
                            ambiguous = span.ambiguous,
                        )
                    },
                )
            }
            check(
                dao.updateAlignmentState(
                    alignmentId = alignmentId,
                    state = TranscriptSpeakerAlignmentStateValue.COMPLETED,
                    updatedAtMs = now,
                    completedAtMs = now,
                    errorCode = null,
                    errorMessage = null,
                ) == 1,
            )
        }
    }

    suspend fun renameSpeaker(
        speakerId: String,
        requestedName: String?,
    ): Boolean {
        val name =
            requestedName
                ?.replace('\u0000', '_')
                ?.map { if (it.isISOControl()) '_' else it }
                ?.joinToString("")
                ?.trim()
                ?.take(MAX_DISPLAY_NAME)
                ?.takeIf { it.isNotBlank() }
        return dao.updateSpeakerDisplayName(speakerId, name) == 1
    }

    suspend fun reconcileInterruptedOnStartup(): StartupDiarizationReconciliation =
        StartupDiarizationReconciliation(
            interruptedRuns =
                dao.markActiveRunsInterrupted(
                    activeStates = DiarizationStateValue.ACTIVE,
                    nowMs = nowMs(),
                ),
            interruptedAlignments =
                dao.markActiveAlignmentsInterrupted(
                    activeStates = TranscriptSpeakerAlignmentStateValue.ACTIVE,
                    nowMs = nowMs(),
                ),
        )

    private suspend fun terminateRun(
        runId: String,
        state: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        require(state in RUN_FAILURE_STATES)
        val current = dao.findRun(runId) ?: error("diarization run not found")
        if (current.state == state) return
        require(current.state in DiarizationStateValue.ACTIVE) {
            "terminal diarization transition requires an active run"
        }
        val now = nowMs()
        check(
            dao.updateRunState(
                runId = runId,
                state = state,
                startedAtMs = current.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) == 1,
        )
    }

    private suspend fun terminateAlignment(
        alignmentId: String,
        state: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        require(state in ALIGNMENT_FAILURE_STATES)
        val current = dao.findAlignment(alignmentId) ?: error("speaker alignment not found")
        if (current.state == state) return
        require(current.state in TranscriptSpeakerAlignmentStateValue.ACTIVE) {
            "terminal alignment transition requires an active alignment"
        }
        val now = nowMs()
        check(
            dao.updateAlignmentState(
                alignmentId = alignmentId,
                state = state,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) == 1,
        )
    }

    private fun validateRunRequest(request: NewDiarizationRunRequest) {
        require(request.recordingId.isNotBlank())
        require(request.sourceCanonicalAssetId.isNotBlank())
        require(SHA256.matches(request.sourceCanonicalSha256))
        require(request.canonicalProfileId.isNotBlank())
        require(request.totalSampleCount >= 0L)
        require(request.pipelineVersion >= 1)
        require(request.runtimeId.isNotBlank())
        require(request.runtimeVersion.isNotBlank())
        require(request.vadModelId.isNotBlank())
        require(request.vadModelVersion.isNotBlank())
        require(request.vadModelRevision >= 1L)
        require(request.segmentationModelId.isNotBlank())
        require(request.segmentationModelVersion.isNotBlank())
        require(request.segmentationModelRevision >= 1L)
        require(request.embeddingModelId.isNotBlank())
        require(request.embeddingModelVersion.isNotBlank())
        require(request.embeddingModelRevision >= 1L)
        require(SHA256.matches(request.modelManifestDigest))
        require(request.configSnapshot.isNotBlank())
    }

    private fun validateTurns(
        speakerCount: Int,
        turns: List<SpeakerTurnWrite>,
        totalSampleCount: Long,
    ) {
        var previousStart = -1L
        turns.forEach { turn ->
            require(turn.speakerIndex in 0 until speakerCount)
            require(turn.startSampleIndex >= 0L)
            require(turn.startSampleIndex >= previousStart)
            require(turn.endSampleIndexExclusive > turn.startSampleIndex)
            require(turn.endSampleIndexExclusive <= totalSampleCount)
            require(
                turn.confidence == null ||
                    (turn.confidence.isFinite() && turn.confidence in -1F..1F),
            )
            previousStart = turn.startSampleIndex
        }
    }

    private fun validateSpans(
        spans: List<TranscriptSpeakerSpanWrite>,
        segments: List<TranscriptSegmentEntity>,
        speakers: List<DiarizationSpeakerEntity>,
        totalSampleCount: Long,
    ) {
        val segmentsByIndex = segments.associateBy { it.segmentIndex }
        val speakerIndices = speakers.map { it.speakerOrdinal - 1 }.toSet()

        spans.forEachIndexed { expectedIndex, span ->
            require(span.spanIndex == expectedIndex)
            val segment =
                segmentsByIndex[span.sourceSegmentIndex]
                    ?: error("speaker span references unknown transcript segment")
            require(span.speakerIndex == null || span.speakerIndex in speakerIndices)
            require(span.startSampleIndex >= segment.startSampleIndex)
            require(span.endSampleIndexExclusive > span.startSampleIndex)
            require(span.endSampleIndexExclusive <= segment.endSampleIndexExclusive)
            require(span.endSampleIndexExclusive <= totalSampleCount)
            require(span.finalTextStartOffset >= 0)
            require(span.finalTextEndOffsetExclusive >= span.finalTextStartOffset)
            require(span.finalTextEndOffsetExclusive <= segment.finalText.length)
            require((span.tokenStartIndex == null) == (span.tokenEndIndexExclusive == null))
            if (span.tokenStartIndex != null) {
                require(span.tokenStartIndex >= 0)
                require(span.tokenEndIndexExclusive!! > span.tokenStartIndex)
                require(
                    span.tokenSource == TranscriptTokenSourceValue.FIRST_PASS ||
                        span.tokenSource == TranscriptTokenSourceValue.SECOND_PASS,
                )
            }
            when (span.assignmentQuality) {
                SpeakerAssignmentQualityValue.ASSIGNED,
                SpeakerAssignmentQualityValue.ASSIGNED_WITH_OVERLAP,
                -> require(span.speakerIndex != null)

                SpeakerAssignmentQualityValue.OVERLAP_AMBIGUOUS,
                SpeakerAssignmentQualityValue.UNRESOLVED,
                -> require(span.speakerIndex == null)

                else -> error("unknown speaker assignment quality")
            }
            require(
                !span.ambiguous ||
                    span.assignmentQuality == SpeakerAssignmentQualityValue.OVERLAP_AMBIGUOUS,
            )
        }

        val bySegment = spans.groupBy { it.sourceSegmentIndex }
        segments.filter { it.finalText.isNotEmpty() }.forEach { segment ->
            val segmentSpans =
                bySegment[segment.segmentIndex]
                    ?.sortedBy { it.finalTextStartOffset }
                    ?: error("non-empty transcript segment has no speaker spans")
            var cursor = 0
            segmentSpans.forEach { span ->
                require(span.finalTextStartOffset == cursor) {
                    "speaker spans must cover finalText without gaps or overlap"
                }
                cursor = span.finalTextEndOffsetExclusive
            }
            require(cursor == segment.finalText.length) {
                "speaker spans must cover the complete finalText"
            }
        }
    }

    private fun isAllowedRunTransition(from: String, to: String): Boolean {
        if (from !in DiarizationStateValue.ACTIVE) return false
        if (to in RUN_FAILURE_STATES) return true
        return when (from) {
            DiarizationStateValue.PREPARING -> to == DiarizationStateValue.VAD_ANALYZING
            DiarizationStateValue.VAD_ANALYZING ->
                to == DiarizationStateValue.DIARIZING ||
                    to == DiarizationStateValue.PERSISTING
            DiarizationStateValue.DIARIZING ->
                to == DiarizationStateValue.STITCHING ||
                    to == DiarizationStateValue.PERSISTING
            DiarizationStateValue.STITCHING,
            DiarizationStateValue.ALIGNING,
            -> to == DiarizationStateValue.PERSISTING
            DiarizationStateValue.PERSISTING -> to == DiarizationStateValue.COMPLETED
            else -> false
        }
    }

    private fun isAllowedAlignmentTransition(from: String, to: String): Boolean {
        if (from !in TranscriptSpeakerAlignmentStateValue.ACTIVE) return false
        if (to in ALIGNMENT_FAILURE_STATES) return true
        return when (from) {
            TranscriptSpeakerAlignmentStateValue.PREPARING ->
                to == TranscriptSpeakerAlignmentStateValue.ALIGNING ||
                    to == TranscriptSpeakerAlignmentStateValue.PERSISTING
            TranscriptSpeakerAlignmentStateValue.ALIGNING ->
                to == TranscriptSpeakerAlignmentStateValue.PERSISTING
            TranscriptSpeakerAlignmentStateValue.PERSISTING ->
                to == TranscriptSpeakerAlignmentStateValue.COMPLETED
            else -> false
        }
    }

    private fun newId(): String =
        idFactory().also { require(it.isNotBlank()) }

    private companion object {
        const val MAX_DISPLAY_NAME = 80
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val RUN_FAILURE_STATES =
            setOf(
                DiarizationStateValue.CANCELLED,
                DiarizationStateValue.INTERRUPTED,
                DiarizationStateValue.FAILED_RECOVERABLE,
                DiarizationStateValue.FAILED_PERMANENT,
            )
        val ALIGNMENT_FAILURE_STATES =
            setOf(
                TranscriptSpeakerAlignmentStateValue.CANCELLED,
                TranscriptSpeakerAlignmentStateValue.INTERRUPTED,
                TranscriptSpeakerAlignmentStateValue.FAILED_RECOVERABLE,
                TranscriptSpeakerAlignmentStateValue.FAILED_PERMANENT,
            )
    }
}
