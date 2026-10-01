package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_1_2_SQL.forEach(connection::execSQL)
        }
    }

internal val MIGRATION_1_2_SQL =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `transcriptions` (
            `id` TEXT NOT NULL,
            `recordingId` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
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
            `firstPassAsrModelId` TEXT NOT NULL,
            `firstPassAsrModelVersion` TEXT NOT NULL,
            `secondPassAsrModelId` TEXT,
            `secondPassAsrModelVersion` TEXT,
            `punctuationModelId` TEXT,
            `punctuationModelVersion` TEXT,
            `languageConfig` TEXT NOT NULL,
            `configSnapshot` TEXT NOT NULL,
            `modelManifestDigest` TEXT NOT NULL,
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
        "CREATE INDEX IF NOT EXISTS `index_transcriptions_recordingId` ON `transcriptions` (`recordingId`)",
        "CREATE INDEX IF NOT EXISTS `index_transcriptions_state` ON `transcriptions` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_transcriptions_recordingId_createdAtMs` ON `transcriptions` (`recordingId`, `createdAtMs`)",
        """
        CREATE TABLE IF NOT EXISTS `transcript_segments` (
            `id` TEXT NOT NULL,
            `transcriptionId` TEXT NOT NULL,
            `segmentIndex` INTEGER NOT NULL,
            `startSampleIndex` INTEGER NOT NULL,
            `endSampleIndexExclusive` INTEGER NOT NULL,
            `firstPassRawText` TEXT NOT NULL,
            `secondPassRawText` TEXT,
            `finalText` TEXT NOT NULL,
            `detectedLanguage` TEXT,
            `confidence` REAL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`transcriptionId`) REFERENCES `transcriptions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_transcript_segments_transcriptionId` ON `transcript_segments` (`transcriptionId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_segments_transcriptionId_segmentIndex` ON `transcript_segments` (`transcriptionId`, `segmentIndex`)",
        """
        CREATE TABLE IF NOT EXISTS `transcript_tokens` (
            `id` TEXT NOT NULL,
            `transcriptSegmentId` TEXT NOT NULL,
            `tokenIndex` INTEGER NOT NULL,
            `text` TEXT NOT NULL,
            `startSampleIndex` INTEGER,
            `endSampleIndexExclusive` INTEGER,
            `source` TEXT NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`transcriptSegmentId`) REFERENCES `transcript_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_transcript_tokens_transcriptSegmentId` ON `transcript_tokens` (`transcriptSegmentId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcript_tokens_transcriptSegmentId_source_tokenIndex` ON `transcript_tokens` (`transcriptSegmentId`, `source`, `tokenIndex`)",
    )
