package io.github.ioannes78.voica.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage13ADatabaseMigrationTest {
    @Test
    fun migration6To7PreservesTranscriptionAndBackfillsSpeechLineage() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v6 = helper.createDatabase(TEST_DB, 6)
        try {
            v6.execSQL("PRAGMA foreign_keys = ON")
            v6.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-13a', 'LOCAL_IMPORT', NULL, NULL,
                    'meeting.wav', 'Stage 13A migration', '2026-10-04T10:00:00',
                    60000, 60000, NULL, 1000, 1000, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v6.execSQL(
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
                    'tx-13a', 'rec-13a', 'HIGH_QUALITY', 'COMPLETED', 'canonical-13a',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 960000,
                    1, 'sherpa-onnx', '1.13.8', 'silero-vad-int8', '2025-07-11',
                    'zipformer-small-bilingual', '2023-02-16',
                    'sensevoice-2024-int8', '2024-07-17',
                    'ct-transformer-zh-en-int8', '2024-04-12', 'auto',
                    '{"legacy":true}',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    2000, 2000, 2001, 2001, NULL, NULL
                )
                """.trimIndent(),
            )
        } finally {
            v6.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                7,
                true,
                MIGRATION_6_7,
            )

        try {
            migrated.query(
                """
                SELECT vadModelRevision, firstPassAsrModelRevision,
                       secondPassAsrModelRevision, punctuationModelRevision,
                       configSnapshotSchemaVersion,
                       requestedConfigSnapshot, effectiveConfigSnapshot
                FROM transcriptions
                WHERE id = 'tx-13a'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals(1L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals("{\"legacy\":true}", cursor.getString(5))
                assertEquals("{\"legacy\":true}", cursor.getString(6))
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage13a-migration-test.db"
    }
}
