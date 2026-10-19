package io.github.ioannes78.voica

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.database.AudioAssetEntity
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingEntity
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.RecordingSourceType
import io.github.ioannes78.voica.database.RecordingState
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineAnnotationAttachmentLifecycleTest {
    private lateinit var context: Context
    private lateinit var database: VoicaDatabase
    private lateinit var annotationRepository: TimelineAnnotationRepository
    private lateinit var attachmentStore: TimelineAnnotationAttachmentStore
    private lateinit var recordingsRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "timeline_annotations").deleteRecursively()
        File(context.cacheDir, "timeline-annotation-camera").deleteRecursively()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        annotationRepository =
            TimelineAnnotationRepository(database, nowMs = { 10L }) { "annotation-1" }
        attachmentStore =
            TimelineAnnotationAttachmentStore(
                context = context,
                database = database,
                annotationRepository = annotationRepository,
                nowMs = { 20L },
                idFactory = { "photo-1" },
            )
        recordingsRoot =
            File(context.cacheDir, "stage16a2-attachment-recordings").apply {
                deleteRecursively()
                mkdirs()
            }
    }

    @After
    fun tearDown() {
        database.close()
        recordingsRoot.deleteRecursively()
        File(context.filesDir, "timeline_annotations").deleteRecursively()
        File(context.cacheDir, "timeline-annotation-camera").deleteRecursively()
    }

    @Test
    fun `gallery photo is copied into app storage and annotation delete removes physical file`() = runBlocking {
        seedRecording("rec-photo")
        val annotation = createAnnotation("rec-photo")
        val source = createPng("gallery-source.png")

        val attachment = attachmentStore.importGallery(annotation.id, Uri.fromFile(source))
        val managed = File(context.filesDir, attachment.relativePath)

        assertTrue(source.isFile)
        assertTrue(managed.isFile)
        assertTrue(attachment.mimeType.startsWith("image/"))
        assertEquals(3, attachment.widthPx)
        assertEquals(2, attachment.heightPx)
        assertTrue(attachment.sha256.matches(Regex("^[0-9a-f]{64}$")))

        val coordinator = TimelineAnnotationContentCoordinator(annotationRepository, attachmentStore)
        val result = coordinator.delete(annotation.id)

        assertTrue(result.deleted)
        assertTrue(result.failedPaths.isEmpty())
        assertFalse(managed.exists())
        assertNull(annotationRepository.get(annotation.id))
        assertNull(database.timelineAnnotationDao().getAttachment(attachment.id))
    }

    @Test
    fun `startup reconcile removes attachment row when managed file is missing`() = runBlocking {
        seedRecording("rec-missing")
        val annotation = createAnnotation("rec-missing")
        val attachment =
            attachmentStore.importGallery(annotation.id, Uri.fromFile(createPng("missing-source.png")))
        val managed = File(context.filesDir, attachment.relativePath)
        assertTrue(managed.delete())

        val result = attachmentStore.reconcileOnStartup()

        assertEquals(1, result.removedMissingRows)
        assertNull(database.timelineAnnotationDao().getAttachment(attachment.id))
        assertTrue(annotationRepository.get(annotation.id) != null)
    }

    @Test
    fun `recording delete cleans annotation files before database cascade`() = runBlocking {
        val library = RecordingLibraryRepository(database, recordingsRoot)
        val audio = seedRecording("rec-delete", recordingsRoot)
        val annotation = createAnnotation("rec-delete")
        val attachment =
            attachmentStore.importGallery(annotation.id, Uri.fromFile(createPng("delete-source.png")))
        val managed = File(context.filesDir, attachment.relativePath)
        val coordinator =
            LocalRecordingDeleteCoordinator(
                repository = library,
                hooks = NoOpDeletionHooks,
                annotationAttachmentCleanup = attachmentStore::deleteAttachmentsForRecording,
            )

        val result = coordinator.delete("rec-delete")

        assertTrue(result.deleted)
        assertFalse(managed.exists())
        assertFalse(audio.exists())
        assertNull(database.recordingDao().recordingState("rec-delete"))
        assertNull(annotationRepository.get(annotation.id))
    }

    @Test
    fun `recording database delete is blocked while annotation attachment cleanup reports failure`() = runBlocking {
        val library = RecordingLibraryRepository(database, recordingsRoot)
        val audio = seedRecording("rec-blocked", recordingsRoot)
        val coordinator =
            LocalRecordingDeleteCoordinator(
                repository = library,
                hooks = NoOpDeletionHooks,
                annotationAttachmentCleanup = { listOf("timeline_annotations/a/photo.jpg") },
            )

        val result = coordinator.delete("rec-blocked")

        assertFalse(result.deleted)
        assertEquals(listOf("timeline_annotations/a/photo.jpg"), result.failedPaths)
        assertTrue(audio.isFile)
        assertEquals(RecordingState.DELETING, database.recordingDao().recordingState("rec-blocked"))
    }

    private suspend fun createAnnotation(recordingId: String) =
        annotationRepository.create(
            owner = TimelineAnnotationOwner.Recording(recordingId),
            anchor =
                TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                    sampleIndex = 8_000L,
                ),
        )

    private suspend fun seedRecording(
        recordingId: String,
        root: File? = null,
    ): File {
        val dao = database.recordingDao()
        dao.insertRecordingIgnore(
            RecordingEntity(
                id = recordingId,
                sourceType = RecordingSourceType.PHONE_MICROPHONE,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "$recordingId.wav",
                displayName = recordingId,
                recordedAtLocalIso = null,
                deviceReportedDurationMs = null,
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
        if (root == null) return File(context.cacheDir, "unused-$recordingId")

        val relativePath = "completed/$recordingId.wav"
        val audio =
            File(root, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        dao.insertAssetIgnore(
            AudioAssetEntity(
                assetId = "asset-$recordingId",
                recordingId = recordingId,
                role = AudioAssetRole.CANONICAL_WAV,
                relativePath = relativePath,
                container = "WAV",
                codec = "PCM",
                sampleFormat = "PCM16_LE",
                sampleRateHz = 16_000,
                channelCount = 1,
                sizeBytes = audio.length(),
                sha256 = "a".repeat(64),
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = AudioValidationState.VALID,
                createdAtMs = 1L,
                verifiedAtMs = 1L,
            ),
        )
        return audio
    }

    private fun createPng(name: String): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private object NoOpDeletionHooks : RecordingDeletionHooks {
        override suspend fun stopPlaybackIfTarget(recordingId: String) = Unit
        override suspend fun cancelCanonicalAndAwait(recordingId: String) = Unit
        override suspend fun cancelTranscriptionAndAwait(recordingId: String) = Unit
        override suspend fun cancelDiarizationAndAwait(recordingId: String) = Unit
        override suspend fun cancelAiSummaryAndAwait(recordingId: String) = Unit
    }
}
