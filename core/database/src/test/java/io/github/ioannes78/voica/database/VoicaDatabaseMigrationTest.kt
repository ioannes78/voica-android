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
class VoicaDatabaseMigrationTest {
    @Test
    fun migration1To2PreservesStage7DataAndCreatesTranscriptSchema() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v1 = helper.createDatabase(TEST_DB, 1)
        try {
            v1.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, downloadedAtMs, createdAtMs,
                    updatedAtMs, state
                ) VALUES (
                    'rec-1', 'DEVICE_DOWNLOAD', 'remote-1', 'AA:BB:CC:DD:EE:FF',
                    'note.wav', 'Stage 7 Recording', '2026-10-01T10:00:00',
                    1234, 1000, 1000, 1001, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v1.execSQL(
                """
                INSERT INTO audio_assets (
                    assetId, recordingId, role, relativePath, container, codec,
                    sampleFormat, sampleRateHz, channelCount, sizeBytes, sha256,
                    integrityState, formatValidationState, createdAtMs, verifiedAtMs
                ) VALUES (
                    'asset-canonical', 'rec-1', 'CANONICAL_WAV',
                    'canonical/rec-1.wav', 'WAV', 'PCM', 'PCM16_LE',
                    16000, 1, 32044,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'VERIFIED', 'VALID', 1002, 1003
                )
                """.trimIndent(),
            )
            v1.execSQL(
                """
                INSERT INTO audio_derivations (
                    recordingId, profileId, sourceSha256, sourceAssetId,
                    outputAssetId, pipelineVersion, state, startedAtMs,
                    updatedAtMs, completedAtMs, errorCode, errorDetail
                ) VALUES (
                    'rec-1', 'CANONICAL_PCM16_16000_MONO_WAV_V1',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'asset-device', 'asset-canonical', 1, 'READY',
                    1000, 1003, 1003, NULL, NULL
                )
                """.trimIndent(),
            )
            v1.execSQL(
                """
                INSERT INTO library_meta (key, value, updatedAtMs)
                VALUES ('legacy_stage5_import', '1', 1004)
                """.trimIndent(),
            )
            v1.execSQL(
                """
                INSERT INTO migration_diagnostics (sourcePath, code, detail, observedAtMs)
                VALUES ('legacy/file.wav', 'TEST', 'kept', 1005)
                """.trimIndent(),
            )
        } finally {
            v1.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                2,
                true,
                MIGRATION_1_2,
            )
        try {
            // MigrationTestHelper exposes a raw SupportSQLiteDatabase. Room enables
            // foreign keys on production connections; mirror that here before
            // validating the full recording -> transcription -> segment -> token
            // cascade chain.
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.query("PRAGMA foreign_keys").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            migrated.query(
                """
                SELECT displayName, state
                FROM recordings
                WHERE id = 'rec-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Stage 7 Recording", cursor.getString(0))
                assertEquals("ACTIVE", cursor.getString(1))
            }

            migrated.query(
                """
                SELECT relativePath, sha256, integrityState, formatValidationState
                FROM audio_assets
                WHERE assetId = 'asset-canonical'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("canonical/rec-1.wav", cursor.getString(0))
                assertEquals(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    cursor.getString(1),
                )
                assertEquals("VERIFIED", cursor.getString(2))
                assertEquals("VALID", cursor.getString(3))
            }

            migrated.query(
                """
                SELECT state, outputAssetId
                FROM audio_derivations
                WHERE recordingId = 'rec-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("READY", cursor.getString(0))
                assertEquals("asset-canonical", cursor.getString(1))
            }

            assertEquals(0, migrated.countRows("transcriptions"))
            assertEquals(0, migrated.countRows("transcript_segments"))
            assertEquals(0, migrated.countRows("transcript_tokens"))

            migrated.execSQL(
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
                    'tx-1', 'rec-1', 'FAST', 'COMPLETED', 'asset-canonical',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 16000,
                    1, 'sherpa-onnx', '1.13.8', 'silero-vad', 'builtin',
                    'zipformer-small-bilingual', '2023-02-16',
                    NULL, NULL, 'ct-transformer-zh-en', '2024-04-12',
                    'auto', '{}',
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    2000, 2000, 2001, 2001, NULL, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO transcript_segments (
                    id, transcriptionId, segmentIndex, startSampleIndex,
                    endSampleIndexExclusive, firstPassRawText, secondPassRawText,
                    finalText, detectedLanguage, confidence
                ) VALUES (
                    'seg-1', 'tx-1', 0, 0, 16000,
                    '你好 world', NULL, '你好，world。', 'zh', 0.9
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO transcript_tokens (
                    id, transcriptSegmentId, tokenIndex, text,
                    startSampleIndex, endSampleIndexExclusive, source
                ) VALUES (
                    'token-1', 'seg-1', 0, '你好', 0, 8000, 'FIRST_PASS'
                )
                """.trimIndent(),
            )

            assertEquals(1, migrated.countRows("transcriptions"))
            assertEquals(1, migrated.countRows("transcript_segments"))
            assertEquals(1, migrated.countRows("transcript_tokens"))

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-1'")

            assertEquals(0, migrated.countRows("recordings"))
            assertEquals(0, migrated.countRows("audio_assets"))
            assertEquals(0, migrated.countRows("audio_derivations"))
            assertEquals(0, migrated.countRows("transcriptions"))
            assertEquals(0, migrated.countRows("transcript_segments"))
            assertEquals(0, migrated.countRows("transcript_tokens"))
        } finally {
            migrated.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countRows(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "voica-stage8-migration-test"
    }
}
