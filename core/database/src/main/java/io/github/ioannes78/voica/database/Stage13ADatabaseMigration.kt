package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_6_7: Migration =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_6_7_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_6_7_SQL.forEach(connection::execSQL)
        }
    }

internal val MIGRATION_6_7_SQL =
    listOf(
        "ALTER TABLE transcriptions ADD COLUMN vadModelRevision INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE transcriptions ADD COLUMN firstPassAsrModelRevision INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE transcriptions ADD COLUMN secondPassAsrModelRevision INTEGER",
        "ALTER TABLE transcriptions ADD COLUMN punctuationModelRevision INTEGER",
        "ALTER TABLE transcriptions ADD COLUMN configSnapshotSchemaVersion INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE transcriptions ADD COLUMN requestedConfigSnapshot TEXT NOT NULL DEFAULT '{}'",
        "ALTER TABLE transcriptions ADD COLUMN effectiveConfigSnapshot TEXT NOT NULL DEFAULT '{}'",
        """
        UPDATE transcriptions
        SET secondPassAsrModelRevision = 1
        WHERE secondPassAsrModelId IS NOT NULL
        """.trimIndent(),
        """
        UPDATE transcriptions
        SET punctuationModelRevision = 1
        WHERE punctuationModelId IS NOT NULL
        """.trimIndent(),
        """
        UPDATE transcriptions
        SET requestedConfigSnapshot = configSnapshot,
            effectiveConfigSnapshot = configSnapshot
        """.trimIndent(),
    )
