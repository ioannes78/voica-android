package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingStorageCleanupTest {
    private lateinit var database: VoicaDatabase
    private lateinit var root: File
    private lateinit var repository: RecordingLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "stage12b-storage-cleanup-test").apply {
            deleteRecursively()
            mkdirs()
        }
        repository =
            RecordingLibraryRepository(
                database = database,
                recordingsRoot = root,
                nowMs = { 20_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun cleanupOnlyDeletesReproducibleUnconsumedCanonicalFiles() = runBlocking {
        seedRecording("safe")
        seedAsset(
            recordingId = "safe",
            assetId = "safe-source",
            role = AudioAssetRole.IMPORTED_ORIGINAL,
            path = "imported/safe.mp3",
            size = 100,
            container = "MP3",
        )
        seedAsset(
            recordingId = "safe",
            assetId = "safe-canonical",
            role = AudioAssetRole.CANONICAL_WAV,
            path = "canonical/safe.wav",
            size = 200,
            container = "WAV",
        )
        seedDerivation("safe", "safe-source", "safe-canonical")

        seedRecording("shared")
        seedAsset(
            recordingId = "shared",
            assetId = "shared-source",
            role = AudioAssetRole.DEVICE_WAV,
            path = "completed/shared.wav",
            size = 300,
            container = "WAV",
        )
        seedAsset(
            recordingId = "shared",
            assetId = "shared-canonical",
            role = AudioAssetRole.CANONICAL_WAV,
            path = "completed/shared.wav",
            size = 300,
            container = "WAV",
        )

        seedRecording("consumed")
        seedAsset(
            recordingId = "consumed",
            assetId = "consumed-source",
            role = AudioAssetRole.IMPORTED_ORIGINAL,
            path = "imported/consumed.flac",
            size = 120,
            container = "FLAC",
        )
        seedAsset(
            recordingId = "consumed",
            assetId = "consumed-canonical",
            role = AudioAssetRole.CANONICAL_WAV,
            path = "canonical/consumed.wav",
            size = 220,
            container = "WAV",
        )
        seedCompletedTranscription(
            recordingId = "consumed",
            canonicalAssetId = "consumed-canonical",
        )

        val usage = repository.recordingStorageUsage()
        assertEquals(520L, usage.originalAudioBytes)
        assertEquals(420L, usage.canonicalAudioBytes)
        assertEquals(200L, usage.reclaimableCanonicalBytes)

        val candidates = repository.reclaimableCanonicalCandidates()
        assertEquals(listOf("safe-canonical"), candidates.map { it.assetId })

        val result = repository.cleanupReclaimableCanonicalAudio()
        assertEquals(200L, result.reclaimedBytes)
        assertEquals(1, result.deletedAssets)
        assertTrue(result.failedPaths.isEmpty())

        assertTrue(File(root, "imported/safe.mp3").isFile)
        assertFalse(File(root, "canonical/safe.wav").exists())
        assertTrue(File(root, "completed/shared.wav").isFile)
        assertTrue(File(root, "canonical/consumed.wav").isFile)

        assertEquals(
            null,
            database.recordingDao().findAsset(
                recordingId = "safe",
                role = AudioAssetRole.CANONICAL_WAV,
            ),
        )
        val derivation =
            database.recordingDao().findDerivation(
                recordingId = "safe",
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                sourceSha256 = "b".repeat(64),
            )
        assertNotNull(derivation)
        assertEquals(AudioDerivationState.NOT_PRESENT, derivation!!.state)
        assertEquals(null, derivation.outputAssetId)

        assertNotNull(
            database.recordingDao().findAsset(
                recordingId = "shared",
                role = AudioAssetRole.CANONICAL_WAV,
            ),
        )
        assertNotNull(
            database.recordingDao().findAsset(
                recordingId = "consumed",
                role = AudioAssetRole.CANONICAL_WAV,
            ),
        )
    }

    @Test
    fun cleanupRejectsCanonicalWhenDerivationSourceShaDoesNotMatchAsset() = runBlocking {
        seedRecording("mismatch")
        seedAsset(
            recordingId = "mismatch",
            assetId = "mismatch-source",
            role = AudioAssetRole.IMPORTED_ORIGINAL,
            path = "imported/mismatch.mp3",
            size = 140,
            container = "MP3",
        )
        seedAsset(
            recordingId = "mismatch",
            assetId = "mismatch-canonical",
            role = AudioAssetRole.CANONICAL_WAV,
            path = "canonical/mismatch.wav",
            size = 240,
            container = "WAV",
        )

        database.recordingDao().upsertDerivation(
            AudioDerivationEntity(
                recordingId = "mismatch",
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                sourceSha256 = "c".repeat(64),
                sourceAssetId = "mismatch-source",
                outputAssetId = "mismatch-canonical",
                pipelineVersion = 1,
                state = AudioDerivationState.READY,
                startedAtMs = 1L,
                updatedAtMs = 1L,
                completedAtMs = 1L,
                errorCode = null,
                errorDetail = null,
            ),
        )

        assertTrue(repository.reclaimableCanonicalCandidates().isEmpty())
        val result = repository.cleanupReclaimableCanonicalAudio()
        assertEquals(0L, result.reclaimedBytes)
        assertEquals(0, result.deletedAssets)
        assertTrue(File(root, "canonical/mismatch.wav").isFile)
        assertNotNull(
            database.recordingDao().findAsset(
                recordingId = "mismatch",
                role = AudioAssetRole.CANONICAL_WAV,
            ),
        )
    }

    private suspend fun seedRecording(id: String) {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = id,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "$id.audio",
                displayName = id,
                recordedAtLocalIso = null,
                deviceReportedDurationMs = null,
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                state = RecordingState.ACTIVE,
            ),
        )
    }

    private suspend fun seedAsset(
        recordingId: String,
        assetId: String,
        role: String,
        path: String,
        size: Int,
        container: String,
    ) {
        val file = File(root, path).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size) { 1 })
        }
        database.recordingDao().insertAssetIgnore(
            AudioAssetEntity(
                assetId = assetId,
                recordingId = recordingId,
                role = role,
                relativePath = path,
                container = container,
                codec = null,
                sampleFormat = null,
                sampleRateHz = null,
                channelCount = null,
                sizeBytes = file.length(),
                sha256 =
                    if (role == AudioAssetRole.CANONICAL_WAV) {
                        "a".repeat(64)
                    } else {
                        "b".repeat(64)
                    },
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = AudioValidationState.VALID,
                createdAtMs = 1L,
                verifiedAtMs = 1L,
            ),
        )
    }

    private suspend fun seedDerivation(
        recordingId: String,
        sourceAssetId: String,
        outputAssetId: String,
    ) {
        database.recordingDao().upsertDerivation(
            AudioDerivationEntity(
                recordingId = recordingId,
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                sourceSha256 = "b".repeat(64),
                sourceAssetId = sourceAssetId,
                outputAssetId = outputAssetId,
                pipelineVersion = 1,
                state = AudioDerivationState.READY,
                startedAtMs = 1L,
                updatedAtMs = 1L,
                completedAtMs = 1L,
                errorCode = null,
                errorDetail = null,
            ),
        )
    }

    private fun seedCompletedTranscription(
        recordingId: String,
        canonicalAssetId: String,
    ) {
        database.openHelper.writableDatabase.execSQL(
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
                'tx-' || ?, ?, 'FAST', 'COMPLETED', ?,
                ?, 'CANONICAL_PCM16_16000_MONO_WAV_V1', 16000,
                1, 'sherpa-onnx', '1.13.8', 'vad', '1',
                'asr', '1', NULL, NULL, NULL, NULL,
                'auto', '{}', ?, 1, 1, 1, 1, NULL, NULL
            )
            """.trimIndent(),
            arrayOf(
                recordingId,
                recordingId,
                canonicalAssetId,
                "a".repeat(64),
                "d".repeat(64),
            ),
        )
    }
}
