package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_3_4: Migration =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_3_4_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_3_4_SQL.forEach(connection::execSQL)
        }
    }

internal val MIGRATION_3_4_SQL =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS `ai_summaries` (
            `id` TEXT NOT NULL,
            `recordingId` TEXT NOT NULL,
            `transcriptionId` TEXT,
            `inputMode` TEXT NOT NULL,
            `mode` TEXT NOT NULL,
            `templateId` TEXT,
            `templateSnapshot` TEXT,
            `providerProfileId` TEXT NOT NULL,
            `providerNameSnapshot` TEXT NOT NULL,
            `baseUrlSnapshot` TEXT NOT NULL,
            `model` TEXT NOT NULL,
            `promptVersion` INTEGER NOT NULL,
            `resultSchemaVersion` INTEGER NOT NULL,
            `contentType` TEXT,
            `classificationConfidence` REAL,
            `structuredPayloadJson` TEXT,
            `displayText` TEXT,
            `status` TEXT NOT NULL,
            `createdAtMs` INTEGER NOT NULL,
            `startedAtMs` INTEGER,
            `updatedAtMs` INTEGER NOT NULL,
            `completedAtMs` INTEGER,
            `errorCode` TEXT,
            `sanitizedErrorMessage` TEXT,
            `requestConfigSnapshot` TEXT NOT NULL,
            `usageSnapshot` TEXT,
            `alignmentIdSnapshot` TEXT,
            `sourceLineageSnapshot` TEXT NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`transcriptionId`) REFERENCES `transcriptions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_ai_summaries_recordingId` ON `ai_summaries` (`recordingId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summaries_transcriptionId` ON `ai_summaries` (`transcriptionId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summaries_status` ON `ai_summaries` (`status`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summaries_recordingId_createdAtMs` ON `ai_summaries` (`recordingId`, `createdAtMs`)",
        """
        CREATE TABLE IF NOT EXISTS `ai_custom_templates` (
            `id` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `schemaVersion` INTEGER NOT NULL,
            `sectionsConfigJson` TEXT NOT NULL,
            `focus` TEXT,
            `userInstruction` TEXT,
            `createdAtMs` INTEGER NOT NULL,
            `updatedAtMs` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_ai_custom_templates_name` ON `ai_custom_templates` (`name`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_custom_templates_updatedAtMs` ON `ai_custom_templates` (`updatedAtMs`)",
        """
        CREATE TABLE IF NOT EXISTS `ai_summary_evidence` (
            `id` TEXT NOT NULL,
            `aiSummaryId` TEXT NOT NULL,
            `summaryItemId` TEXT NOT NULL,
            `sourceRef` TEXT NOT NULL,
            `sourceKind` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `startSampleIndex` INTEGER NOT NULL,
            `endSampleIndexExclusive` INTEGER NOT NULL,
            `speakerId` TEXT,
            `assignmentQuality` TEXT,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`aiSummaryId`) REFERENCES `ai_summaries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_ai_summary_evidence_aiSummaryId` ON `ai_summary_evidence` (`aiSummaryId`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summary_evidence_summaryItemId` ON `ai_summary_evidence` (`summaryItemId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_summary_evidence_aiSummaryId_summaryItemId_sourceRef` ON `ai_summary_evidence` (`aiSummaryId`, `summaryItemId`, `sourceRef`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summary_evidence_aiSummaryId_startSampleIndex` ON `ai_summary_evidence` (`aiSummaryId`, `startSampleIndex`)",
        """
        CREATE TABLE IF NOT EXISTS `ai_summary_chunks` (
            `id` TEXT NOT NULL,
            `aiSummaryId` TEXT NOT NULL,
            `level` INTEGER NOT NULL,
            `chunkIndex` INTEGER NOT NULL,
            `sourceStartOrdinal` INTEGER NOT NULL,
            `sourceEndOrdinalExclusive` INTEGER NOT NULL,
            `startSampleIndex` INTEGER NOT NULL,
            `endSampleIndexExclusive` INTEGER NOT NULL,
            `inputDigest` TEXT NOT NULL,
            `status` TEXT NOT NULL,
            `attempt` INTEGER NOT NULL,
            `structuredResultJson` TEXT,
            `errorCode` TEXT,
            `sanitizedErrorMessage` TEXT,
            `updatedAtMs` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`aiSummaryId`) REFERENCES `ai_summaries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_ai_summary_chunks_aiSummaryId` ON `ai_summary_chunks` (`aiSummaryId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_summary_chunks_aiSummaryId_level_chunkIndex` ON `ai_summary_chunks` (`aiSummaryId`, `level`, `chunkIndex`)",
        "CREATE INDEX IF NOT EXISTS `index_ai_summary_chunks_aiSummaryId_status` ON `ai_summary_chunks` (`aiSummaryId`, `status`)",
    )
