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
class Stage13C7R1MigrationTest {
    @Test
    fun migration11To12PreservesDiarizationAndAddsDurableAttentionAcknowledgement() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )
        val v11 = helper.createDatabase(TEST_DB, 11)
        try {
            v11.execSQL("PRAGMA foreign_keys = ON")
            v11.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-c7-r1', 'LOCAL_IMPORT', NULL, NULL,
                    'c7-r1.wav', 'C7-R1', NULL,
                    1000, 1000, NULL, 1, 1, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v11.execSQL(
                """
                INSERT INTO diarization_runs (
                    id, recordingId, state, sourceCanonicalAssetId, sourceCanonicalSha256,
                    canonicalProfileId, totalSampleCount, pipelineVersion, runtimeId, runtimeVersion,
                    vadModelId, vadModelVersion, vadModelRevision,
                    segmentationModelId, segmentationModelVersion, segmentationModelRevision,
                    embeddingModelId, embeddingModelVersion, embeddingModelRevision,
                    modelManifestDigest, configSnapshot, createdAtMs, startedAtMs, updatedAtMs,
                    completedAtMs, errorCode, errorMessage
                ) VALUES (
                    'dia-c7-r1', 'rec-c7-r1', 'INTERRUPTED', 'canonical-c7-r1',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 16000, 2,
                    'sherpa-onnx', '1.13.8',
                    'silero-vad-int8', '1', 1,
                    'pyannote-segmentation-3-int8', '3', 1,
                    'campplus-int8', '1', 1,
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '{}', 2, 2, 3, 3, 'PROCESS_INTERRUPTED', 'interrupted'
                )
                """.trimIndent(),
            )
        } finally {
            v11.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                12,
                true,
                MIGRATION_11_12,
            )

        try {
            migrated.query(
                "SELECT state, terminalAcknowledgedAtMs FROM diarization_runs WHERE id = 'dia-c7-r1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(DiarizationStateValue.INTERRUPTED, cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }

            migrated.query("PRAGMA table_info(transcript_speaker_alignments)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue("terminalAcknowledgedAtMs" in columns)
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage13c-c7-r1-migration-test.db"
    }
}
