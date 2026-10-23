package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        AiSummaryEvidenceEntity::class,
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
    version = 15,
    exportSchema = true,
)
abstract class VoicaDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    abstract fun transcriptionDao(): TranscriptionDao

    abstract fun diarizationDao(): DiarizationDao

    abstract fun aiSummaryDao(): AiSummaryDao

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
            ).build()
    }
}
