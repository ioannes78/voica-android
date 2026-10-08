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
class Stage13B5Qa4MigrationTest {
    @Test
    fun migration9To10PreservesTranscriptionAndAddsDurableAttentionState() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v9 = helper.createDatabase(TEST_DB, 9)
        try {
            v9.execSQL("PRAGMA foreign_keys = ON")
            v9.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-qa4', 'LOCAL_IMPORT', NULL, NULL,
                    'qa4.wav', 'QA4', NULL,
                    1000, 1000, NULL, 1, 1, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v9.execSQL(
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
                    'tx-qa4', 'rec-qa4', 'HIGH_QUALITY', 'INTERRUPTED', 'canonical-qa4',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 16000,
                    2, 'sherpa-onnx', '1', 'vad', '1',
                    'asr', '1', NULL, NULL, NULL, NULL, 'auto',
                    '{}',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    2, 2, 3, 3, 'PROCESS_INTERRUPTED', 'interrupted'
                )
                """.trimIndent(),
            )
        } finally {
            v9.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                10,
                true,
                MIGRATION_9_10,
            )

        try {
            migrated.query(
                "SELECT state, terminalAcknowledgedAtMs FROM transcriptions WHERE id = 'tx-qa4'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(TranscriptionStateValue.INTERRUPTED, cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }

            migrated.execSQL(
                """
                INSERT INTO recording_candidate_attention (
                    recordingId, dismissedTranscriptionCandidateId,
                    dismissedAiSummaryCandidateId, updatedAtMs
                ) VALUES ('rec-qa4', 'tx-candidate', 'summary-candidate', 10)
                """.trimIndent(),
            )
            migrated.query(
                """
                SELECT dismissedTranscriptionCandidateId, dismissedAiSummaryCandidateId
                FROM recording_candidate_attention
                WHERE recordingId = 'rec-qa4'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tx-candidate", cursor.getString(0))
                assertEquals("summary-candidate", cursor.getString(1))
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage13b5-qa4-migration-test.db"
    }
}
