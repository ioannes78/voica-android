package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import io.github.ioannes78.voica.ai.OfflineQaEvidence
import io.github.ioannes78.voica.ai.OfflineQaFailureCode
import io.github.ioannes78.voica.ai.OfflineQaSnapshot
import io.github.ioannes78.voica.ai.OfflineQaSourceIdentity

class OfflineQaSourceException(val code: OfflineQaFailureCode) : IllegalStateException(code.name)

/** Database lineage validation only. Actual file/loaded player identity must be checked at seek. */
class OfflineQaEvidenceResolver(private val database: VoicaDatabase) {
    private val content = Stage12CContentRepository(database)
    private val builder = StructuredTranscriptInputBuilder(database)

    suspend fun loadCurrent(recordingId: String): OfflineQaSnapshot =
        database.withTransaction { readCurrent(recordingId) }

    suspend fun requireCurrent(expected: OfflineQaSourceIdentity): OfflineQaSnapshot =
        database.withTransaction { readMatching(expected) }

    suspend fun resolveCurrent(expected: OfflineQaSourceIdentity, ref: String): OfflineQaEvidence =
        database.withTransaction {
            readMatching(expected).resolve(ref)
                ?: throw OfflineQaSourceException(OfflineQaFailureCode.INVALID_EVIDENCE)
        }

    private suspend fun readMatching(expected: OfflineQaSourceIdentity): OfflineQaSnapshot {
        val current = readCurrent(expected.recordingId)
        if (current.source != expected) throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
        return current
    }

    private suspend fun readCurrent(recordingId: String): OfflineQaSnapshot {
        require(recordingId.isNotBlank())
        val row = database.recordingDao().findWithAssets(recordingId)
            ?: unavailable()
        if (row.recording.state != RecordingState.ACTIVE) unavailable()
        val effective = content.resolveEffectiveTranscription(recordingId) ?: unavailable()
        val tx = database.transcriptionDao().findTranscription(effective.transcriptionId) ?: unavailable()
        if (tx.recordingId != recordingId || tx.state != TranscriptionStateValue.COMPLETED) unavailable()
        val asset = row.assets.singleOrNull { it.role == AudioAssetRole.CANONICAL_WAV } ?: unavailable()
        if (asset.integrityState != AudioIntegrityState.VERIFIED ||
            asset.formatValidationState != AudioValidationState.VALID ||
            asset.sampleRateHz != 16_000 || asset.channelCount != 1 ||
            asset.sizeBytes <= 0 || asset.relativePath.isBlank()
        ) unavailable()
        if (asset.assetId != tx.sourceCanonicalAssetId || asset.sha256 != tx.sourceCanonicalSha256) {
            throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_CHANGED)
        }
        if (tx.canonicalProfileId != "CANONICAL_PCM16_16000_MONO_WAV_V1" ||
            row.derivations.none {
                it.profileId == tx.canonicalProfileId &&
                    it.outputAssetId == asset.assetId && it.state == AudioDerivationState.READY
            }
        ) unavailable()
        // Selection, revision, alignment, segments and canonical lineage share this Room transaction.
        // No native inference or filesystem IO inside this transaction.
        val input = try {
            builder.buildSnapshot(effective.transcriptionId, effective.revisionId)
        } catch (_: IllegalArgumentException) {
            unavailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            unavailable()
        }
        return try {
            OfflineQaSnapshot.from(input)
        } catch (_: IllegalArgumentException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing =
        throw OfflineQaSourceException(OfflineQaFailureCode.SOURCE_UNAVAILABLE)
}
