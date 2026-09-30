package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Transaction
    @Query("SELECT * FROM recordings WHERE state != 'DELETED' ORDER BY recordedAtLocalIso DESC, downloadedAtMs DESC")
    fun observeAll(): Flow<List<RecordingWithAssets>>

    @Transaction
    @Query("SELECT * FROM recordings WHERE id = :recordingId LIMIT 1")
    suspend fun findWithAssets(recordingId: String): RecordingWithAssets?

    @Query("SELECT * FROM recordings WHERE sourceRemoteIdentity = :remoteIdentity LIMIT 1")
    suspend fun findByRemoteIdentity(remoteIdentity: String): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun allRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM audio_assets WHERE recordingId = :recordingId AND role = :role LIMIT 1")
    suspend fun findAsset(recordingId: String, role: String): AudioAssetEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordingIgnore(recording: RecordingEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssetIgnore(asset: AudioAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(asset: AudioAssetEntity)

    @Query("UPDATE audio_assets SET integrityState = :integrityState, formatValidationState = :validationState, container = :container, codec = :codec, sampleRateHz = :sampleRateHz, channelCount = :channelCount, verifiedAtMs = :verifiedAtMs WHERE assetId = :assetId")
    suspend fun updateAssetValidation(
        assetId: String,
        integrityState: String,
        validationState: String,
        container: String,
        codec: String?,
        sampleRateHz: Int?,
        channelCount: Int?,
        verifiedAtMs: Long,
    ): Int

    @Query("UPDATE audio_assets SET integrityState = :integrityState, formatValidationState = :validationState, verifiedAtMs = :verifiedAtMs WHERE assetId = :assetId")
    suspend fun updateAssetIntegrity(
        assetId: String,
        integrityState: String,
        validationState: String,
        verifiedAtMs: Long,
    ): Int

    @Query("SELECT * FROM audio_derivations WHERE recordingId = :recordingId AND profileId = :profileId AND sourceSha256 = :sourceSha256 LIMIT 1")
    suspend fun findDerivation(
        recordingId: String,
        profileId: String,
        sourceSha256: String,
    ): AudioDerivationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDerivation(derivation: AudioDerivationEntity)

    @Query("SELECT * FROM audio_derivations WHERE profileId = :profileId AND state IN (:states)")
    suspend fun findDerivationsByStates(
        profileId: String,
        states: List<String>,
    ): List<AudioDerivationEntity>

    @Query("UPDATE audio_derivations SET state = :failedState, updatedAtMs = :updatedAtMs, completedAtMs = :updatedAtMs, errorCode = :errorCode, errorDetail = :errorDetail WHERE profileId = :profileId AND state IN (:activeStates)")
    suspend fun failActiveDerivations(
        profileId: String,
        activeStates: List<String>,
        failedState: String,
        updatedAtMs: Long,
        errorCode: String,
        errorDetail: String,
    ): Int

    @Query("UPDATE recordings SET displayName = :displayName, updatedAtMs = :updatedAtMs WHERE id = :recordingId")
    suspend fun rename(recordingId: String, displayName: String, updatedAtMs: Long): Int

    @Query("UPDATE recordings SET state = :state, updatedAtMs = :updatedAtMs WHERE id = :recordingId")
    suspend fun updateState(recordingId: String, state: String, updatedAtMs: Long): Int

    @Query("DELETE FROM recordings WHERE id = :recordingId")
    suspend fun deleteRecording(recordingId: String): Int

    @Transaction
    @Query("SELECT * FROM recordings WHERE state = :state")
    suspend fun findByState(state: String): List<RecordingWithAssets>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: LibraryMetaEntity)

    @Query("SELECT value FROM library_meta WHERE key = :key LIMIT 1")
    suspend fun readMeta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMigrationDiagnostic(diagnostic: MigrationDiagnosticEntity): Long

    @Query("SELECT * FROM migration_diagnostics ORDER BY observedAtMs DESC")
    suspend fun migrationDiagnostics(): List<MigrationDiagnosticEntity>
}
