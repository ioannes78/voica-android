package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_13_14 =
    object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `timeline_annotations` (
                    `id` TEXT NOT NULL,
                    `recordingId` TEXT,
                    `ownershipState` TEXT NOT NULL,
                    `pendingOwnerKind` TEXT,
                    `pendingOwnerId` TEXT,
                    `anchorDomain` TEXT NOT NULL,
                    `anchorSampleIndex` INTEGER NOT NULL,
                    `anchorCanonicalAssetIdSnapshot` TEXT,
                    `anchorCanonicalSha256Snapshot` TEXT,
                    `anchorCanonicalProfileIdSnapshot` TEXT,
                    `anchorRealtimeRunIdSnapshot` TEXT,
                    `anchorRealtimeSegmentIdSnapshot` TEXT,
                    `anchorRealtimeSegmentIndexSnapshot` INTEGER,
                    `anchorCanonicalSessionIdSnapshot` INTEGER,
                    `anchorCanonicalEpochIdSnapshot` INTEGER,
                    `noteText` TEXT,
                    `sourceTextSnapshot` TEXT,
                    `sourceTranscriptionIdSnapshot` TEXT,
                    `sourceRevisionIdSnapshot` TEXT,
                    `sourceTranscriptSegmentIdSnapshot` TEXT,
                    `sourceTimingQualitySnapshot` TEXT,
                    `speakerLabelSnapshot` TEXT,
                    `createdAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_recordingId` ON `timeline_annotations` (`recordingId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_recordingId_anchorDomain_anchorSampleIndex` ON `timeline_annotations` (`recordingId`, `anchorDomain`, `anchorSampleIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_ownershipState` ON `timeline_annotations` (`ownershipState`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_pendingOwnerKind_pendingOwnerId` ON `timeline_annotations` (`pendingOwnerKind`, `pendingOwnerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_recordingId_createdAtMs` ON `timeline_annotations` (`recordingId`, `createdAtMs`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotations_recordingId_anchorRealtimeSegmentIdSnapshot_anchorSampleIndex` ON `timeline_annotations` (`recordingId`, `anchorRealtimeSegmentIdSnapshot`, `anchorSampleIndex`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `timeline_annotation_attachments` (
                    `id` TEXT NOT NULL,
                    `annotationId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `relativePath` TEXT NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `sizeBytes` INTEGER NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `widthPx` INTEGER,
                    `heightPx` INTEGER,
                    `lifecycleState` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`annotationId`) REFERENCES `timeline_annotations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotation_attachments_annotationId` ON `timeline_annotation_attachments` (`annotationId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_timeline_annotation_attachments_lifecycleState` ON `timeline_annotation_attachments` (`lifecycleState`)")
        }
    }
