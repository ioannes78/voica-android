package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingEntity::class,
        AudioAssetEntity::class,
        AudioDerivationEntity::class,
        LibraryMetaEntity::class,
        MigrationDiagnosticEntity::class,
        RecordingImportProvenanceEntity::class,
        FolderEntity::class,
        RecordingUserMetadataEntity::class,
        TagEntity::class,
        RecordingTagCrossRef::class,
        TranscriptionEntity::class,
        TranscriptSegmentEntity::class,
        TranscriptTokenEntity::class,
        DiarizationRunEntity::class,
        DiarizationSpeakerEntity::class,
        SpeakerTurnEntity::class,
        TranscriptSpeakerAlignmentEntity::class,
        TranscriptSpeakerSpanEntity::class,
        AiSummaryEntity::class,
        AiCustomTemplateEntity::class,
        AiSummaryEvidenceEntity::class,
        AiSummaryChunkEntity::class,
        TranscriptionUserMetadataEntity::class,
        TranscriptionRevisionEntity::class,
        TranscriptionRevisionParagraphEntity::class,
        AiSummaryUserMetadataEntity::class,
        AiSummaryRevisionEntity::class,
        RecordingContentSelectionEntity::class,
        RecordingCandidateAttentionEntity::class,
        SearchDocumentEntity::class,
        SearchDocumentFtsEntity::class,
        SearchIndexStateEntity::class,
        RealtimeTranscriptionRunEntity::class,
        RealtimeMediaSegmentEntity::class,
        RealtimeUtteranceEntity::class,
        RealtimeTimelineMarkerEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class VoicaDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun diarizationDao(): DiarizationDao

    abstract fun aiSummaryDao(): AiSummaryDao

    abstract fun aiSummaryOwnershipDao(): AiSummaryOwnershipDao

    abstract fun stage12cContentDao(): Stage12CContentDao

    abstract fun stage13B5Qa4Dao(): Stage13B5Qa4Dao

    abstract fun searchDao(): SearchDao

    abstract fun realtimeTranscriptionDao(): RealtimeTranscriptionDao

    companion object {
        const val DATABASE_NAME = "voica-recordings.db"

        fun create(context: Context): VoicaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VoicaDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_12_13)
                .addCallback(
                    object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            repairPhoneCanonicalIdentityLineage(db)
                        }
                    },
                )
                .build()
    }
}

/**
 * Repairs Stage 16A.1 QA candidates that registered a verified phone CANONICAL_WAV before the
 * identity-lineage contract was added. This is a data reconciliation only: schema v13 is unchanged.
 */
internal fun repairPhoneCanonicalIdentityLineage(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        INSERT OR IGNORE INTO audio_derivations (
            recordingId,
            profileId,
            sourceSha256,
            sourceAssetId,
            outputAssetId,
            pipelineVersion,
            state,
            startedAtMs,
            updatedAtMs,
            completedAtMs,
            errorCode,
            errorDetail
        )
        SELECT
            r.id,
            '$PHONE_CANONICAL_PROFILE_ID',
            a.sha256,
            a.assetId,
            a.assetId,
            $PHONE_CANONICAL_PIPELINE_VERSION,
            '${AudioDerivationState.READY}',
            COALESCE(a.verifiedAtMs, a.createdAtMs),
            COALESCE(a.verifiedAtMs, a.createdAtMs),
            COALESCE(a.verifiedAtMs, a.createdAtMs),
            NULL,
            NULL
        FROM recordings AS r
        INNER JOIN audio_assets AS a ON a.recordingId = r.id
        WHERE r.sourceType = '${RecordingSourceType.PHONE_MICROPHONE}'
          AND r.state = '${RecordingState.ACTIVE}'
          AND a.role = '${AudioAssetRole.CANONICAL_WAV}'
          AND a.integrityState = '${AudioIntegrityState.VERIFIED}'
          AND a.formatValidationState = '${AudioValidationState.VALID}'
          AND UPPER(a.container) = 'WAV'
          AND UPPER(COALESCE(a.codec, '')) = 'PCM'
          AND UPPER(COALESCE(a.sampleFormat, '')) = 'PCM16_LE'
          AND a.sampleRateHz = 16000
          AND a.channelCount = 1
        """.trimIndent(),
    )
}
