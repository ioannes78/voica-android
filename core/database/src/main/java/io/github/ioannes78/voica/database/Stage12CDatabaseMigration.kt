package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

val MIGRATION_5_6: Migration =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            runStage12CMigration(db::execSQL)
        }

        override fun migrate(connection: SQLiteConnection) {
            runStage12CMigration(connection::execSQL)
        }
    }

private inline fun runStage12CMigration(execSql: (String) -> Unit) {
    MIGRATION_5_6_SQL.forEachIndexed { index, sql ->
        try {
            execSql(sql)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Stage 12C migration statement #$index failed: $sql",
                error,
            )
        }
    }
}

internal val MIGRATION_5_6_SQL =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS transcription_user_metadata (
            transcriptionId TEXT NOT NULL,
            displayName TEXT,
            currentRevisionId TEXT,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(transcriptionId),
            FOREIGN KEY(transcriptionId) REFERENCES transcriptions(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_transcription_user_metadata_currentRevisionId ON transcription_user_metadata (currentRevisionId)",
        "CREATE INDEX IF NOT EXISTS index_transcription_user_metadata_updatedAtMs ON transcription_user_metadata (updatedAtMs)",
        """
        INSERT OR IGNORE INTO transcription_user_metadata (
            transcriptionId, displayName, currentRevisionId, updatedAtMs
        )
        SELECT id, NULL, NULL, updatedAtMs FROM transcriptions
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS transcription_revisions (
            id TEXT NOT NULL,
            transcriptionId TEXT NOT NULL,
            revisionNumber INTEGER NOT NULL,
            parentRevisionId TEXT,
            createdAtMs INTEGER NOT NULL,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(transcriptionId) REFERENCES transcriptions(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_transcription_revisions_transcriptionId ON transcription_revisions (transcriptionId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_transcription_revisions_transcriptionId_revisionNumber ON transcription_revisions (transcriptionId, revisionNumber)",
        "CREATE INDEX IF NOT EXISTS index_transcription_revisions_createdAtMs ON transcription_revisions (createdAtMs)",
        """
        CREATE TABLE IF NOT EXISTS transcription_revision_paragraphs (
            id TEXT NOT NULL,
            revisionId TEXT NOT NULL,
            paragraphIndex INTEGER NOT NULL,
            text TEXT NOT NULL,
            sourceAnchorRefsJson TEXT NOT NULL,
            anchorStartSampleIndex INTEGER,
            anchorEndSampleIndexExclusive INTEGER,
            speakerId TEXT,
            speakerDisplayMode TEXT NOT NULL,
            timingQuality TEXT NOT NULL,
            isUserModified INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(revisionId) REFERENCES transcription_revisions(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_transcription_revision_paragraphs_revisionId ON transcription_revision_paragraphs (revisionId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_transcription_revision_paragraphs_revisionId_paragraphIndex ON transcription_revision_paragraphs (revisionId, paragraphIndex)",
        "CREATE INDEX IF NOT EXISTS index_transcription_revision_paragraphs_anchorStartSampleIndex ON transcription_revision_paragraphs (anchorStartSampleIndex)",
        """
        CREATE TABLE IF NOT EXISTS ai_summary_user_metadata (
            aiSummaryId TEXT NOT NULL,
            currentRevisionId TEXT,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(aiSummaryId),
            FOREIGN KEY(aiSummaryId) REFERENCES ai_summaries(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_ai_summary_user_metadata_currentRevisionId ON ai_summary_user_metadata (currentRevisionId)",
        "CREATE INDEX IF NOT EXISTS index_ai_summary_user_metadata_updatedAtMs ON ai_summary_user_metadata (updatedAtMs)",
        """
        INSERT OR IGNORE INTO ai_summary_user_metadata (
            aiSummaryId, currentRevisionId, updatedAtMs
        )
        SELECT id, NULL, updatedAtMs FROM ai_summaries
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ai_summary_revisions (
            id TEXT NOT NULL,
            aiSummaryId TEXT NOT NULL,
            revisionNumber INTEGER NOT NULL,
            parentRevisionId TEXT,
            revisionSchemaVersion INTEGER NOT NULL,
            revisionPayloadJson TEXT NOT NULL,
            createdAtMs INTEGER NOT NULL,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(aiSummaryId) REFERENCES ai_summaries(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_ai_summary_revisions_aiSummaryId ON ai_summary_revisions (aiSummaryId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_ai_summary_revisions_aiSummaryId_revisionNumber ON ai_summary_revisions (aiSummaryId, revisionNumber)",
        "CREATE INDEX IF NOT EXISTS index_ai_summary_revisions_createdAtMs ON ai_summary_revisions (createdAtMs)",
        """
        CREATE TABLE IF NOT EXISTS recording_content_selection (
            recordingId TEXT NOT NULL,
            currentTranscriptionId TEXT,
            currentAiSummaryId TEXT,
            updatedAtMs INTEGER NOT NULL,
            PRIMARY KEY(recordingId),
            FOREIGN KEY(recordingId) REFERENCES recordings(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_recording_content_selection_currentTranscriptionId ON recording_content_selection (currentTranscriptionId)",
        "CREATE INDEX IF NOT EXISTS index_recording_content_selection_currentAiSummaryId ON recording_content_selection (currentAiSummaryId)",
        "CREATE INDEX IF NOT EXISTS index_recording_content_selection_updatedAtMs ON recording_content_selection (updatedAtMs)",
        """
        CREATE TABLE IF NOT EXISTS search_documents (
            rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            documentId TEXT NOT NULL,
            documentType TEXT NOT NULL,
            recordingId TEXT,
            transcriptionId TEXT,
            revisionId TEXT,
            sourceAnchorId TEXT,
            aiSummaryId TEXT,
            sectionId TEXT,
            itemId TEXT,
            folderId TEXT,
            tagId TEXT,
            displayTitle TEXT NOT NULL,
            displayText TEXT NOT NULL,
            indexTitle TEXT NOT NULL,
            indexBody TEXT NOT NULL,
            updatedAtMs INTEGER NOT NULL,
            FOREIGN KEY(recordingId) REFERENCES recordings(id)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX IF NOT EXISTS index_search_documents_documentId ON search_documents (documentId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_recordingId ON search_documents (recordingId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_documentType ON search_documents (documentType)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_transcriptionId ON search_documents (transcriptionId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_aiSummaryId ON search_documents (aiSummaryId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_folderId ON search_documents (folderId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_tagId ON search_documents (tagId)",
        "CREATE INDEX IF NOT EXISTS index_search_documents_updatedAtMs ON search_documents (updatedAtMs)",
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS search_documents_fts
        USING FTS4(documentId TEXT NOT NULL, indexTitle TEXT NOT NULL, indexBody TEXT NOT NULL, tokenize=unicode61)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS search_index_state (
            id INTEGER NOT NULL,
            status TEXT NOT NULL,
            indexedDocumentCount INTEGER NOT NULL,
            startedAtMs INTEGER,
            updatedAtMs INTEGER NOT NULL,
            completedAtMs INTEGER,
            errorMessage TEXT,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        """
        INSERT OR REPLACE INTO search_index_state (
            id, status, indexedDocumentCount, startedAtMs,
            updatedAtMs, completedAtMs, errorMessage
        )
        VALUES (1, 'REBUILD_REQUIRED', 0, NULL, 0, NULL, NULL)
        """.trimIndent(),
    )
