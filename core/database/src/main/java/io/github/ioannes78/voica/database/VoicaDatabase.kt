package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_16_17 =
    object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `offline_summary_tasks` (
                    `aiSummaryId` TEXT NOT NULL,
                    `pipelineVersion` INTEGER NOT NULL,
                    `plannerVersion` INTEGER NOT NULL,
                    `mapPromptVersion` INTEGER NOT NULL,
                    `reducePromptVersion` INTEGER NOT NULL,
                    `finalPromptVersion` INTEGER NOT NULL,
                    `intermediateSchemaVersion` INTEGER NOT NULL,
                    `inputContentDigest` TEXT NOT NULL,
                    `route` TEXT NOT NULL,
                    `directPromptEstimatedTokens` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `currentLevel` INTEGER NOT NULL,
                    `totalLeafChunks` INTEGER NOT NULL,
                    `completedLeafChunks` INTEGER NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`aiSummaryId`),
                    FOREIGN KEY(`aiSummaryId`) REFERENCES `ai_summaries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_offline_summary_tasks_status` " +
                    "ON `offline_summary_tasks` (`status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_offline_summary_tasks_updatedAtMs` " +
                    "ON `offline_summary_tasks` (`updatedAtMs`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `offline_summary_nodes` (
                    `id` TEXT NOT NULL,
                    `aiSummaryId` TEXT NOT NULL,
                    `nodeType` TEXT NOT NULL,
                    `level` INTEGER NOT NULL,
                    `ordinal` INTEGER NOT NULL,
                    `sourceStartUnitOrdinal` INTEGER NOT NULL,
                    `sourceEndUnitOrdinalExclusive` INTEGER NOT NULL,
                    `inputNodeIdsJson` TEXT NOT NULL,
                    `sourceRefsJson` TEXT NOT NULL,
                    `evidenceRefsJson` TEXT NOT NULL,
                    `inputDigest` TEXT NOT NULL,
                    `estimatedPromptTokens` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `attemptCount` INTEGER NOT NULL,
                    `validatedPayloadJson` TEXT,
                    `errorCode` TEXT,
                    `sanitizedErrorMessage` TEXT,
                    `createdAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    `completedAtMs` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`aiSummaryId`) REFERENCES `ai_summaries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_offline_summary_nodes_aiSummaryId` " +
                    "ON `offline_summary_nodes` (`aiSummaryId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_summary_nodes_aiSummaryId_level_ordinal` " +
                    "ON `offline_summary_nodes` (`aiSummaryId`, `level`, `ordinal`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_offline_summary_nodes_aiSummaryId_status` " +
                    "ON `offline_summary_nodes` (`aiSummaryId`, `status`)",
            )
        }
    }

@Database(
    entities = [
        RecordingEntity::class,
        AudioAssetEntity::class,
        AudioDerivationEntity::class,
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
        AiSummaryEvidenceEntity::class,
        OfflineSummaryTaskEntity::class,
        OfflineSummaryNodeEntity::class,
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
        TimelineAnnotationEntity::class,
        TimelineAnnotationAttachmentEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class VoicaDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun diarizationDao(): DiarizationDao

    abstract fun aiSummaryDao(): AiSummaryDao

    abstract fun offlineSummaryLongDao(): OfflineSummaryLongDao

    abstract fun stage12cContentDao(): Stage12CContentDao

    abstract fun stage13B5Qa4Dao(): Stage13B5Qa4Dao

    abstract fun searchDao(): SearchDao

    abstract fun realtimeTranscriptionDao(): RealtimeTranscriptionDao

    abstract fun timelineAnnotationDao(): TimelineAnnotationDao

    companion object {
        const val DATABASE_NAME = "voica-recordings.db"

        fun create(context: Context): VoicaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                VoicaDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_16_17)
                .build()
    }
}
