package io.github.ioannes78.voica

import android.content.Context
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
import io.github.ioannes78.voica.database.RecordingUserMetadataEntity
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
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
class LocalRecordingDeleteCoordinatorTest {
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
        recordingsRoot =
            File(context.cacheDir, "stage12b-delete-coordinator-test").apply {
                deleteRecursively()
                mkdirs()
            }
        repository = RecordingLibraryRepository(database, recordingsRoot)
    }

    @After
    fun tearDown() {
        database.close()
        recordingsRoot.deleteRecursively()
    }

    @Test
    fun deleteDrainsLifecycleBeforeRemovingManagedFileAndRow() = runBlocking {
        val source = seedRecording("rec-1")
        val hooks = FakeHooks()
        val coordinator = LocalRecordingDeleteCoordinator(repository, hooks)

        val result = coordinator.delete("rec-1")

        assertTrue(result.deleted)
        assertFalse(source.exists())
        assertNull(database.recordingDao().recordingState("rec-1"))
        assertEquals(
            listOf(
                "playback:rec-1",
                "canonical:rec-1",
                "transcription:rec-1",
                "diarization:rec-1",
                "summary:rec-1",
            ),
            hooks.events,
        )
    }

    @Test
    fun lifecycleFailureLeavesDeletingRowAndSourceForSafeRetry() = runBlocking {
        val source = seedRecording("rec-retry")
        val hooks = FakeHooks(failAt = "transcription")
        val coordinator = LocalRecordingDeleteCoordinator(repository, hooks)

        val first = coordinator.delete("rec-retry")

        assertFalse(first.deleted)
        assertTrue(source.exists())
        assertEquals(
            RecordingState.DELETING,
            database.recordingDao().recordingState("rec-retry"),
        )

        hooks.failAt = null
        hooks.events.clear()
        val second = coordinator.delete("rec-retry")

        assertTrue(second.deleted)
        assertFalse(source.exists())
        assertNull(database.recordingDao().recordingState("rec-retry"))
        assertEquals(
            listOf(
                "playback:rec-retry",
                "canonical:rec-retry",
                "transcription:rec-retry",
                "diarization:rec-retry",
                "summary:rec-retry",
            ),
            hooks.events,
        )
    }

    @Test
    fun batchDeleteIsDeterministicAndSequential() = runBlocking {
        seedRecording("rec-a")
        seedRecording("rec-b")
        val hooks = FakeHooks()
        val coordinator = LocalRecordingDeleteCoordinator(repository, hooks)

        val result = coordinator.deleteBatch(listOf("rec-a", "rec-b", "rec-a"))

        assertEquals(2, result.deletedCount)
        assertEquals(0, result.failedCount)
        assertEquals(
            listOf(
                "playback:rec-a",
                "canonical:rec-a",
                "transcription:rec-a",
                "diarization:rec-a",
                "summary:rec-a",
                "playback:rec-b",
                "canonical:rec-b",
                "transcription:rec-b",
                "diarization:rec-b",
                "summary:rec-b",
            ),
            hooks.events,
        )
    }

    private suspend fun seedRecording(recordingId: String): File {
        val now = 1_000L
        val relativePath = "completed/$recordingId.wav"
        val source =
            File(recordingsRoot, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val dao = database.recordingDao()
        dao.insertRecordingIgnore(
            RecordingEntity(
                id = recordingId,
                sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                sourceRemoteIdentity = "remote-$recordingId",
                sourceDeviceAddress = "AA:BB",
                originalFilename = "$recordingId.wav",
                displayName = recordingId,
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 1_000L,
                downloadedAtMs = now,
                createdAtMs = now,
                updatedAtMs = now,
            ),
        )
        dao.insertUserMetadataIgnore(
            RecordingUserMetadataEntity(
                recordingId = recordingId,
                folderId = null,
                isFavorite = false,
                updatedAtMs = now,
            ),
        )
        dao.insertAssetIgnore(
            AudioAssetEntity(
                assetId = "asset-$recordingId",
                recordingId = recordingId,
                role = AudioAssetRole.DEVICE_WAV,
                relativePath = relativePath,
                container = "WAV",
                codec = "PCM",
                sampleFormat = "PCM16_LE",
                sampleRateHz = 16_000,
                channelCount = 1,
                sizeBytes = source.length(),
                sha256 = "a".repeat(64),
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = AudioValidationState.VALID,
                createdAtMs = now,
                verifiedAtMs = now,
            ),
        )
        return source
    }

    private class FakeHooks(
        var failAt: String? = null,
    ) : RecordingDeletionHooks {
        val events = mutableListOf<String>()

        override suspend fun stopPlaybackIfTarget(recordingId: String) =
            step("playback", recordingId)

        override suspend fun cancelCanonicalAndAwait(recordingId: String) =
            step("canonical", recordingId)

        override suspend fun cancelTranscriptionAndAwait(recordingId: String) =
            step("transcription", recordingId)

        override suspend fun cancelDiarizationAndAwait(recordingId: String) =
            step("diarization", recordingId)

        override suspend fun cancelAiSummaryAndAwait(recordingId: String) =
            step("summary", recordingId)

        private fun step(
            name: String,
            recordingId: String,
        ) {
            events += "$name:$recordingId"
            if (failAt == name) {
                error("forced $name failure")
            }
        }
    }
}
