package io.github.ioannes78.voica.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineSummaryLongMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            VoicaDatabase::class.java,
        )

    @Test
    fun migration16To17PreservesExistingSummaryAndAddsCascadeCheckpoints() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        helper.createDatabase(TEST_DB, 16).apply {
            execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'recording', 'LOCAL_IMPORT', NULL, NULL,
                    'x.wav', 'x', NULL,
                    NULL, NULL, NULL,
                    1, 1, 'ACTIVE'
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO ai_summaries (
                    id, recordingId, transcriptionId, inputMode, mode, model,
                    promptVersion, resultSchemaVersion, contentType,
                    classificationConfidence, structuredPayloadJson, displayText,
                    status, createdAtMs, startedAtMs, updatedAtMs, completedAtMs,
                    errorCode, sanitizedErrorMessage, alignmentIdSnapshot,
                    sourceLineageSnapshot
                ) VALUES (
                    'summary', 'recording', NULL, 'TRANSCRIPT_TEXT', 'SMART', 'legacy-provider',
                    110000, 1, NULL,
                    NULL, NULL, 'legacy display',
                    'COMPLETED', 1, NULL, 2, 2,
                    NULL, NULL, NULL,
                    '{}'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 17, true, MIGRATION_16_17)
        migrated.query("SELECT displayText FROM ai_summaries WHERE id = 'summary'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("legacy display", cursor.getString(0))
        }

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            """
            INSERT INTO offline_summary_tasks (
                aiSummaryId, pipelineVersion, plannerVersion, mapPromptVersion,
                reducePromptVersion, finalPromptVersion, intermediateSchemaVersion,
                inputContentDigest, route, directPromptEstimatedTokens, status,
                currentLevel, totalLeafChunks, completedLeafChunks, createdAtMs, updatedAtMs
            ) VALUES (
                'summary', 173001, 173001, 173101,
                173201, 173301, 1,
                '${"a".repeat(64)}', 'LONG', 3000, 'MAPPING',
                0, 1, 0, 3, 3
            )
            """.trimIndent(),
        )
        migrated.execSQL(
            """
            INSERT INTO offline_summary_nodes (
                id, aiSummaryId, nodeType, level, ordinal,
                sourceStartUnitOrdinal, sourceEndUnitOrdinalExclusive,
                inputNodeIdsJson, sourceRefsJson, evidenceRefsJson, inputDigest,
                estimatedPromptTokens, status, attemptCount, validatedPayloadJson,
                errorCode, sanitizedErrorMessage, createdAtMs, updatedAtMs, completedAtMs
            ) VALUES (
                'node-1', 'summary', 'MAP', 0, 0,
                0, 1,
                '[]', '["U00001"]', '[]', '${"b".repeat(64)}',
                1200, 'PENDING', 0, NULL,
                NULL, NULL, 3, 3, NULL
            )
            """.trimIndent(),
        )

        assertEquals(1, scalarCount(migrated, "offline_summary_tasks"))
        assertEquals(1, scalarCount(migrated, "offline_summary_nodes"))

        migrated.execSQL("DELETE FROM ai_summaries WHERE id = 'summary'")

        assertEquals(0, scalarCount(migrated, "offline_summary_tasks"))
        assertEquals(0, scalarCount(migrated, "offline_summary_nodes"))
        migrated.close()
    }

    private fun scalarCount(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "stage17-3-migration.db"
    }
}
