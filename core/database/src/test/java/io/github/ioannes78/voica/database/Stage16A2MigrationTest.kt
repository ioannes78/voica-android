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
class Stage16A2MigrationTest {
    @Test
    fun migration13To14AddsRecordingOwnedAnnotationsAndPreservesPendingSessionRows() {
        val helper =
            MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                VoicaDatabase::class.java,
            )
        val v13 = helper.createDatabase(TEST_DB, 13)
        try {
            v13.execSQL("PRAGMA foreign_keys = ON")
            v13.execSQL(
                """
                INSERT INTO recordings (
                    id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                    originalFilename, displayName, recordedAtLocalIso,
                    deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                    createdAtMs, updatedAtMs, state
                ) VALUES (
                    'rec-stage16a2', 'PHONE_MICROPHONE', NULL, NULL,
                    'phone.wav', 'Stage16A2', '2026-10-18T10:00:00',
                    NULL, 1000, 1, 1, 1, 'ACTIVE'
                )
                """.trimIndent(),
            )
        } finally {
            v13.close()
        }

        val migrated =
            helper.runMigrationsAndValidate(
                TEST_DB,
                14,
                true,
                MIGRATION_13_14,
            )

        try {
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL(
                """
                INSERT INTO timeline_annotations (
                    id, recordingId, ownershipState, pendingOwnerKind, pendingOwnerId,
                    anchorDomain, anchorSampleIndex,
                    anchorCanonicalAssetIdSnapshot, anchorCanonicalSha256Snapshot,
                    anchorCanonicalProfileIdSnapshot, anchorRealtimeRunIdSnapshot,
                    anchorRealtimeSegmentIdSnapshot, anchorRealtimeSegmentIndexSnapshot,
                    anchorCanonicalSessionIdSnapshot, anchorCanonicalEpochIdSnapshot,
                    noteText, sourceTextSnapshot, sourceTranscriptionIdSnapshot,
                    sourceRevisionIdSnapshot, sourceTranscriptSegmentIdSnapshot,
                    sourceTimingQualitySnapshot, speakerLabelSnapshot,
                    createdAtMs, updatedAtMs
                ) VALUES (
                    'annotation-attached', 'rec-stage16a2', 'ATTACHED_RECORDING', NULL, NULL,
                    'CANONICAL_RECORDING', 8000,
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    '测试标注', NULL, NULL, NULL, NULL, NULL, NULL,
                    2, 2
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO timeline_annotation_attachments (
                    id, annotationId, kind, source, relativePath, mimeType,
                    sizeBytes, sha256, widthPx, heightPx, lifecycleState,
                    createdAtMs, updatedAtMs
                ) VALUES (
                    'photo-1', 'annotation-attached', 'PHOTO', 'CAMERA',
                    'timeline_annotations/annotation-attached/photo-1.jpg', 'image/jpeg',
                    100, 'sha-photo', 100, 100, 'READY', 2, 2
                )
                """.trimIndent(),
            )
            migrated.execSQL(
                """
                INSERT INTO timeline_annotations (
                    id, recordingId, ownershipState, pendingOwnerKind, pendingOwnerId,
                    anchorDomain, anchorSampleIndex,
                    anchorCanonicalAssetIdSnapshot, anchorCanonicalSha256Snapshot,
                    anchorCanonicalProfileIdSnapshot, anchorRealtimeRunIdSnapshot,
                    anchorRealtimeSegmentIdSnapshot, anchorRealtimeSegmentIndexSnapshot,
                    anchorCanonicalSessionIdSnapshot, anchorCanonicalEpochIdSnapshot,
                    noteText, sourceTextSnapshot, sourceTranscriptionIdSnapshot,
                    sourceRevisionIdSnapshot, sourceTranscriptSegmentIdSnapshot,
                    sourceTimingQualitySnapshot, speakerLabelSnapshot,
                    createdAtMs, updatedAtMs
                ) VALUES (
                    'annotation-pending', NULL, 'PENDING_SESSION', 'PHONE_CAPTURE', 'future-recording-id',
                    'CANONICAL_RECORDING', 4000,
                    NULL, NULL, NULL, NULL, NULL, NULL, 7, 1,
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    3, 3
                )
                """.trimIndent(),
            )

            migrated.query("SELECT COUNT(*) FROM timeline_annotations").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM timeline_annotation_attachments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("PRAGMA index_list('timeline_annotations')").use { cursor ->
                var indexCount = 0
                while (cursor.moveToNext()) indexCount += 1
                assertTrue("timeline_annotations must retain query indices", indexCount >= 6)
            }

            migrated.execSQL("DELETE FROM recordings WHERE id = 'rec-stage16a2'")
            migrated.query("SELECT COUNT(*) FROM timeline_annotations WHERE id = 'annotation-attached'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM timeline_annotation_attachments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM timeline_annotations WHERE id = 'annotation-pending'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pending session annotation must not be owned by an unrelated recording", 1, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val TEST_DB = "stage16a2-13-14-migration-test.db"
    }
}
