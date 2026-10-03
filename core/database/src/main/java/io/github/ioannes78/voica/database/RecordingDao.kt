package io.github.ioannes78.voica.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Transaction
    @Query("SELECT * FROM recordings WHERE state = 'ACTIVE' ORDER BY recordedAtLocalIso DESC, createdAtMs DESC")
    fun observeAll(): Flow<List<RecordingWithAssets>>

    @RawQuery(
        observedEntities = [
            RecordingEntity::class,
            AudioAssetEntity::class,
            RecordingImportProvenanceEntity::class,
            RecordingUserMetadataEntity::class,
            FolderEntity::class,
            TagEntity::class,
            RecordingTagCrossRef::class,
            TranscriptionEntity::class,
            AiSummaryEntity::class,
        ],
    )
    fun observeLibrary(query: SupportSQLiteQuery): Flow<List<RecordingLibraryProjection>>

    @Query("SELECT * FROM recording_folders ORDER BY name COLLATE NOCASE ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM recording_tags ORDER BY name COLLATE NOCASE ASC")
    fun observeTags(): Flow<List<TagEntity>>

    @Transaction
    @Query("SELECT * FROM recordings WHERE id = :recordingId LIMIT 1")
    suspend fun findWithAssets(recordingId: String): RecordingWithAssets?

    @Query("SELECT * FROM recordings WHERE sourceRemoteIdentity = :remoteIdentity LIMIT 1")
    suspend fun findByRemoteIdentity(remoteIdentity: String): RecordingEntity?

    @Query("SELECT * FROM recordings")
    suspend fun allRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM audio_assets WHERE recordingId = :recordingId AND role = :role LIMIT 1")
    suspend fun findAsset(recordingId: String, role: String): AudioAssetEntity?

    @Query(
        """
        SELECT
            r.id AS recordingId,
            r.displayName AS displayName,
            a.role AS role,
            a.relativePath AS relativePath
        FROM audio_assets a
        JOIN recordings r ON r.id = a.recordingId
        WHERE LOWER(a.sha256) = LOWER(:sha256)
          AND a.sizeBytes = :sizeBytes
          AND r.state = 'ACTIVE'
        ORDER BY r.createdAtMs DESC
        """
    )
    suspend fun findAudioDuplicates(
        sha256: String,
        sizeBytes: Long,
    ): List<AudioDuplicateMatch>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordingIgnore(recording: RecordingEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAssetIgnore(asset: AudioAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserMetadataIgnore(metadata: RecordingUserMetadataEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportProvenance(provenance: RecordingImportProvenanceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecordingTagCrossRefs(crossRefs: List<RecordingTagCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(asset: AudioAssetEntity)

    @Query("UPDATE audio_assets SET integrityState = :integrityState, formatValidationState = :validationState, container = :container, codec = :codec, sampleFormat = :sampleFormat, sampleRateHz = :sampleRateHz, channelCount = :channelCount, verifiedAtMs = :verifiedAtMs WHERE assetId = :assetId")
    suspend fun updateAssetValidation(
        assetId: String,
        integrityState: String,
        validationState: String,
        container: String,
        codec: String?,
        sampleFormat: String?,
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

    @Query("UPDATE recording_user_metadata SET isFavorite = :favorite, updatedAtMs = :updatedAtMs WHERE recordingId IN (:recordingIds)")
    suspend fun setFavorite(
        recordingIds: List<String>,
        favorite: Boolean,
        updatedAtMs: Long,
    ): Int

    @Query("UPDATE recording_user_metadata SET folderId = :folderId, updatedAtMs = :updatedAtMs WHERE recordingId IN (:recordingIds)")
    suspend fun moveToFolder(
        recordingIds: List<String>,
        folderId: String?,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM recording_tag_cross_refs WHERE recordingId IN (:recordingIds) AND tagId = :tagId")
    suspend fun removeTagFromRecordings(recordingIds: List<String>, tagId: String): Int

    @Query("SELECT * FROM recording_folders WHERE folderId = :folderId LIMIT 1")
    suspend fun findFolder(folderId: String): FolderEntity?

    @Query("SELECT * FROM recording_folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findFolderByName(name: String): FolderEntity?

    @Query("SELECT * FROM recording_tags WHERE tagId = :tagId LIMIT 1")
    suspend fun findTag(tagId: String): TagEntity?

    @Query("SELECT * FROM recording_tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findTagByName(name: String): TagEntity?

    @Query("UPDATE recording_folders SET name = :name, updatedAtMs = :updatedAtMs WHERE folderId = :folderId")
    suspend fun renameFolder(folderId: String, name: String, updatedAtMs: Long): Int

    @Query("UPDATE recording_tags SET name = :name, updatedAtMs = :updatedAtMs WHERE tagId = :tagId")
    suspend fun renameTag(tagId: String, name: String, updatedAtMs: Long): Int

    @Query("DELETE FROM recording_folders WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: String): Int

    @Query("DELETE FROM recording_tags WHERE tagId = :tagId")
    suspend fun deleteTag(tagId: String): Int

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
