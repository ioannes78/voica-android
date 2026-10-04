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
class Stage12CDatabaseMigrationTest {
    @Test
    fun migration5To6PreservesContentGraphAndMarksSearchForRebuild() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )

        val v5 = helper.createDatabase(TEST_DB, 5)
        try {
            insertV5Fixture(v5)
        } finally {
            v5.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                6,
                true,
                MIGRATION_5_6,
            )

        try {
            migrated.execSQL("PRAGMA foreign_keys = ON")

            migrated.query(
                """
                SELECT displayName, mediaDurationMs, state
                FROM recordings
                WHERE id = 'rec-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("客户访谈", cursor.getString(0))
                assertEquals(120000L, cursor.getLong(1))
                assertEquals("ACTIVE", cursor.getString(2))
            }

            migrated.query(
                """
                SELECT startSampleIndex, endSampleIndexExclusive, finalText
                FROM transcript_segments
                WHERE id = 'seg-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(16000L, cursor.getLong(0))
                assertEquals(64000L, cursor.getLong(1))
                assertEquals("供应链风险需要继续跟进。", cursor.getString(2))
            }

            migrated.query(
                """
                SELECT providerNameSnapshot, model, sourceLineageSnapshot
                FROM ai_summaries
                WHERE id = 'summary-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Test Provider", cursor.getString(0))
                assertEquals("test-model", cursor.getString(1))
                assertEquals(
                    "{\"transcriptionId\":\"tx-12c\"}",
                    cursor.getString(2),
                )
            }

            migrated.query(
                """
                SELECT displayName, currentRevisionId
                FROM transcription_user_metadata
                WHERE transcriptionId = 'tx-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }

            migrated.query(
                """
                SELECT currentRevisionId
                FROM ai_summary_user_metadata
                WHERE aiSummaryId = 'summary-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }

            migrated.query(
                """
                SELECT status, indexedDocumentCount
                FROM search_index_state
                WHERE id = 1
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("REBUILD_REQUIRED", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
            }

            assertEquals(0, migrated.countRows("transcription_revisions"))
            assertEquals(0, migrated.countRows("transcription_revision_paragraphs"))
            assertEquals(0, migrated.countRows("ai_summary_revisions"))
            assertEquals(0, migrated.countRows("recording_content_selection"))
            assertEquals(0, migrated.countRows("search_documents"))

            migrated.execSQL(
                """
                INSERT INTO transcription_revisions (
                    id, transcriptionId, revisionNumber, parentRevisionId,
                    createdAtMs, updatedAtMs
                ) VALUES (
                    'revision-12c', 'tx-12c', 1, NULL, 7000, 7000
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO transcription_revision_paragraphs (
                    id, revisionId, paragraphIndex, text, sourceAnchorRefsJson,
                    anchorStartSampleIndex, anchorEndSampleIndexExclusive,
                    speakerId, speakerDisplayMode, timingQuality, isUserModified
                ) VALUES (
                    'paragraph-12c', 'revision-12c', 0,
                    '供应链风险需要继续跟进。',
                    '["SEGMENT:seg-12c"]',
                    16000, 64000, NULL, 'INHERIT', 'ANCHORED', 1
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                UPDATE transcription_user_metadata
                SET currentRevisionId = 'revision-12c', updatedAtMs = 7000
                WHERE transcriptionId = 'tx-12c'
                """.trimIndent(),
            )

            migrated.query(
                """
                SELECT currentRevisionId
                FROM transcription_user_metadata
                WHERE transcriptionId = 'tx-12c'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("revision-12c", cursor.getString(0))
            }

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-12c'")
            assertEquals(0, migrated.countRows("transcriptions"))
            assertEquals(0, migrated.countRows("ai_summaries"))
            assertEquals(0, migrated.countRows("transcription_user_metadata"))
            assertEquals(0, migrated.countRows("transcription_revisions"))
            assertEquals(0, migrated.countRows("transcription_revision_paragraphs"))
        } finally {
            migrated.close()
        }
    }

    private fun insertV5Fixture(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            """
            INSERT INTO recordings (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            ) VALUES (
                'rec-12c', 'LOCAL_IMPORT', NULL, NULL,
                'meeting.wav', '客户访谈', '2026-10-03T10:00:00',
                120000, 120000, NULL, 1000, 1000, 'ACTIVE'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_folders (
                folderId, name, createdAtMs, updatedAtMs
            ) VALUES ('folder-12c', '客户', 1000, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_user_metadata (
                recordingId, folderId, isFavorite, updatedAtMs
            ) VALUES ('rec-12c', 'folder-12c', 1, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_tags (
                tagId, name, createdAtMs, updatedAtMs
            ) VALUES ('tag-12c', '重点', 1000, 1000)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_tag_cross_refs (recordingId, tagId)
            VALUES ('rec-12c', 'tag-12c')
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
                'tx-12c', 'rec-12c', 'FAST', 'COMPLETED', 'canonical-12c',
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                'CANONICAL_PCM16_16000_MONO_WAV_V1', 1920000,
                1, 'sherpa-onnx', '1.13.8', 'silero-vad-int8', '1',
                'zipformer-small-bilingual', '1',
                NULL, NULL, 'ct-transformer-zh-en-int8', '1',
                'auto', '{}',
                'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
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
                'seg-12c', 'tx-12c', 0, 16000, 64000,
                '供应链风险需要继续跟进', NULL,
                '供应链风险需要继续跟进。', 'zh', 0.91
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO transcript_tokens (
                id, transcriptSegmentId, tokenIndex, text,
                startSampleIndex, endSampleIndexExclusive, source
            ) VALUES (
                'token-12c', 'seg-12c', 0, '供应链',
                16000, 32000, 'FIRST_PASS'
            )
            """.trimIndent(),
        )
        db.execSQL(
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
                'summary-12c', 'rec-12c', 'tx-12c',
                'TRANSCRIPT_TEXT', 'SMART', NULL, NULL, 'provider-12c',
                'Test Provider', 'https://example.invalid/v1', 'test-model',
                1, 1, 'MEETING', 0.9,
                '{"schemaVersion":1}', '供应链风险需要继续跟进。',
                'COMPLETED', 4000, 4000, 4001, 4001,
                NULL, NULL, '{}', NULL, NULL,
                '{"transcriptionId":"tx-12c"}'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO ai_summary_evidence (
                id, aiSummaryId, summaryItemId, sourceRef,
                sourceKind, sourceId, startSampleIndex,
                endSampleIndexExclusive, speakerId, assignmentQuality
            ) VALUES (
                'evidence-12c', 'summary-12c', 'item-12c', 'S00001',
                'TRANSCRIPT_SEGMENT', 'seg-12c', 16000, 64000,
                NULL, 'ASSIGNED'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO ai_summary_chunks (
                id, aiSummaryId, level, chunkIndex,
                sourceStartOrdinal, sourceEndOrdinalExclusive,
                startSampleIndex, endSampleIndexExclusive,
                inputDigest, status, attempt, structuredResultJson,
                errorCode, sanitizedErrorMessage, updatedAtMs
            ) VALUES (
                'chunk-12c', 'summary-12c', 0, 0,
                0, 1, 16000, 64000,
                'digest', 'COMPLETED', 1, '{"ok":true}',
                NULL, NULL, 4001
            )
            """.trimIndent(),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.countRows(table: String): Int =
        query("SELECT COUNT(*) FROM " + table).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "voica-stage12c-migration-v5-test"
    }
}
