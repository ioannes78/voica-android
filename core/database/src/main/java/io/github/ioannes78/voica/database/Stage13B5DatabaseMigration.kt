package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_7_8: Migration =
    object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_7_8_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_7_8_SQL.forEach(connection::execSQL)
        }
    }

val MIGRATION_8_9: Migration =
    object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_8_9_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_8_9_SQL.forEach(connection::execSQL)
        }
    }

val MIGRATION_9_10: Migration =
    object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_9_10_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_9_10_SQL.forEach(connection::execSQL)
        }
    }

internal val MIGRATION_7_8_SQL =
    listOf(
        "ALTER TABLE ai_summaries ADD COLUMN executionGeneration INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE ai_summaries ADD COLUMN remoteDispatchState TEXT NOT NULL DEFAULT 'NONE'",
        "ALTER TABLE ai_summaries ADD COLUMN remoteRequestId TEXT",
        "ALTER TABLE ai_summaries ADD COLUMN remoteStepKind TEXT",
        "ALTER TABLE ai_summaries ADD COLUMN remoteStepKey TEXT",
        "ALTER TABLE ai_summaries ADD COLUMN remoteCallOrdinal INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE ai_summaries ADD COLUMN remoteStartedAtMs INTEGER",
        "ALTER TABLE ai_summaries ADD COLUMN terminalAcknowledgedAtMs INTEGER",
        "ALTER TABLE ai_summaries ADD COLUMN retryOfSummaryId TEXT",
    )

internal val MIGRATION_8_9_SQL =
    listOf(
        "ALTER TABLE ai_summaries ADD COLUMN ownerTaskId INTEGER",
    )

internal val MIGRATION_9_10_SQL =
    listOf(
        "ALTER TABLE transcriptions ADD COLUMN terminalAcknowledgedAtMs INTEGER",
        "ALTER TABLE recording_content_selection ADD COLUMN dismissedTranscriptionCandidateId TEXT",
        "ALTER TABLE recording_content_selection ADD COLUMN dismissedAiSummaryCandidateId TEXT",
    )
