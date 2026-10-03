package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryQueryRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: RecordingLibraryRepository
    private lateinit var recordingsRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        recordingsRoot = File(context.cacheDir, "stage12b-library-query-test").apply {
            deleteRecursively()
            mkdirs()
        }
        repository = RecordingLibraryRepository(database, recordingsRoot)
        seed()
    }

    @After
    fun tearDown() {
        database.close()
        recordingsRoot.deleteRecursively()
    }

    @Test
    fun defaultQueryHidesDeletingAndDeduplicatesPhysicalAudioPaths() = runBlocking {
        val rows = repository.observeLibrary(LibraryQueryCriteria()).first()

        assertEquals(setOf("rec-device", "rec-import"), rows.map { it.id }.toSet())
        val device = rows.single { it.id == "rec-device" }
        assertEquals(150L, device.physicalAudioBytes)
        assertEquals(listOf("客户", "重要").toSet(), device.tagNames.toSet())
        assertTrue(device.hasCompletedTranscription)
        assertTrue(device.hasCompletedSummary)
    }

    @Test
    fun combinedFiltersRequireAllSelectedTagsAndCompletedArtifacts() = runBlocking {
        val rows =
            repository.observeLibrary(
                LibraryQueryCriteria(
                    favoriteOnly = true,
                    folderId = "folder-client",
                    tagIds = setOf("tag-client", "tag-important"),
                    sourceTypes = setOf(RecordingSourceType.DEVICE_DOWNLOAD),
                    completedTranscriptionOnly = true,
                    completedSummaryOnly = true,
                ),
            ).first()

        assertEquals(listOf("rec-device"), rows.map { it.id })
    }

    @Test
    fun sizeSortUsesDeduplicatedPhysicalBytes() = runBlocking {
        val rows =
            repository.observeLibrary(
                LibraryQueryCriteria(sort = LibrarySort.SIZE),
            ).first()

        assertEquals(listOf("rec-import", "rec-device"), rows.map { it.id })
        assertEquals(300L, rows.first().physicalAudioBytes)
    }

    @Test
    fun exactDateRangeMatchesRecordingOrLibraryTimestampsInSql() = runBlocking {
        val rows =
            repository.observeLibrary(
                LibraryQueryCriteria(
                    searchDateFromLocalIso = "2026-10-03T00:00",
                    searchDateToLocalIsoExclusive = "2026-10-04T00:00",
                    searchDateFromMs = 900,
                    searchDateToMsExclusive = 1500,
                ),
            ).first()

        assertEquals(listOf("rec-device"), rows.map { it.id })
    }

    @Test
    fun folderAndTagManagementPreservesRecordingsAndRelationships() = runBlocking {
        val folder = repository.createFolder("  项目资料  ")
        val tag = repository.createTag("  高优先  ")

        assertEquals("项目资料", folder.name)
        assertEquals("高优先", tag.name)

        assertEquals(1, repository.moveToFolder(listOf("rec-import"), folder.folderId))
        assertEquals(1, repository.addTag(listOf("rec-import"), tag.tagId))

        val assigned =
            repository.observeLibrary(
                LibraryQueryCriteria(
                    folderId = folder.folderId,
                    tagIds = setOf(tag.tagId),
                ),
            ).first()
        assertEquals(listOf("rec-import"), assigned.map { it.id })
        assertEquals("项目资料", assigned.single().folderName)
        assertTrue("高优先" in assigned.single().tagNames)

        assertTrue(repository.renameFolder(folder.folderId, "归档项目"))
        assertTrue(repository.renameTag(tag.tagId, "重点"))
        val renamed =
            repository.observeLibrary(
                LibraryQueryCriteria(folderId = folder.folderId),
            ).first().single()
        assertEquals("归档项目", renamed.folderName)
        assertTrue("重点" in renamed.tagNames)

        assertTrue(repository.deleteFolder(folder.folderId))
        val uncategorized =
            repository.observeLibrary(
                LibraryQueryCriteria(uncategorizedOnly = true),
            ).first()
        assertTrue(uncategorized.any { it.id == "rec-import" && it.folderId == null })

        assertTrue(repository.deleteTag(tag.tagId))
        val afterTagDelete =
            repository.observeLibrary(
                LibraryQueryCriteria(query = "重点"),
            ).first()
        assertTrue(afterTagDelete.isEmpty())
        assertTrue(
            repository.observeLibrary(LibraryQueryCriteria()).first()
                .any { it.id == "rec-import" },
        )
    }

    @Test
    fun renameFolderAndTagRejectCaseInsensitiveNameConflicts() = runBlocking {
        val folderA = repository.createFolder("研发")
        val folderB = repository.createFolder("销售")
        assertTrue(!repository.renameFolder(folderB.folderId, "研发"))

        val tagA = repository.createTag("内部")
        val tagB = repository.createTag("外部")
        assertTrue(!repository.renameTag(tagB.tagId, "内部"))

        assertEquals(folderA.folderId, repository.createFolder("研发").folderId)
        assertEquals(tagA.tagId, repository.createTag("内部").tagId)
    }

    @Test
    fun searchEscapesSqlWildcardsInsteadOfTreatingThemAsPatterns() = runBlocking {
        val noLiteralPercent =
            repository.observeLibrary(
                LibraryQueryCriteria(query = "%"),
            ).first()
        assertTrue(noLiteralPercent.isEmpty())

        val literal =
            repository.observeLibrary(
                LibraryQueryCriteria(query = "访谈"),
            ).first()
        assertEquals(listOf("rec-device"), literal.map { it.id })
    }

    private fun seed() {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO recordings (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            ) VALUES (
                'rec-device', 'DEVICE_DOWNLOAD', 'remote-device', 'AA:BB',
                'device.wav', '客户访谈', '2026-10-03T10:00:00',
                10000, NULL, 1000, 1000, 1100, 'ACTIVE'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recordings (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            ) VALUES (
                'rec-import', 'LOCAL_IMPORT', NULL, NULL,
                'phone.mp3', '手机录音', NULL,
                NULL, 12000, NULL, 2000, 2100, 'ACTIVE'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recordings (
                id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                originalFilename, displayName, recordedAtLocalIso,
                deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                createdAtMs, updatedAtMs, state
            ) VALUES (
                'rec-deleting', 'DEVICE_DOWNLOAD', 'remote-deleting', 'AA:CC',
                'deleting.wav', '删除中', '2026-10-03T11:00:00',
                5000, NULL, 3000, 3000, 3100, 'DELETING'
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO recording_folders (folderId, name, createdAtMs, updatedAtMs)
            VALUES ('folder-client', '客户资料', 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_user_metadata (
                recordingId, folderId, isFavorite, updatedAtMs
            ) VALUES ('rec-device', 'folder-client', 1, 1100)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_user_metadata (
                recordingId, folderId, isFavorite, updatedAtMs
            ) VALUES ('rec-import', NULL, 0, 2100)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_user_metadata (
                recordingId, folderId, isFavorite, updatedAtMs
            ) VALUES ('rec-deleting', NULL, 0, 3100)
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO recording_tags (tagId, name, createdAtMs, updatedAtMs)
            VALUES ('tag-client', '客户', 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO recording_tags (tagId, name, createdAtMs, updatedAtMs)
            VALUES ('tag-important', '重要', 1, 1)
            """.trimIndent(),
        )
        db.execSQL(
            "INSERT INTO recording_tag_cross_refs (recordingId, tagId) VALUES ('rec-device', 'tag-client')",
        )
        db.execSQL(
            "INSERT INTO recording_tag_cross_refs (recordingId, tagId) VALUES ('rec-device', 'tag-important')",
        )
        db.execSQL(
            "INSERT INTO recording_tag_cross_refs (recordingId, tagId) VALUES ('rec-import', 'tag-client')",
        )

        insertAsset("asset-device-source", "rec-device", "DEVICE_WAV", "completed/shared.wav", 100)
        insertAsset("asset-device-canonical", "rec-device", "CANONICAL_WAV", "completed/shared.wav", 100)
        insertAsset("asset-device-opus", "rec-device", "DEVICE_OPUS", "completed/device.opus", 50)
        insertAsset("asset-import", "rec-import", "IMPORTED_ORIGINAL", "imported/phone.mp3", 300)

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
                'tx-device', 'rec-device', 'FAST', 'COMPLETED', 'asset-device-canonical',
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                'CANONICAL_PCM16_16000_MONO_WAV_V1', 160000,
                1, 'sherpa-onnx', '1.13.8', 'vad', '1',
                'asr', '1', NULL, NULL, NULL, NULL,
                'auto', '{}',
                'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                4000, 4000, 4100, 4100, NULL, NULL
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
                'summary-device', 'rec-device', 'tx-device',
                'TRANSCRIPT_TEXT', 'SMART', NULL, NULL,
                'provider', 'Provider', 'https://example.invalid', 'model',
                1, 1, 'MEETING', 0.9, '{}', 'summary',
                'COMPLETED', 5000, 5000, 5100, 5100,
                NULL, NULL, '{}', NULL, NULL, '{}'
            )
            """.trimIndent(),
        )
    }

    private fun insertAsset(
        assetId: String,
        recordingId: String,
        role: String,
        path: String,
        size: Long,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO audio_assets (
                assetId, recordingId, role, relativePath, container, codec,
                sampleFormat, sampleRateHz, channelCount, sizeBytes, sha256,
                integrityState, formatValidationState, createdAtMs, verifiedAtMs
            ) VALUES (
                '$assetId', '$recordingId', '$role', '$path', 'WAV', NULL,
                NULL, NULL, NULL, $size,
                'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                'VERIFIED', 'VALID', 1, 1
            )
            """.trimIndent(),
        )
    }
}
