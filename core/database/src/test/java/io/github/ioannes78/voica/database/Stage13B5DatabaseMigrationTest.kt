package io.github.ioannes78.voica.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage13B5DatabaseMigrationTest {
    @Test
    fun migration7To8PreservesSummaryCheckpointsAndAddsDurableExecutionDefaults() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v7 = helper.createDatabase(TEST_DB, 7)
        try {
            v7.execSQL("PRAGMA foreign_keys = ON")
            v7.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-13b5', 'LOCAL_IMPORT', NULL, NULL,
                    'meeting.wav', 'Stage 13B.5 migration', '2026-10-07T12:00:00',
                    60000, 60000, NULL, 1000, 1000, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v7.execSQL(
                """
                INSERT INTO transcriptions (
                    id, recordingId, mode, state, sourceCanonicalAssetId,
                    sourceCanonicalSha256, canonicalProfileId, totalSampleCount,
                    pipelineVersion, runtimeId, runtimeVersion, vadModelId,
                    vadModelVersion, firstPassAsrModelId, firstPassAsrModelVersion,
                    secondPassAsrModelId, secondPassAsrModelVersion,
                    punctuationModelId, punctuationModelVersion, languageConfig,
                    configSnapshot, modelManifestDigest, createdAtMs, startedAtMs,
                    updatedAtMs, completedAtMs, errorCode, errorMessage
                ) VALUES (
                    'tx-13b5', 'rec-13b5', 'FAST', 'COMPLETED', 'canonical-13b5',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 960000,
                    1, 'sherpa-onnx', '1.13.8', 'silero-vad-int8', '2025-07-11',
                    'sensevoice-2024-int8', '2024-07-17',
                    NULL, NULL, 'ct-transformer-zh-en-int8', '2024-04-12', 'auto',
                    '{}',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    2000, 2000, 2001, 2001, NULL, NULL
                )
                """.trimIndent(),
            )
            v7.execSQL(
                """
                INSERT INTO ai_summaries (
                    id, recordingId, transcriptionId, inputMode, mode,
                    templateId, templateSnapshot, providerProfileId,
                    providerNameSnapshot, baseUrlSnapshot, model,
                    promptVersion, resultSchemaVersion, contentType,
                    classificationConfidence, structuredPayloadJson, displayText,
                    status, createdAtMs, startedAtMs, updatedAtMs, completedAtMs,
                    errorCode, sanitizedErrorMessage, requestConfigSnapshot,
                    usageSnapshot, alignmentIdSnapshot, sourceLineageSnapshot
                ) VALUES (
                    'summary-13b5', 'rec-13b5', 'tx-13b5', 'TRANSCRIPT_TEXT', 'SMART',
                    NULL, NULL, 'provider', 'Provider', 'https://example.invalid', 'model',
                    1, 1, NULL, NULL, NULL, NULL,
                    'MAPPING', 3000, 3001, 3002, NULL,
                    NULL, NULL, '{}', NULL, NULL,
                    '{"transcriptionId":"tx-13b5"}'
                )
                """.trimIndent(),
            )
            v7.execSQL(
                """
                INSERT INTO ai_summary_chunks (
                    id, aiSummaryId, level, chunkIndex, sourceStartOrdinal,
                    sourceEndOrdinalExclusive, startSampleIndex, endSampleIndexExclusive,
                    inputDigest, status, attempt, structuredResultJson,
                    errorCode, sanitizedErrorMessage, updatedAtMs
                ) VALUES (
                    'chunk-13b5', 'summary-13b5', 0, 0, 0,
                    1, 0, 16000, 'digest', 'COMPLETED', 1,
                    '{"schemaVersion":1}', NULL, NULL, 3002
                )
                """.trimIndent(),
            )
            v7.execSQL(
                """
                INSERT INTO recording_content_selection (
                    recordingId, currentTranscriptionId, currentAiSummaryId, updatedAtMs
                ) VALUES ('rec-13b5', 'tx-13b5', 'summary-13b5', 3003)
                """.trimIndent(),
            )
        } finally {
            v7.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                8,
                true,
                MIGRATION_7_8,
            )

        try {
            migrated.query(
                """
                SELECT status, executionGeneration, remoteDispatchState,
                       remoteRequestId, remoteStepKind, remoteStepKey,
                       remoteCallOrdinal, remoteStartedAtMs,
                       terminalAcknowledgedAtMs, retryOfSummaryId
                FROM ai_summaries
                WHERE id = 'summary-13b5'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("MAPPING", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("NONE", cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertEquals(0, cursor.getInt(6))
                assertTrue(cursor.isNull(7))
                assertTrue(cursor.isNull(8))
                assertTrue(cursor.isNull(9))
            }

            migrated.query(
                "SELECT structuredResultJson FROM ai_summary_chunks WHERE id = 'chunk-13b5'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"schemaVersion\":1}", cursor.getString(0))
            }

            migrated.query(
                "SELECT currentAiSummaryId FROM recording_content_selection WHERE recordingId = 'rec-13b5'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("summary-13b5", cursor.getString(0))
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage13b5-migration-test.db"
    }
}
