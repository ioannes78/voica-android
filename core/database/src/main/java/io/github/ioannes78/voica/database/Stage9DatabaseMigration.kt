package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_2_3_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_2_3_SQL.forEach(connection::execSQL)
        }
    }

internal val MIGRATION_2_3_SQL =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `diarization_runs` (
            `id` TEXT NOT NULL,
            `recordingId` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `sourceCanonicalAssetId` TEXT NOT NULL,
            `sourceCanonicalSha256` TEXT NOT NULL,
            `canonicalProfileId` TEXT NOT NULL,
            `totalSampleCount` INTEGER NOT NULL,
            `pipelineVersion` INTEGER NOT NULL,
            `runtimeId` TEXT NOT NULL,
            `runtimeVersion` TEXT NOT NULL,
            `vadModelId` TEXT NOT NULL,
            `vadModelVersion` TEXT NOT NULL,
            `vadModelRevision` INTEGER NOT NULL,
            `segmentationModelId` TEXT NOT NULL,
            `segmentationModelVersion` TEXT NOT NULL,
            `segmentationModelRevision` INTEGER NOT NULL,
            `embeddingModelId` TEXT NOT NULL,
            `embeddingModelVersion` TEXT NOT NULL,
            `embeddingModelRevision` INTEGER NOT NULL,
            `modelManifestDigest` TEXT NOT NULL,
            `configSnapshot` TEXT NOT NULL,
            `createdAtMs` INTEGER NOT NULL,
            `startedAtMs` INTEGER,
            `updatedAtMs` INTEGER NOT NULL,
            `completedAtMs` INTEGER,
            `errorCode` TEXT,
            `errorMessage` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_diarization_runs_recordingId` ON `diarization_runs` (`recordingId`)",
        "CREATE INDEX IF NOT EXISTS `index_diarization_runs_state` ON `diarization_runs` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_diarization_runs_recordingId_createdAtMs` ON `diarization_runs` (`recordingId`, `createdAtMs`)",
        """
        CREATE TABLE IF NOT EXISTS `diarization_speakers` (
            `id` TEXT NOT NULL,
            `diarizationRunId` TEXT NOT NULL,
            `speakerOrdinal` INTEGER NOT NULL,
            `displayName` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`diarizationRunId`) REFERENCES `diarization_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_diarization_speakers_diarizationRunId` ON `diarization_speakers` (`diarizationRunId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_diarization_speakers_diarizationRunId_speakerOrdinal` ON `diarization_speakers` (`diarizationRunId`, `speakerOrdinal`)",
        """
        CREATE TABLE IF NOT EXISTS `speaker_turns` (
            `id` TEXT NOT NULL,
            `diarizationRunId` TEXT NOT NULL,
            `turnIndex` INTEGER NOT NULL,
            `speakerId` TEXT NOT NULL,
            `startSampleIndex` INTEGER NOT NULL,
            `endSampleIndexExclusive` INTEGER NOT NULL,
            `confidence` REAL,
            `overlap` INTEGER NOT NULL,
            `overlapMetadata` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`diarizationRunId`) REFERENCES `diarization_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`speakerId`) REFERENCES `diarization_speakers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_speaker_turns_diarizationRunId` ON `speaker_turns` (`diarizationRunId`)",
        "CREATE INDEX IF NOT EXISTS `index_speaker_turns_speakerId` ON `speaker_turns` (`speakerId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_speaker_turns_diarizationRunId_turnIndex` ON `speaker_turns` (`diarizationRunId`, `turnIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_speaker_turns_diarizationRunId_startSampleIndex` ON `speaker_turns` (`diarizationRunId`, `startSampleIndex`)",
        """
        CREATE TABLE IF NOT EXISTS `transcript_speaker_alignments` (
            `id` TEXT NOT NULL,
            `transcriptionId` TEXT NOT NULL,
            `diarizationRunId` TEXT NOT NULL,
            `alignmentVersion` INTEGER NOT NULL,
            `configSnapshot` TEXT NOT NULL,
            `state` TEXT NOT NULL,
            `createdAtMs` INTEGER NOT NULL,
            `updatedAtMs` INTEGER NOT NULL,
            `completedAtMs` INTEGER,
            `errorCode` TEXT,
            `errorMessage` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`transcriptionId`) REFERENCES `transcriptions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`diarizationRunId`) REFERENCES `diarization_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_alignments_transcriptionId` ON `transcript_speaker_alignments` (`transcriptionId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_alignments_diarizationRunId` ON `transcript_speaker_alignments` (`diarizationRunId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_alignments_transcriptionId_diarizationRunId` ON `transcript_speaker_alignments` (`transcriptionId`, `diarizationRunId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_alignments_state` ON `transcript_speaker_alignments` (`state`)",
        """
        CREATE TABLE IF NOT EXISTS `transcript_speaker_spans` (
            `id` TEXT NOT NULL,
            `alignmentId` TEXT NOT NULL,
            `spanIndex` INTEGER NOT NULL,
            `sourceTranscriptSegmentId` TEXT NOT NULL,
            `speakerId` TEXT,
            `startSampleIndex` INTEGER NOT NULL,
            `endSampleIndexExclusive` INTEGER NOT NULL,
            `tokenSource` TEXT,
            `tokenStartIndex` INTEGER,
            `tokenEndIndexExclusive` INTEGER,
            `finalTextStartOffset` INTEGER NOT NULL,
            `finalTextEndOffsetExclusive` INTEGER NOT NULL,
            `assignmentQuality` TEXT NOT NULL,
            `overlap` INTEGER NOT NULL,
            `ambiguous` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`alignmentId`) REFERENCES `transcript_speaker_alignments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceTranscriptSegmentId`) REFERENCES `transcript_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`speakerId`) REFERENCES `diarization_speakers`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_spans_alignmentId` ON `transcript_speaker_spans` (`alignmentId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_spans_sourceTranscriptSegmentId` ON `transcript_speaker_spans` (`sourceTranscriptSegmentId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcript_speaker_spans_speakerId` ON `transcript_speaker_spans` (`speakerId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_speaker_spans_alignmentId_spanIndex` ON `transcript_speaker_spans` (`alignmentId`, `spanIndex`)",
    )
