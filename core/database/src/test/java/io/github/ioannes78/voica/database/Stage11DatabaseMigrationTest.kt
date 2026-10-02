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
class Stage11DatabaseMigrationTest {
    @Test
    fun migration3To4PreservesStage10DataAndCreatesAiSchema() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v3 = helper.createDatabase(TEST_DB_V3, 3)
        try {
            insertStage10Fixture(v3)
        } finally {
            v3.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB_V3,
                4,
                true,
                MIGRATION_3_4,
            )

        try {
            migrated.execSQL("PRAGMA foreign_keys = ON")
            assertEquals(1, migrated.countRows("recordings"))
            assertEquals(1, migrated.countRows("audio_assets"))
            assertEquals(1, migrated.countRows("transcriptions"))
            assertEquals(1, migrated.countRows("transcript_segments"))
            assertEquals(1, migrated.countRows("transcript_tokens"))
            assertEquals(1, migrated.countRows("diarization_runs"))
            assertEquals(1, migrated.countRows("diarization_speakers"))
            assertEquals(1, migrated.countRows("speaker_turns"))
            assertEquals(1, migrated.countRows("transcript_speaker_alignments"))
            assertEquals(1, migrated.countRows("transcript_speaker_spans"))

            assertEquals(0, migrated.countRows("ai_summaries"))
            assertEquals(0, migrated.countRows("ai_custom_templates"))
            assertEquals(0, migrated.countRows("ai_summary_evidence"))
            assertEquals(0, migrated.countRows("ai_summary_chunks"))

            migrated.execSQL(
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
                    'summary-1', 'rec-1', 'tx-1', 'TRANSCRIPT_TEXT', 'SMART',
                    NULL, NULL, 'provider-1',
                    'Test Provider', 'https://example.invalid/v1', 'test-model',
                    1, 1, 'MEETING',
                    0.9, '{"schemaVersion":1}', 'summary',
                    'COMPLETED', 4000, 4000, 4001, 4001,
                    NULL, NULL, '{}',
                    '{"totalTokens":12}', 'align-1', '{"transcriptionId":"tx-1"}'
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO ai_summary_evidence (
                    id, aiSummaryId, summaryItemId, sourceRef,
                    sourceKind, sourceId, startSampleIndex,
                    endSampleIndexExclusive, speakerId, assignmentQuality
                ) VALUES (
                    'evidence-1', 'summary-1', 'item-1', 'S00001',
                    'SPEAKER_SPAN', 'span-1', 0, 32000,
                    'speaker-1', 'ASSIGNED'
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO ai_summary_chunks (
                    id, aiSummaryId, level, chunkIndex,
                    sourceStartOrdinal, sourceEndOrdinalExclusive,
                    startSampleIndex, endSampleIndexExclusive,
                    inputDigest, status, attempt, structuredResultJson,
                    errorCode, sanitizedErrorMessage, updatedAtMs
                ) VALUES (
                    'chunk-1', 'summary-1', 0, 0,
                    0, 1, 0, 32000,
                    'digest', 'COMPLETED', 1, '{"ok":true}',
                    NULL, NULL, 4001
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO ai_custom_templates (
                    id, name, schemaVersion, sectionsConfigJson,
                    focus, userInstruction, createdAtMs, updatedAtMs
                ) VALUES (
                    'template-1', '测试模板', 1, '{}',
                    NULL, NULL, 4000, 4000
                )
                """.trimIndent(),
            )

            assertEquals(1, migrated.countRows("ai_summaries"))
            assertEquals(1, migrated.countRows("ai_summary_evidence"))
            assertEquals(1, migrated.countRows("ai_summary_chunks"))
            assertEquals(1, migrated.countRows("ai_custom_templates"))

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-1'")
            assertEquals(0, migrated.countRows("ai_summaries"))
            assertEquals(0, migrated.countRows("ai_summary_evidence"))
            assertEquals(0, migrated.countRows("ai_summary_chunks"))
            assertEquals(1, migrated.countRows("ai_custom_templates"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migration1To4PreservesLegacyRecordingThroughCompleteChain() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v1 = helper.createDatabase(TEST_DB_CHAIN, 1)
        try {
            v1.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, downloadedAtMs, createdAtMs,
                    updatedAtMs, state
                ) VALUES (
                    'legacy-rec', 'DEVICE_DOWNLOAD', 'legacy-remote', 'AA:BB:CC:DD:EE:FF',
                    'legacy.wav', 'Legacy Recording', '2026-09-01T10:00:00',
                    1234, 1000, 1000, 1001, 'ACTIVE'
                )
                """.trimIndent(),
            )
        } finally {
            v1.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB_CHAIN,
                4,
                true,
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
            )

        try {
            migrated.query(
                "SELECT displayName FROM recordings WHERE id = 'legacy-rec'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy Recording", cursor.getString(0))
            }
            assertEquals(0, migrated.countRows("transcriptions"))
            assertEquals(0, migrated.countRows("diarization_runs"))
            assertEquals(0, migrated.countRows("ai_summaries"))
            assertEquals(0, migrated.countRows("ai_summary_evidence"))
            assertEquals(0, migrated.countRows("ai_summary_chunks"))
        } finally {
            migrated.close()
        }
    }

    private fun insertStage10Fixture(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO recordings (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, downloadedAtMs, createdAtMs,
                updatedAtMs, state
            ) VALUES (
                'rec-1', 'DEVICE_DOWNLOAD', 'remote-1', 'AA:BB:CC:DD:EE:FF',
                'note.wav', 'Stage 10 Recording', '2026-10-02T10:00:00',
                2000, 1000, 1000, 1001, 'ACTIVE'
            )
            """.trimIndent(),
        )
        db.execSQL(
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
        db.execSQL(
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
        db.execSQL(
            """
            INSERT INTO transcript_segments (
                id, transcriptionId, segmentIndex, startSampleIndex,
                endSampleIndexExclusive, firstPassRawText, secondPassRawText,
                finalText, detectedLanguage, confidence
            ) VALUES (
                'seg-1', 'tx-1', 0, 0, 32000,
                '你好世界', NULL, '你好，世界。', 'zh', 0.9
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO transcript_tokens (
                id, transcriptSegmentId, tokenIndex, text,
                startSampleIndex, endSampleIndexExclusive, source
            ) VALUES (
                'token-1', 'seg-1', 0, '你好', 0, 16000, 'FIRST_PASS'
            )
            """.trimIndent(),
        )
        db.execSQL(
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
                '{}', 3000, 3000, 3001, 3001, NULL, NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO diarization_speakers (
                id, diarizationRunId, speakerOrdinal, displayName
            ) VALUES ('speaker-1', 'dia-1', 1, '张三')
            """.trimIndent(),
        )
        db.execSQL(
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
        db.execSQL(
            """
            INSERT INTO transcript_speaker_alignments (
                id, transcriptionId, diarizationRunId, alignmentVersion,
                configSnapshot, state, createdAtMs, updatedAtMs,
                completedAtMs, errorCode, errorMessage
            ) VALUES (
                'align-1', 'tx-1', 'dia-1', 1,
                '{}', 'COMPLETED', 3100, 3101, 3101, NULL, NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO transcript_speaker_spans (
                id, alignmentId, spanIndex, sourceTranscriptSegmentId,
                speakerId, startSampleIndex, endSampleIndexExclusive,
                tokenSource, tokenStartIndex, tokenEndIndexExclusive,
                finalTextStartOffset, finalTextEndOffsetExclusive,
                assignmentQuality, overlap, ambiguous
            ) VALUES (
                'span-1', 'align-1', 0, 'seg-1',
                'speaker-1', 0, 32000,
                'FIRST_PASS', 0, 1,
                0, 6, 'ASSIGNED', 0, 0
            )
            """.trimIndent(),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countRows(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB_V3 = "voica-stage11-migration-v3-test"
        const val TEST_DB_CHAIN = "voica-stage11-migration-chain-test"
    }
}
