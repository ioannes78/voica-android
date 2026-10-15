package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.security.MessageDigest

/** Canonical media identity produced directly by a complete Stage 16A realtime capture. */
data class RealtimeCanonicalCapture(
    val recordingId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val profileId: String,
    val pipelineVersion: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val verifiedAtMs: Long,
)

data class RealtimeTranscriptProjectionResult(
    val transcriptionId: String?,
    val segmentCount: Int,
    val alignedToCanonical: Boolean,
)

/**
 * Bridges durable Stage 16A realtime rows into the existing Recording/Transcription product model.
 *
 * This does not invent missing recorder-card audio. A multi-segment reconnect run is projected as a
 * text-only realtime transcription using a dedicated non-playback profile. A single verified local
 * canonical WAV may instead use the normal canonical profile and exact sample coordinates.
 */
class RealtimeRecordingIntegrationRepository(
    private val database: VoicaDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val recordingDao = database.recordingDao()
    private val realtimeDao = database.realtimeTranscriptionDao()
    private val transcriptionDao = database.transcriptionDao()
    private val contentRepository = Stage12CContentRepository(database, nowMs = nowMs)

    suspend fun commitDirectCanonicalCapture(registration: RealtimeCanonicalCapture): String {
        require(registration.sizeBytes > 0L) { "canonical capture must not be empty" }
        val normalizedSha = registration.sha256.lowercase()
        require(SHA256.matches(normalizedSha)) { "invalid canonical SHA-256" }
        val assetId = canonicalAssetId(registration.recordingId, registration.profileId)
        database.withTransaction {
            recordingDao.upsertAsset(
                AudioAssetEntity(
                    assetId = assetId,
                    recordingId = registration.recordingId,
                    role = AudioAssetRole.CANONICAL_WAV,
                    relativePath = registration.relativePath,
                    container = "WAV",
                    codec = "PCM",
                    sampleFormat = "PCM16_LE",
                    sampleRateHz = registration.sampleRateHz,
                    channelCount = registration.channelCount,
                    sizeBytes = registration.sizeBytes,
                    sha256 = normalizedSha,
                    integrityState = AudioIntegrityState.VERIFIED,
                    formatValidationState = AudioValidationState.VALID,
                    createdAtMs = registration.verifiedAtMs,
                    verifiedAtMs = registration.verifiedAtMs,
                ),
            )
            // Direct realtime capture is already canonical. Its lineage is an identity derivation so
            // the same verified-media contract used by offline ASR/playback remains authoritative.
            recordingDao.upsertDerivation(
                AudioDerivationEntity(
                    recordingId = registration.recordingId,
                    profileId = registration.profileId,
                    sourceSha256 = normalizedSha,
                    sourceAssetId = assetId,
                    outputAssetId = assetId,
                    pipelineVersion = registration.pipelineVersion,
                    state = AudioDerivationState.READY,
                    startedAtMs = registration.verifiedAtMs,
                    updatedAtMs = registration.verifiedAtMs,
                    completedAtMs = registration.verifiedAtMs,
                    errorCode = null,
                    errorDetail = null,
                ),
            )
        }
        clearRemotePendingAsset(registration.recordingId)
        return assetId
    }

    suspend fun ensureRemotePendingAsset(
        recordingId: String,
        remoteIdentity: String,
        filename: String,
        sizeBytes: Long,
    ) {
        if (sizeBytes <= 0L) return
        val verifiedSource = recordingDao.findAsset(recordingId, AudioAssetRole.DEVICE_OPUS)
        if (verifiedSource?.integrityState == AudioIntegrityState.VERIFIED) {
            clearRemotePendingAsset(recordingId)
            return
        }
        val now = nowMs()
        recordingDao.upsertAsset(
            AudioAssetEntity(
                assetId = remotePendingAssetId(recordingId),
                recordingId = recordingId,
                role = AudioAssetRole.REMOTE_PENDING,
                relativePath = "remote-pending/$recordingId.opus",
                container = "OPUS",
                codec = "OPUS",
                sampleFormat = null,
                sampleRateHz = null,
                channelCount = null,
                sizeBytes = sizeBytes,
                sha256 = stableId("pending|$remoteIdentity|$filename|$sizeBytes"),
                integrityState = AudioIntegrityState.MISSING,
                formatValidationState = AudioValidationState.UNVERIFIED,
                createdAtMs = now,
                verifiedAtMs = null,
            ),
        )
    }

    suspend fun clearRemotePendingAsset(recordingId: String) {
        recordingDao.findAsset(recordingId, AudioAssetRole.REMOTE_PENDING)
            ?.let { recordingDao.deleteAsset(it.assetId) }
    }

    suspend fun hasVerifiedDeviceOpus(recordingId: String): Boolean =
        recordingDao.findAsset(recordingId, AudioAssetRole.DEVICE_OPUS)
            ?.integrityState == AudioIntegrityState.VERIFIED

    suspend fun projectCompletedRealtimeRun(
        runId: String,
        recordingId: String,
        canonicalAssetId: String?,
        canonicalSha256: String?,
        canonicalProfileId: String,
        canonicalPipelineVersion: Int,
    ): RealtimeTranscriptProjectionResult {
        val run = realtimeDao.getRun(runId)
            ?: return RealtimeTranscriptProjectionResult(null, 0, false)
        if (run.recordingId != recordingId || run.state != RealtimeTranscriptionStateValue.COMPLETED) {
            return RealtimeTranscriptProjectionResult(null, 0, false)
        }
        val segments = realtimeDao.getSegments(runId).sortedBy { it.segmentIndex }
        val utterancesBySegment =
            segments.map { segment -> segment to realtimeDao.getUtterances(segment.id) }
        val allUtterances = utterancesBySegment.flatMap { it.second }
        if (allUtterances.isEmpty()) {
            return RealtimeTranscriptProjectionResult(null, 0, false)
        }

        val timelineMarkers = realtimeDao.getTimelineMarkers(runId)
        val exactEpochOffsets =
            segments.singleOrNull()?.let { segment ->
                resolveRealtimeCanonicalEpochOffsets(
                    segment = segment,
                    utterances = utterancesBySegment.single().second,
                    markers = timelineMarkers,
                )
            }
        val aligned =
            segments.size == 1 &&
                canonicalAssetId != null &&
                canonicalSha256?.matches(SHA256) == true &&
                exactEpochOffsets != null
        val transcriptionId = realtimeTranscriptionId(runId)
        val existing = transcriptionDao.findTranscription(transcriptionId)
        if (existing != null) {
            return RealtimeTranscriptProjectionResult(
                transcriptionId = transcriptionId,
                segmentCount = transcriptionDao.loadSegments(transcriptionId).size,
                alignedToCanonical = existing.canonicalProfileId == canonicalProfileId,
            )
        }

        val totalSamples = segments.sumOf { it.canonicalSampleCount.coerceAtLeast(0L) }
        val sourceAssetId =
            if (aligned) checkNotNull(canonicalAssetId) else stableId("segmented-source|$runId")
        val sourceSha =
            if (aligned) {
                checkNotNull(canonicalSha256).lowercase()
            } else {
                stableId(
                    buildString {
                        append(runId)
                        segments.forEach { segment ->
                            append('|')
                            append(segment.sha256 ?: "n/a")
                            append(':')
                            append(segment.canonicalSampleCount)
                        }
                        allUtterances.forEach { utterance ->
                            append('|')
                            append(utterance.finalText)
                        }
                    },
                )
            }
        val profileId =
            if (aligned) canonicalProfileId else RealtimeTranscriptProfileValue.SEGMENTED_CAPTURE
        val now = run.endedAtMs ?: nowMs()

        database.withTransaction {
            transcriptionDao.insertTranscription(
                TranscriptionEntity(
                    id = transcriptionId,
                    recordingId = recordingId,
                    mode = TranscriptionModeValue.REALTIME,
                    state = TranscriptionStateValue.COMPLETED,
                    sourceCanonicalAssetId = sourceAssetId,
                    sourceCanonicalSha256 = sourceSha,
                    canonicalProfileId = profileId,
                    totalSampleCount = totalSamples,
                    pipelineVersion = canonicalPipelineVersion,
                    runtimeId = run.runtimeId,
                    runtimeVersion = run.runtimeVersion,
                    vadModelId = run.vadModelId ?: "stage16a-realtime-vad",
                    vadModelVersion = run.vadModelVersion ?: "unknown",
                    firstPassAsrModelId = run.modelId,
                    firstPassAsrModelVersion = run.modelVersion,
                    secondPassAsrModelId = null,
                    secondPassAsrModelVersion = null,
                    punctuationModelId = run.punctuationModelId,
                    punctuationModelVersion = run.punctuationModelVersion,
                    languageConfig = run.languageConfig,
                    configSnapshot = run.effectiveConfigSnapshot,
                    modelManifestDigest = run.modelManifestDigest,
                    createdAtMs = run.createdAtMs,
                    startedAtMs = run.startedAtMs,
                    updatedAtMs = now,
                    completedAtMs = now,
                    errorCode = null,
                    errorMessage = null,
                    vadModelRevision = run.vadModelRevision ?: 1L,
                    firstPassAsrModelRevision = run.modelRevision,
                    secondPassAsrModelRevision = null,
                    punctuationModelRevision = run.punctuationModelRevision,
                    configSnapshotSchemaVersion = run.configSnapshotSchemaVersion,
                    requestedConfigSnapshot = run.requestedConfigSnapshot,
                    effectiveConfigSnapshot = run.effectiveConfigSnapshot,
                    terminalAcknowledgedAtMs = now,
                ),
            )

            var segmentOffset = 0L
            var projectedIndex = 0
            utterancesBySegment.forEach { (segment, utterances) ->
                utterances.forEach { utterance ->
                    val start =
                        if (aligned) {
                            Math.addExact(
                                checkNotNull(exactEpochOffsets)[utterance.canonicalEpochId]
                                    ?: error("missing exact realtime epoch offset"),
                                utterance.startSampleIndex,
                            )
                        } else {
                            segmentOffset + utterance.startSampleIndex
                        }
                    val end =
                        if (aligned) {
                            Math.addExact(
                                checkNotNull(exactEpochOffsets)[utterance.canonicalEpochId]
                                    ?: error("missing exact realtime epoch offset"),
                                utterance.endSampleIndexExclusive,
                            )
                        } else {
                            segmentOffset + utterance.endSampleIndexExclusive
                        }
                    transcriptionDao.insertSegment(
                        TranscriptSegmentEntity(
                            id = "$transcriptionId-segment-$projectedIndex",
                            transcriptionId = transcriptionId,
                            segmentIndex = projectedIndex,
                            startSampleIndex = start.coerceAtLeast(0L),
                            endSampleIndexExclusive = end.coerceAtLeast(start),
                            firstPassRawText = utterance.rawFinalText,
                            secondPassRawText = null,
                            finalText = utterance.finalText,
                            detectedLanguage = utterance.detectedLanguage,
                            confidence = null,
                        ),
                    )
                    projectedIndex += 1
                }
                if (!aligned) segmentOffset += segment.canonicalSampleCount.coerceAtLeast(0L)
            }
        }

        contentRepository.onTranscriptionCompleted(recordingId, transcriptionId)
        return RealtimeTranscriptProjectionResult(
            transcriptionId = transcriptionId,
            segmentCount = allUtterances.size,
            alignedToCanonical = aligned,
        )
    }

    suspend fun completedAttachedRuns(): List<RealtimeTranscriptionRunEntity> =
        realtimeDao.getCompletedAttachedRuns()

    private fun realtimeTranscriptionId(runId: String): String = "stage16a-realtime-$runId"

    private fun canonicalAssetId(recordingId: String, profileId: String): String =
        stableId("$recordingId|canonical=$profileId")

    private fun remotePendingAssetId(recordingId: String): String =
        stableId("$recordingId|remote-pending")

    private fun stableId(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    }
}
