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
class Stage16AMigrationTest {
    @Test
    fun migration12To13AddsRealtimeDurableDomainAndPreservesCascade() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )
        val v12 = helper.createDatabase(TEST_DB, 12)
        try {
            v12.execSQL("PRAGMA foreign_keys = ON")
            v12.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-stage16a', 'DEVICE_DOWNLOAD', 'device-rec-1', 'AA:BB:CC:DD:EE:FF',
                    'note20261012-120000.opus', 'Stage16A', '2026-10-12T12:00:00',
                    1000, 1000, 1, 1, 1, 'ACTIVE'
                )
                """.trimIndent(),
            )
        } finally {
            v12.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                13,
                true,
                MIGRATION_12_13,
            )

        try {
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                """
                INSERT INTO realtime_transcription_runs (
                    id, recordingId, deviceRecordingIdentity, audioSourceType, state,
                    modelId, modelVersion, modelRevision, runtimeId, runtimeVersion,
                    vadModelId, vadModelVersion, vadModelRevision,
                    punctuationModelId, punctuationModelVersion, punctuationModelRevision,
                    languageConfig, configSnapshotSchemaVersion,
                    requestedConfigSnapshot, effectiveConfigSnapshot, modelManifestDigest,
                    createdAtMs, startedAtMs, updatedAtMs, endedAtMs,
                    endReason, errorCode, errorMessage
                ) VALUES (
                    'run-1', 'rec-stage16a', 'device-rec-1', 'RECORDER_CARD', 'ACTIVE',
                    'zipformer-small-bilingual', '2023-02-16', 1, 'sherpa-onnx', '1.13.8',
                    'silero-vad-int8', '1', 1,
                    'ct-transformer-zh-en-int8', '1', 1,
                    'zh-en', 1, '{}', '{}', 'manifest-digest',
                    2, 2, 2, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO realtime_media_segments (
                    id, runId, segmentIndex, transportGeneration, canonicalSessionId, state,
                    startedAtMs, endedAtMs, endReason, canonicalSampleCount,
                    localRelativePath, localFilename, deviceFilename, sizeBytes, sha256,
                    isCompleteDeviceRecording
                ) VALUES (
                    'seg-1', 'run-1', 0, 9, 101, 'FINALIZED',
                    2, 3, 'STOP', 16000,
                    'realtime/run-1/part01.wav', 'part01.wav', NULL, 32044, 'sha', 1
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO realtime_utterances (
                    id, segmentId, utteranceIndex, canonicalEpochId,
                    startSampleIndex, endSampleIndexExclusive,
                    rawFinalText, finalText, detectedLanguage, createdAtMs
                ) VALUES (
                    'utt-1', 'seg-1', 0, 1,
                    0, 8000, '测试', '测试。', 'zh', 3
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO realtime_timeline_markers (
                    id, runId, segmentId, markerIndex, type,
                    canonicalSessionId, canonicalEpochId, sampleIndex, createdAtMs, detail
                ) VALUES (
                    'mark-1', 'run-1', 'seg-1', 0, 'PAUSE',
                    101, 1, 8000, 3, NULL
                )
                """.trimIndent(),
            )

            migrated.query("SELECT COUNT(*) FROM realtime_transcription_runs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT finalText FROM realtime_utterances WHERE id = 'utt-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("测试。", cursor.getString(0))
            }

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-stage16a'")
            for (table in listOf(
                "realtime_transcription_runs",
                "realtime_media_segments",
                "realtime_utterances",
                "realtime_timeline_markers",
            )) {
                migrated.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("$table must cascade with recording ownership", 0, cursor.getInt(0))
                }
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage16a-12-13-migration-test.db"
    }
}
