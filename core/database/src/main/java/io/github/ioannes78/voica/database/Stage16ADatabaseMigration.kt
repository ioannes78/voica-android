package io.github.ioannes78.voica.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_12_13 =
    object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `realtime_transcription_runs` (
                    `id` TEXT NOT NULL,
                    `recordingId` TEXT,
                    `deviceRecordingIdentity` TEXT,
                    `audioSourceType` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `modelVersion` TEXT NOT NULL,
                    `modelRevision` INTEGER NOT NULL,
                    `runtimeId` TEXT NOT NULL,
                    `runtimeVersion` TEXT NOT NULL,
                    `vadModelId` TEXT,
                    `vadModelVersion` TEXT,
                    `vadModelRevision` INTEGER,
                    `punctuationModelId` TEXT,
                    `punctuationModelVersion` TEXT,
                    `punctuationModelRevision` INTEGER,
                    `languageConfig` TEXT NOT NULL,
                    `configSnapshotSchemaVersion` INTEGER NOT NULL,
                    `requestedConfigSnapshot` TEXT NOT NULL,
                    `effectiveConfigSnapshot` TEXT NOT NULL,
                    `modelManifestDigest` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `startedAtMs` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    `endedAtMs` INTEGER,
                    `endReason` TEXT,
                    `errorCode` TEXT,
                    `errorMessage` TEXT,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_transcription_runs_recordingId` ON `realtime_transcription_runs` (`recordingId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_transcription_runs_deviceRecordingIdentity` ON `realtime_transcription_runs` (`deviceRecordingIdentity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_transcription_runs_state` ON `realtime_transcription_runs` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_transcription_runs_startedAtMs` ON `realtime_transcription_runs` (`startedAtMs`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `realtime_media_segments` (
                    `id` TEXT NOT NULL,
                    `runId` TEXT NOT NULL,
                    `segmentIndex` INTEGER NOT NULL,
                    `transportGeneration` INTEGER,
                    `canonicalSessionId` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `startedAtMs` INTEGER NOT NULL,
                    `endedAtMs` INTEGER,
                    `endReason` TEXT,
                    `canonicalSampleCount` INTEGER NOT NULL,
                    `localRelativePath` TEXT,
                    `localFilename` TEXT,
                    `deviceFilename` TEXT,
                    `sizeBytes` INTEGER,
                    `sha256` TEXT,
                    `isCompleteDeviceRecording` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`runId`) REFERENCES `realtime_transcription_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_media_segments_runId` ON `realtime_media_segments` (`runId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_realtime_media_segments_runId_segmentIndex` ON `realtime_media_segments` (`runId`, `segmentIndex`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_realtime_media_segments_runId_canonicalSessionId` ON `realtime_media_segments` (`runId`, `canonicalSessionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_media_segments_state` ON `realtime_media_segments` (`state`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `realtime_utterances` (
                    `id` TEXT NOT NULL,
                    `segmentId` TEXT NOT NULL,
                    `utteranceIndex` INTEGER NOT NULL,
                    `canonicalEpochId` INTEGER NOT NULL,
                    `startSampleIndex` INTEGER NOT NULL,
                    `endSampleIndexExclusive` INTEGER NOT NULL,
                    `rawFinalText` TEXT NOT NULL,
                    `finalText` TEXT NOT NULL,
                    `detectedLanguage` TEXT,
                    `createdAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`segmentId`) REFERENCES `realtime_media_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_utterances_segmentId` ON `realtime_utterances` (`segmentId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_realtime_utterances_segmentId_utteranceIndex` ON `realtime_utterances` (`segmentId`, `utteranceIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_utterances_segmentId_canonicalEpochId_startSampleIndex` ON `realtime_utterances` (`segmentId`, `canonicalEpochId`, `startSampleIndex`)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `realtime_timeline_markers` (
                    `id` TEXT NOT NULL,
                    `runId` TEXT NOT NULL,
                    `segmentId` TEXT,
                    `markerIndex` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `canonicalSessionId` INTEGER,
                    `canonicalEpochId` INTEGER,
                    `sampleIndex` INTEGER,
                    `createdAtMs` INTEGER NOT NULL,
                    `detail` TEXT,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`runId`) REFERENCES `realtime_transcription_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`segmentId`) REFERENCES `realtime_media_segments`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_timeline_markers_runId` ON `realtime_timeline_markers` (`runId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_timeline_markers_segmentId` ON `realtime_timeline_markers` (`segmentId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_realtime_timeline_markers_runId_markerIndex` ON `realtime_timeline_markers` (`runId`, `markerIndex`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_realtime_timeline_markers_type` ON `realtime_timeline_markers` (`type`)")
        }
    }
