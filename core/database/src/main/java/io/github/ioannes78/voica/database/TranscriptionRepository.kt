package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class NewTranscriptionRequest(
    val recordingId: String,
    val mode: String,
    val sourceCanonicalAssetId: String,
    val sourceCanonicalSha256: String,
    val canonicalProfileId: String,
    val totalSampleCount: Long,
    val pipelineVersion: Int,
    val runtimeId: String,
    val runtimeVersion: String,
    val vadModelId: String,
    val vadModelVersion: String,
    val firstPassAsrModelId: String,
    val firstPassAsrModelVersion: String,
    val secondPassAsrModelId: String?,
    val secondPassAsrModelVersion: String?,
    val punctuationModelId: String?,
    val punctuationModelVersion: String?,
    val languageConfig: String,
    val configSnapshot: String,
    val modelManifestDigest: String,
    val vadModelRevision: Long = 1L,
    val firstPassAsrModelRevision: Long = 1L,
    val secondPassAsrModelRevision: Long? = secondPassAsrModelId?.let { 1L },
    val punctuationModelRevision: Long? = punctuationModelId?.let { 1L },
    val configSnapshotSchemaVersion: Int = 1,
    val requestedConfigSnapshot: String = configSnapshot,
    val effectiveConfigSnapshot: String = configSnapshot,
)

data class TranscriptTokenWrite(
    val text: String,
    val startSampleIndex: Long?,
    val endSampleIndexExclusive: Long?,
    val source: String,
)

data class TranscriptSegmentWrite(
    val segmentIndex: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val firstPassRawText: String,
    val secondPassRawText: String?,
    val finalText: String,
    val detectedLanguage: String?,
    val confidence: Float?,
    val tokens: List<TranscriptTokenWrite>,
)

class TranscriptionRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.transcriptionDao()

    fun observeVersions(recordingId: String): Flow<List<TranscriptionEntity>> =
        dao.observeVersions(recordingId)

    fun observeLatestCompleted(recordingId: String): Flow<TranscriptionEntity?> =
        dao.observeLatestCompleted(recordingId)

    fun observeSegments(transcriptionId: String): Flow<List<TranscriptSegmentEntity>> =
        dao.observeSegments(transcriptionId)

    suspend fun loadSegments(transcriptionId: String): List<TranscriptSegmentEntity> =
        dao.loadSegments(transcriptionId)

    suspend fun loadTokens(segmentId: String): List<TranscriptTokenEntity> =
        dao.loadTokens(segmentId)

    suspend fun loadTokensForTranscription(
        transcriptionId: String,
    ): List<TranscriptTokenEntity> =
        dao.loadTokensForTranscription(transcriptionId)

    suspend fun find(transcriptionId: String): TranscriptionEntity? =
        dao.findTranscription(transcriptionId)

    suspend fun create(request: NewTranscriptionRequest): String {
        validateRequest(request)
        val now = nowMs()
        val id = idFactory()
        require(id.isNotBlank())

        dao.insertTranscription(
            TranscriptionEntity(
                id = id,
                recordingId = request.recordingId,
                mode = request.mode,
                state = TranscriptionStateValue.PREPARING,
                sourceCanonicalAssetId = request.sourceCanonicalAssetId,
                sourceCanonicalSha256 = request.sourceCanonicalSha256.lowercase(),
                canonicalProfileId = request.canonicalProfileId,
                totalSampleCount = request.totalSampleCount,
                pipelineVersion = request.pipelineVersion,
                runtimeId = request.runtimeId,
                runtimeVersion = request.runtimeVersion,
                vadModelId = request.vadModelId,
                vadModelVersion = request.vadModelVersion,
                firstPassAsrModelId = request.firstPassAsrModelId,
                firstPassAsrModelVersion = request.firstPassAsrModelVersion,
                secondPassAsrModelId = request.secondPassAsrModelId,
                secondPassAsrModelVersion = request.secondPassAsrModelVersion,
                punctuationModelId = request.punctuationModelId,
                punctuationModelVersion = request.punctuationModelVersion,
                languageConfig = request.languageConfig,
                configSnapshot = request.configSnapshot,
                modelManifestDigest = request.modelManifestDigest.lowercase(),
                createdAtMs = now,
                startedAtMs = now,
                updatedAtMs = now,
                completedAtMs = null,
                errorCode = null,
                errorMessage = null,
                vadModelRevision = request.vadModelRevision,
                firstPassAsrModelRevision = request.firstPassAsrModelRevision,
                secondPassAsrModelRevision = request.secondPassAsrModelRevision,
                punctuationModelRevision = request.punctuationModelRevision,
                configSnapshotSchemaVersion = request.configSnapshotSchemaVersion,
                requestedConfigSnapshot = request.requestedConfigSnapshot,
                effectiveConfigSnapshot = request.effectiveConfigSnapshot,
            ),
        )
        return id
    }

    suspend fun transition(
        transcriptionId: String,
        state: String,
    ) {
        val current =
            dao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        if (current.state == state) return
        require(isAllowedTransition(current.state, state)) {
            "invalid transcription state transition: " +
                current.state + " -> " + state
        }
        val now = nowMs()
        val completedAt =
            if (state in TERMINAL_STATES) now else null
        check(
            dao.updateState(
                transcriptionId = transcriptionId,
                state = state,
                startedAtMs = current.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = completedAt,
                errorCode = null,
                errorMessage = null,
            ) == 1,
        )
    }

    suspend fun cancel(transcriptionId: String) {
        transitionTerminal(
            transcriptionId = transcriptionId,
            state = TranscriptionStateValue.CANCELLED,
            errorCode = "USER_CANCELLED",
            errorMessage = "transcription cancelled by user",
        )
    }

    suspend fun failRecoverable(
        transcriptionId: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        transitionTerminal(
            transcriptionId = transcriptionId,
            state = TranscriptionStateValue.FAILED_RECOVERABLE,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    suspend fun failPermanent(
        transcriptionId: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        transitionTerminal(
            transcriptionId = transcriptionId,
            state = TranscriptionStateValue.FAILED_PERMANENT,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
    }

    suspend fun persistCompleted(
        transcriptionId: String,
        segments: List<TranscriptSegmentWrite>,
    ) {
        val before =
            dao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        if (before.state != TranscriptionStateValue.PERSISTING) {
            transition(
                transcriptionId = transcriptionId,
                state = TranscriptionStateValue.PERSISTING,
            )
        }

        val transcription =
            dao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        require(transcription.state == TranscriptionStateValue.PERSISTING)
        validateSegments(
            segments = segments,
            totalSampleCount = transcription.totalSampleCount,
        )

        val completedAt = nowMs()
        database.withTransaction {
            segments.forEach { segment ->
                val segmentId = idFactory()
                require(segmentId.isNotBlank())
                dao.insertSegment(
                    TranscriptSegmentEntity(
                        id = segmentId,
                        transcriptionId = transcriptionId,
                        segmentIndex = segment.segmentIndex,
                        startSampleIndex = segment.startSampleIndex,
                        endSampleIndexExclusive = segment.endSampleIndexExclusive,
                        firstPassRawText = segment.firstPassRawText,
                        secondPassRawText = segment.secondPassRawText,
                        finalText = segment.finalText,
                        detectedLanguage = segment.detectedLanguage,
                        confidence = segment.confidence,
                    ),
                )
                if (segment.tokens.isNotEmpty()) {
                    dao.insertTokens(
                        segment.tokens.mapIndexed { tokenIndex, token ->
                            TranscriptTokenEntity(
                                id = idFactory().also { require(it.isNotBlank()) },
                                transcriptSegmentId = segmentId,
                                tokenIndex = tokenIndex,
                                text = token.text,
                                startSampleIndex = token.startSampleIndex,
                                endSampleIndexExclusive = token.endSampleIndexExclusive,
                                source = token.source,
                            )
                        },
                    )
                }
            }

            check(
                dao.updateState(
                    transcriptionId = transcriptionId,
                    state = TranscriptionStateValue.COMPLETED,
                    startedAtMs = transcription.startedAtMs ?: transcription.createdAtMs,
                    updatedAtMs = completedAt,
                    completedAtMs = completedAt,
                    errorCode = null,
                    errorMessage = null,
                ) == 1,
            )
        }
        SearchIndexRebuilder(database).reindexTranscription(transcriptionId)
    }

    suspend fun reconcileInterruptedOnStartup(): Int =
        dao.markActiveInterrupted(
            activeStates = TranscriptionStateValue.ACTIVE,
            nowMs = nowMs(),
        )

    private suspend fun transitionTerminal(
        transcriptionId: String,
        state: String,
        errorCode: String,
        errorMessage: String?,
    ) {
        require(state in TERMINAL_FAILURE_STATES)
        val current =
            dao.findTranscription(transcriptionId)
                ?: error("transcription not found")
        if (current.state == state) return
        require(current.state in TranscriptionStateValue.ACTIVE) {
            "terminal transition requires an active transcription"
        }
        val now = nowMs()
        check(
            dao.updateState(
                transcriptionId = transcriptionId,
                state = state,
                startedAtMs = current.startedAtMs ?: now,
                updatedAtMs = now,
                completedAtMs = now,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) == 1,
        )
    }

    private fun validateRequest(request: NewTranscriptionRequest) {
        require(request.recordingId.isNotBlank())
        require(
            request.mode == TranscriptionModeValue.FAST ||
                request.mode == TranscriptionModeValue.HIGH_QUALITY,
        )
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
        require(request.firstPassAsrModelId.isNotBlank())
        require(request.firstPassAsrModelVersion.isNotBlank())
        require(request.firstPassAsrModelRevision >= 1L)
        require(
            (request.secondPassAsrModelId == null) ==
                (request.secondPassAsrModelVersion == null),
        )
        require(
            (request.secondPassAsrModelId == null) ==
                (request.secondPassAsrModelRevision == null),
        )
        require(request.secondPassAsrModelRevision == null || request.secondPassAsrModelRevision >= 1L)
        require(
            (request.punctuationModelId == null) ==
                (request.punctuationModelVersion == null),
        )
        require(
            (request.punctuationModelId == null) ==
                (request.punctuationModelRevision == null),
        )
        require(request.punctuationModelRevision == null || request.punctuationModelRevision >= 1L)
        require(request.languageConfig.isNotBlank())
        require(request.configSnapshot.isNotBlank())
        require(request.configSnapshotSchemaVersion >= 1)
        require(request.requestedConfigSnapshot.isNotBlank())
        require(request.effectiveConfigSnapshot.isNotBlank())
        require(SHA256.matches(request.modelManifestDigest))
    }

    private fun validateSegments(
        segments: List<TranscriptSegmentWrite>,
        totalSampleCount: Long,
    ) {
        var previousEnd = 0L
        segments.forEachIndexed { expectedIndex, segment ->
            require(segment.segmentIndex == expectedIndex)
            require(segment.startSampleIndex >= previousEnd)
            require(segment.endSampleIndexExclusive > segment.startSampleIndex)
            require(segment.endSampleIndexExclusive <= totalSampleCount)
            require(segment.confidence == null || segment.confidence in 0.0F..1.0F)
            segment.tokens.forEach { token ->
                require(token.text.isNotEmpty())
                require(
                    token.source == TranscriptTokenSourceValue.FIRST_PASS ||
                        token.source == TranscriptTokenSourceValue.SECOND_PASS,
                )
                val start = token.startSampleIndex
                val end = token.endSampleIndexExclusive
                if (start != null) {
                    require(start >= segment.startSampleIndex)
                    require(start < segment.endSampleIndexExclusive)
                }
                if (end != null) {
                    require(start != null)
                    require(end > start)
                    require(end <= segment.endSampleIndexExclusive)
                }
            }
            previousEnd = segment.endSampleIndexExclusive
        }
    }

    private fun isAllowedTransition(
        from: String,
        to: String,
    ): Boolean {
        if (from !in TranscriptionStateValue.ACTIVE) return false
        if (to in TERMINAL_FAILURE_STATES) return true

        return when (from) {
            TranscriptionStateValue.PREPARING ->
                to == TranscriptionStateValue.VAD_ANALYZING

            TranscriptionStateValue.VAD_ANALYZING ->
                to == TranscriptionStateValue.FIRST_PASS_TRANSCRIBING ||
                    to == TranscriptionStateValue.SECOND_PASS_TRANSCRIBING ||
                    to == TranscriptionStateValue.PERSISTING

            TranscriptionStateValue.FIRST_PASS_TRANSCRIBING ->
                to == TranscriptionStateValue.SECOND_PASS_TRANSCRIBING ||
                    to == TranscriptionStateValue.PUNCTUATING ||
                    to == TranscriptionStateValue.PERSISTING

            TranscriptionStateValue.SECOND_PASS_TRANSCRIBING ->
                to == TranscriptionStateValue.PUNCTUATING ||
                    to == TranscriptionStateValue.PERSISTING

            TranscriptionStateValue.PUNCTUATING ->
                to == TranscriptionStateValue.PERSISTING

            TranscriptionStateValue.PERSISTING ->
                to == TranscriptionStateValue.COMPLETED

            else -> false
        }
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        val TERMINAL_FAILURE_STATES =
            setOf(
                TranscriptionStateValue.CANCELLED,
                TranscriptionStateValue.INTERRUPTED,
                TranscriptionStateValue.FAILED_RECOVERABLE,
                TranscriptionStateValue.FAILED_PERMANENT,
            )

        val TERMINAL_STATES =
            TERMINAL_FAILURE_STATES + TranscriptionStateValue.COMPLETED
    }
}
