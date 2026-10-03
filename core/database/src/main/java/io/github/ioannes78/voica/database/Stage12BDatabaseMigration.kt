package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_4_5: Migration =
    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_4_5_SQL.forEach(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            MIGRATION_4_5_SQL.forEach(connection::execSQL)
        }
    }

private val STAGE12B_RECORDING_GRAPH_TABLES =
    listOf(
        "recordings",
        "audio_assets",
        "audio_derivations",
        "transcriptions",
        "transcript_segments",
        "transcript_tokens",
        "diarization_runs",
        "diarization_speakers",
        "speaker_turns",
        "transcript_speaker_alignments",
        "transcript_speaker_spans",
        "ai_summaries",
        "ai_summary_evidence",
        "ai_summary_chunks",
    )

internal val MIGRATION_4_5_SQL =
    buildList {
        // Rebuilding recordings is required because Stage 12B makes device-only
        // source fields nullable. Keep the entire dependent graph in TEMP tables
        // so the deliberate parent delete cannot destroy historical ASR,
        // diarization/alignment, or AI-summary data.
        STAGE12B_RECORDING_GRAPH_TABLES.forEach { table ->
            add("DROP TABLE IF EXISTS `_stage12b_backup_$table`")
            add(
                "CREATE TEMP TABLE `_stage12b_backup_$table` AS " +
                    "SELECT * FROM `$table`",
            )
        }

        // Empty every FK descendant through the existing ON DELETE actions before
        // replacing the parent table. This keeps foreign_keys enabled throughout
        // the migration instead of weakening integrity checks.
        add("DELETE FROM `recordings`")
        add("DROP TABLE `recordings`")

        add(
            """
            CREATE TABLE IF NOT EXISTS `recordings` (
                `id` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `sourceRemoteIdentity` TEXT,
                `sourceDeviceAddress` TEXT,
                `originalFilename` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `recordedAtLocalIso` TEXT,
                `deviceReportedDurationMs` INTEGER,
                `mediaDurationMs` INTEGER,
                `downloadedAtMs` INTEGER,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        add(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_recordings_sourceRemoteIdentity` ON " +
                "`recordings` (`sourceRemoteIdentity`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_recordedAtLocalIso` " +
                "ON `recordings` (`recordedAtLocalIso`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_downloadedAtMs` " +
                "ON `recordings` (`downloadedAtMs`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_createdAtMs` " +
                "ON `recordings` (`createdAtMs`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_updatedAtMs` " +
                "ON `recordings` (`updatedAtMs`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_sourceType` " +
                "ON `recordings` (`sourceType`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recordings_state` " +
                "ON `recordings` (`state`)",
        )

        add(
            """
            INSERT INTO `recordings` (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            )
            SELECT
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, NULL, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            FROM `_stage12b_backup_recordings`
            """.trimIndent(),
        )

        // Restore the existing graph in FK-topological order.
        add("INSERT INTO `audio_assets` SELECT * FROM `_stage12b_backup_audio_assets`")
        add(
            "INSERT INTO `audio_derivations` " +
                "SELECT * FROM `_stage12b_backup_audio_derivations`",
        )
        add(
            "INSERT INTO `transcriptions` " +
                "SELECT * FROM `_stage12b_backup_transcriptions`",
        )
        add(
            "INSERT INTO `transcript_segments` " +
                "SELECT * FROM `_stage12b_backup_transcript_segments`",
        )
        add(
            "INSERT INTO `transcript_tokens` " +
                "SELECT * FROM `_stage12b_backup_transcript_tokens`",
        )
        add(
            "INSERT INTO `diarization_runs` " +
                "SELECT * FROM `_stage12b_backup_diarization_runs`",
        )
        add(
            "INSERT INTO `diarization_speakers` " +
                "SELECT * FROM `_stage12b_backup_diarization_speakers`",
        )
        add(
            "INSERT INTO `speaker_turns` " +
                "SELECT * FROM `_stage12b_backup_speaker_turns`",
        )
        add(
            "INSERT INTO `transcript_speaker_alignments` " +
                "SELECT * FROM `_stage12b_backup_transcript_speaker_alignments`",
        )
        add(
            "INSERT INTO `transcript_speaker_spans` " +
                "SELECT * FROM `_stage12b_backup_transcript_speaker_spans`",
        )
        add(
            "INSERT INTO `ai_summaries` " +
                "SELECT * FROM `_stage12b_backup_ai_summaries`",
        )
        add(
            "INSERT INTO `ai_summary_evidence` " +
                "SELECT * FROM `_stage12b_backup_ai_summary_evidence`",
        )
        add(
            "INSERT INTO `ai_summary_chunks` " +
                "SELECT * FROM `_stage12b_backup_ai_summary_chunks`",
        )

        add(
            """
            CREATE TABLE IF NOT EXISTS `recording_import_provenance` (
                `recordingId` TEXT NOT NULL,
                `importedAtMs` INTEGER NOT NULL,
                `originalDisplayName` TEXT NOT NULL,
                `sourceMimeType` TEXT,
                `sourceSizeBytes` INTEGER NOT NULL,
                `providerAuthority` TEXT,
                `sourceLastModifiedMs` INTEGER,
                PRIMARY KEY(`recordingId`),
                FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_import_provenance_importedAtMs` " +
                "ON `recording_import_provenance` (`importedAtMs`)",
        )

        add(
            """
            CREATE TABLE IF NOT EXISTS `recording_folders` (
                `folderId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`folderId`)
            )
            """.trimIndent(),
        )
        add(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recording_folders_name` " +
                "ON `recording_folders` (`name`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_folders_updatedAtMs` " +
                "ON `recording_folders` (`updatedAtMs`)",
        )

        add(
            """
            CREATE TABLE IF NOT EXISTS `recording_user_metadata` (
                `recordingId` TEXT NOT NULL,
                `folderId` TEXT,
                `isFavorite` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`recordingId`),
                FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`folderId`) REFERENCES `recording_folders`(`folderId`)
                    ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_user_metadata_folderId` " +
                "ON `recording_user_metadata` (`folderId`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_user_metadata_isFavorite` " +
                "ON `recording_user_metadata` (`isFavorite`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_user_metadata_updatedAtMs` " +
                "ON `recording_user_metadata` (`updatedAtMs`)",
        )
        add(
            """
            INSERT INTO `recording_user_metadata` (
                recordingId, folderId, isFavorite, updatedAtMs
            )
            SELECT id, NULL, 0, updatedAtMs
            FROM `recordings`
            """.trimIndent(),
        )

        add(
            """
            CREATE TABLE IF NOT EXISTS `recording_tags` (
                `tagId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                PRIMARY KEY(`tagId`)
            )
            """.trimIndent(),
        )
        add(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recording_tags_name` " +
                "ON `recording_tags` (`name`)",
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_tags_updatedAtMs` " +
                "ON `recording_tags` (`updatedAtMs`)",
        )

        add(
            """
            CREATE TABLE IF NOT EXISTS `recording_tag_cross_refs` (
                `recordingId` TEXT NOT NULL,
                `tagId` TEXT NOT NULL,
                PRIMARY KEY(`recordingId`, `tagId`),
                FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`tagId`) REFERENCES `recording_tags`(`tagId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        add(
            "CREATE INDEX IF NOT EXISTS `index_recording_tag_cross_refs_tagId` " +
                "ON `recording_tag_cross_refs` (`tagId`)",
        )

        STAGE12B_RECORDING_GRAPH_TABLES.forEach { table ->
            add("DROP TABLE `_stage12b_backup_$table`")
        }
    }
