package io.github.ioannes78.voica

import android.content.Context
import android.content.Intent
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
import io.github.ioannes78.voica.database.RecordingUserMetadataEntity
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalAudioExportCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: VoicaDatabase
    private lateinit var repository: RecordingLibraryRepository
    private lateinit var recordingsRoot: File
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: LocalAudioExportCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        recordingsRoot =
            File(context.cacheDir, "stage12b-export-test").apply {
                deleteRecursively()
                mkdirs()
            }
        repository =
            RecordingLibraryRepository(
                database = database,
                recordingsRoot = recordingsRoot,
                nowMs = { 10_000L },
            )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator =
            LocalAudioExportCoordinator(
                context = context,
                repository = repository,
                recordingsRoot = recordingsRoot,
                canonicalAudioCoordinator =
                    CanonicalAudioCoordinator(
                        repository = repository,
                        recordingsRoot = recordingsRoot,
                        applicationScope = scope,
                    ),
                nowMs = { 10_000L },
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        recordingsRoot.deleteRecursively()
        File(context.cacheDir, "share").deleteRecursively()
    }

    @Test
    fun canonicalAndImportedOriginalExposeCorrectFilenameAndMime() = runBlocking {
        seedRecording(
            recordingId = "rec-import",
            sourceType = RecordingSourceType.LOCAL_IMPORT,
            displayName = "Phone / Interview",
        )
        seedAsset(
            recordingId = "rec-import",
            assetId = "asset-original",
            role = AudioAssetRole.IMPORTED_ORIGINAL,
            relativePath = "imported/rec-import.mp3",
            container = "MP3",
            validation = AudioValidationState.VALID,
        )
        seedAsset(
            recordingId = "rec-import",
            assetId = "asset-canonical",
            role = AudioAssetRole.CANONICAL_WAV,
            relativePath = "canonical/rec-import.wav",
            container = "WAV",
            codec = "PCM",
            validation = AudioValidationState.VALID,
        )

        val canonical =
            coordinator.describe(
                recordingId = "rec-import",
                variant = AudioExportVariant.CANONICAL_WAV,
            )
        val original =
            coordinator.describe(
                recordingId = "rec-import",
                variant = AudioExportVariant.ORIGINAL,
            )

        requireNotNull(canonical)
        requireNotNull(original)
        assertEquals("audio/wav", canonical.mimeType)
        assertTrue(canonical.displayName.endsWith(".wav"))
        assertEquals("audio/mpeg", original.mimeType)
        assertTrue(original.displayName.endsWith(".mp3"))
        assertTrue('/' !in original.displayName)
    }

    @Test
    fun recorderRawOpusIsNeverAdvertisedAsStandardOggOpus() = runBlocking {
        seedRecording(
            recordingId = "rec-raw",
            sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
            displayName = "Recorder Raw",
        )
        seedAsset(
            recordingId = "rec-raw",
            assetId = "asset-raw",
            role = AudioAssetRole.DEVICE_OPUS,
            relativePath = "completed/rec-raw.opus",
            container = "RAW_OPUS",
            codec = "OPUS",
            validation = AudioValidationState.VALID,
        )

        val descriptor =
            coordinator.describe(
                recordingId = "rec-raw",
                variant = AudioExportVariant.ORIGINAL,
            )

        requireNotNull(descriptor)
        assertEquals("application/octet-stream", descriptor.mimeType)
        assertTrue(descriptor.displayName.endsWith(".opus"))
    }

    @Test
    fun shareUsesFileProviderContentUriAndTemporaryReadGrant() = runBlocking {
        seedRecording(
            recordingId = "rec-share",
            sourceType = RecordingSourceType.LOCAL_IMPORT,
            displayName = "Share Test",
        )
        seedAsset(
            recordingId = "rec-share",
            assetId = "asset-share",
            role = AudioAssetRole.CANONICAL_WAV,
            relativePath = "canonical/rec-share.wav",
            container = "WAV",
            codec = "PCM",
            validation = AudioValidationState.VALID,
        )

        val outcome =
            coordinator.prepareShare(
                recordingId = "rec-share",
                variant = AudioExportVariant.CANONICAL_WAV,
            )

        val ready = outcome as LocalAudioShareOutcome.Ready
        assertEquals(Intent.ACTION_CHOOSER, ready.intent.action)

        @Suppress("DEPRECATION")
        val send = ready.intent.getParcelableExtra(Intent.EXTRA_INTENT) as Intent?
        requireNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/wav", send.type)
        assertTrue(
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )

        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
        requireNotNull(stream)
        assertEquals("content", stream.scheme)
        assertEquals(context.packageName + ".fileprovider", stream.authority)
    }

    @Test
    fun deletingRecordingCannotBeDescribedForExport() = runBlocking {
        seedRecording(
            recordingId = "rec-deleting",
            sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
            displayName = "Deleting",
            state = RecordingState.DELETING,
        )
        seedAsset(
            recordingId = "rec-deleting",
            assetId = "asset-delete",
            role = AudioAssetRole.DEVICE_WAV,
            relativePath = "completed/rec-deleting.wav",
            container = "WAV",
            codec = "PCM",
            validation = AudioValidationState.VALID,
        )

        assertNull(
            coordinator.describe(
                recordingId = "rec-deleting",
                variant = AudioExportVariant.ORIGINAL,
            ),
        )
    }

    private suspend fun seedRecording(
        recordingId: String,
        sourceType: String,
        displayName: String,
        state: String = RecordingState.ACTIVE,
    ) {
        val dao = database.recordingDao()
        dao.insertRecordingIgnore(
            RecordingEntity(
                id = recordingId,
                sourceType = sourceType,
                sourceRemoteIdentity =
                    if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) {
                        "remote-$recordingId"
                    } else {
                        null
                    },
                sourceDeviceAddress =
                    if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) {
                        "AA:BB"
                    } else {
                        null
                    },
                originalFilename = "$recordingId.wav",
                displayName = displayName,
                recordedAtLocalIso = null,
                deviceReportedDurationMs = null,
                mediaDurationMs = 1_000L,
                downloadedAtMs =
                    if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) 1L else null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                state = state,
            ),
        )
        dao.insertUserMetadataIgnore(
            RecordingUserMetadataEntity(
                recordingId = recordingId,
                folderId = null,
                isFavorite = false,
                updatedAtMs = 1L,
            ),
        )
    }

    private suspend fun seedAsset(
        recordingId: String,
        assetId: String,
        role: String,
        relativePath: String,
        container: String,
        codec: String? = null,
        validation: String,
    ) {
        val file =
            File(recordingsRoot, relativePath).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(128) { index -> index.toByte() })
            }
        database.recordingDao().insertAssetIgnore(
            AudioAssetEntity(
                assetId = assetId,
                recordingId = recordingId,
                role = role,
                relativePath = relativePath,
                container = container,
                codec = codec,
                sampleFormat = null,
                sampleRateHz = null,
                channelCount = null,
                sizeBytes = file.length(),
                sha256 = "a".repeat(64),
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = validation,
                createdAtMs = 1L,
                verifiedAtMs = 1L,
            ),
        )
    }
}
