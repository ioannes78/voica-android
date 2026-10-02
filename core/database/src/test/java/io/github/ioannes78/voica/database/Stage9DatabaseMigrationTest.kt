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
class Stage9DatabaseMigrationTest {
    @Test
    fun migration2To3PreservesStage8DataAndCreatesDiarizationSchema() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v2 = helper.createDatabase(TEST_DB, 2)
        try {
            v2.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, downloadedAtMs, createdAtMs,
                    updatedAtMs, state
                ) VALUES (
                    'rec-1', 'DEVICE_DOWNLOAD', 'remote-1', 'AA:BB:CC:DD:EE:FF',
                    'note.wav', 'Stage 8 Recording', '2026-10-02T09:00:00',
                    2000, 1000, 1000, 1001, 'ACTIVE'
                )
                """.trimIndent(),
            )
            v2.execSQL(
                """
                INSERT INTO audio_assets (
                    assetId, recordingId, role, relativePath, container, codec,
                    sampleFormat, sampleRateHz, channelCount, sizeBytes, sha256,
                    integrityState, formatValidationState, createdAtMs, verifiedAtMs
                ) VALUES (
                    'asset-canonical', 'rec-1', 'CANONICAL_WAV',
                    'canonical/rec-1.wav', 'WAV', 'PCM', 'PCM16_LE',
                    16000, 1, 64044,
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'VERIFIED', 'VALID', 1002, 1003
                )
                """.trimIndent(),
            )
            v2.execSQL(
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
                    'tx-fast', 'rec-1', 'FAST', 'COMPLETED', 'asset-canonical',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 32000,
                    1, 'sherpa-onnx', '1.13.8', 'silero-vad-int8', '2025-07-11',
                    'zipformer-small-bilingual', '2023-02-16',
                    NULL, NULL, 'ct-transformer-zh-en-int8', '2024-04-12',
                    'auto', '{}',
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    2000, 2000, 2001, 2001, NULL, NULL
                )
                """.trimIndent(),
            )
            v2.execSQL(
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
                    'tx-hq', 'rec-1', 'HIGH_QUALITY', 'COMPLETED', 'asset-canonical',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 32000,
                    1, 'sherpa-onnx', '1.13.8', 'silero-vad-int8', '2025-07-11',
                    'zipformer-small-bilingual', '2023-02-16',
                    'sensevoice-2024-int8', '2024-07-17',
                    'ct-transformer-zh-en-int8', '2024-04-12',
                    'auto', '{}',
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    2100, 2100, 2101, 2101, NULL, NULL
                )
                """.trimIndent(),
            )
            v2.execSQL(
                """
                INSERT INTO transcript_segments (
                    id, transcriptionId, segmentIndex, startSampleIndex,
                    endSampleIndexExclusive, firstPassRawText, secondPassRawText,
                    finalText, detectedLanguage, confidence
                ) VALUES (
                    'seg-1', 'tx-fast', 0, 0, 32000,
                    '你好世界', NULL, '你好，世界。', 'zh', 0.9
                )
                """.trimIndent(),
            )
            v2.execSQL(
                """
                INSERT INTO transcript_tokens (
                    id, transcriptSegmentId, tokenIndex, text,
                    startSampleIndex, endSampleIndexExclusive, source
                ) VALUES (
                    'token-1', 'seg-1', 0, '你好', 0, 16000, 'FIRST_PASS'
                )
                """.trimIndent(),
            )
        } finally {
            v2.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                3,
                true,
                MIGRATION_2_3,
            )

        try {
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.query("PRAGMA foreign_keys").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }

            assertEquals(1, migrated.countRows("recordings"))
            assertEquals(1, migrated.countRows("audio_assets"))
            assertEquals(2, migrated.countRows("transcriptions"))
            assertEquals(1, migrated.countRows("transcript_segments"))
            assertEquals(1, migrated.countRows("transcript_tokens"))

            assertEquals(0, migrated.countRows("diarization_runs"))
            assertEquals(0, migrated.countRows("diarization_speakers"))
            assertEquals(0, migrated.countRows("speaker_turns"))
            assertEquals(0, migrated.countRows("transcript_speaker_alignments"))
            assertEquals(0, migrated.countRows("transcript_speaker_spans"))

            migrated.execSQL(
                """
                INSERT INTO diarization_runs (
                    id, recordingId, state, sourceCanonicalAssetId,
                    sourceCanonicalSha256, canonicalProfileId, totalSampleCount,
                    pipelineVersion, runtimeId, runtimeVersion,
                    vadModelId, vadModelVersion, vadModelRevision,
                    segmentationModelId, segmentationModelVersion, segmentationModelRevision,
                    embeddingModelId, embeddingModelVersion, embeddingModelRevision,
                    modelManifestDigest, configSnapshot,
                    createdAtMs, startedAtMs, updatedAtMs, completedAtMs,
                    errorCode, errorMessage
                ) VALUES (
                    'dia-1', 'rec-1', 'COMPLETED', 'asset-canonical',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'CANONICAL_PCM16_16000_MONO_WAV_V1', 32000,
                    1, 'sherpa-onnx', '1.13.8',
                    'silero-vad-int8', '2025-07-11', 1,
                    'pyannote-segmentation-3-int8', '3.0', 1,
                    'eres2net-base-zh-16k', 'stage9', 1,
                    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                    '{"chunkSamples":960000,"overlapSamples":160000}',
                    3000, 3000, 3001, 3001, NULL, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO diarization_speakers (
                    id, diarizationRunId, speakerOrdinal, displayName
                ) VALUES (
                    'speaker-1', 'dia-1', 1, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO speaker_turns (
                    id, diarizationRunId, turnIndex, speakerId,
                    startSampleIndex, endSampleIndexExclusive,
                    confidence, overlap, overlapMetadata
                ) VALUES (
                    'turn-1', 'dia-1', 0, 'speaker-1',
                    0, 32000, 0.95, 0, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO transcript_speaker_alignments (
                    id, transcriptionId, diarizationRunId, alignmentVersion,
                    configSnapshot, state, createdAtMs, updatedAtMs,
                    completedAtMs, errorCode, errorMessage
                ) VALUES (
                    'align-fast', 'tx-fast', 'dia-1', 1,
                    '{}', 'COMPLETED', 3100, 3101, 3101, NULL, NULL
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO transcript_speaker_spans (
                    id, alignmentId, spanIndex, sourceTranscriptSegmentId,
                    speakerId, startSampleIndex, endSampleIndexExclusive,
                    tokenSource, tokenStartIndex, tokenEndIndexExclusive,
                    finalTextStartOffset, finalTextEndOffsetExclusive,
                    assignmentQuality, overlap, ambiguous
                ) VALUES (
                    'span-1', 'align-fast', 0, 'seg-1',
                    'speaker-1', 0, 32000,
                    'FIRST_PASS', 0, 1,
                    0, 6, 'ASSIGNED', 0, 0
                )
                """.trimIndent(),
            )

            assertEquals(1, migrated.countRows("diarization_runs"))
            assertEquals(1, migrated.countRows("diarization_speakers"))
            assertEquals(1, migrated.countRows("speaker_turns"))
            assertEquals(1, migrated.countRows("transcript_speaker_alignments"))
            assertEquals(1, migrated.countRows("transcript_speaker_spans"))

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-1'")

            assertEquals(0, migrated.countRows("recordings"))
            assertEquals(0, migrated.countRows("audio_assets"))
            assertEquals(0, migrated.countRows("transcriptions"))
            assertEquals(0, migrated.countRows("transcript_segments"))
            assertEquals(0, migrated.countRows("transcript_tokens"))
            assertEquals(0, migrated.countRows("diarization_runs"))
            assertEquals(0, migrated.countRows("diarization_speakers"))
            assertEquals(0, migrated.countRows("speaker_turns"))
            assertEquals(0, migrated.countRows("transcript_speaker_alignments"))
            assertEquals(0, migrated.countRows("transcript_speaker_spans"))
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
        const val TEST_DB = "voica-stage9-migration-test"
    }
}
