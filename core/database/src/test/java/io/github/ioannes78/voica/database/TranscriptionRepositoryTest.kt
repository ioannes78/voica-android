package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var recordingRepository: RecordingLibraryRepository
    private lateinit var repository: TranscriptionRepository
    private lateinit var root: File
    private var clock = 10_000L
    private val ids = AtomicInteger()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                VoicaDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "transcription-repository-test-" + System.nanoTime())
        root.mkdirs()
        recordingRepository =
            RecordingLibraryRepository(
                database = database,
                recordingsRoot = root,
                nowMs = { ++clock },
            )
        repository =
            TranscriptionRepository(
                database = database,
                nowMs = { ++clock },
                idFactory = { "id-" + ids.incrementAndGet() },
            )

        seedCanonicalRecording()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun canonicalLineageRequiresReadyVerifiedCanonicalAsset() = runBlocking {
        val lineage =
            recordingRepository.loadCanonicalTranscriptionLineage(
                recordingId = "rec-1",
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            )

        assertNotNull(lineage)
        assertEquals("canonical-1", lineage!!.canonicalAssetId)
        assertEquals("a".repeat(64), lineage.canonicalSha256)
        assertEquals("CANONICAL_PCM16_16000_MONO_WAV_V1", lineage.canonicalProfileId)
        assertEquals(1, lineage.canonicalPipelineVersion)

        assertNull(
            recordingRepository.loadCanonicalTranscriptionLineage(
                recordingId = "rec-1",
                profileId = "other-profile",
            ),
        )
    }

    @Test
    fun completedTranscriptPersistsSegmentsAndTokensAtomically() = runBlocking {
        val id = repository.create(request(mode = TranscriptionModeValue.FAST))
        repository.transition(id, TranscriptionStateValue.VAD_ANALYZING)
        repository.transition(id, TranscriptionStateValue.FIRST_PASS_TRANSCRIBING)
        repository.transition(id, TranscriptionStateValue.PUNCTUATING)

        repository.persistCompleted(
            transcriptionId = id,
            segments =
                listOf(
                    TranscriptSegmentWrite(
                        segmentIndex = 0,
                        startSampleIndex = 1_600,
                        endSampleIndexExclusive = 4_800,
                        firstPassRawText = "今天开会",
                        secondPassRawText = null,
                        finalText = "今天开会。",
                        detectedLanguage = "zh",
                        confidence = null,
                        tokens =
                            listOf(
                                TranscriptTokenWrite(
                                    text = "今天",
                                    startSampleIndex = 1_600,
                                    endSampleIndexExclusive = 2_400,
                                    source = TranscriptTokenSourceValue.FIRST_PASS,
                                ),
                            ),
                    ),
                ),
        )

        val completed = repository.find(id)!!
        assertEquals(TranscriptionStateValue.COMPLETED, completed.state)
        assertNotNull(completed.completedAtMs)

        val latest = repository.observeLatestCompleted("rec-1").first()
        assertEquals(id, latest!!.id)

        val segments = repository.loadSegments(id)
        assertEquals(1, segments.size)
        assertEquals("今天开会。", segments.single().finalText)

        val tokens = repository.loadTokens(segments.single().id)
        assertEquals(1, tokens.size)
        assertEquals(1_600L, tokens.single().startSampleIndex)
        assertEquals(TranscriptTokenSourceValue.FIRST_PASS, tokens.single().source)
    }

    @Test
    fun invalidStateJumpIsRejected() = runBlocking {
        val id = repository.create(request(mode = TranscriptionModeValue.HIGH_QUALITY))

        try {
            repository.transition(id, TranscriptionStateValue.SECOND_PASS_TRANSCRIBING)
            throw AssertionError("invalid state transition must fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertEquals(
            TranscriptionStateValue.PREPARING,
            repository.find(id)!!.state,
        )
    }

    @Test
    fun startupReconciliationMarksActiveRowsInterrupted() = runBlocking {
        val first = repository.create(request(mode = TranscriptionModeValue.FAST))
        repository.transition(first, TranscriptionStateValue.VAD_ANALYZING)
        val second = repository.create(request(mode = TranscriptionModeValue.HIGH_QUALITY))
        repository.transition(second, TranscriptionStateValue.VAD_ANALYZING)
        repository.transition(second, TranscriptionStateValue.FIRST_PASS_TRANSCRIBING)

        val changed = repository.reconcileInterruptedOnStartup()

        assertEquals(2, changed)
        assertEquals(
            TranscriptionStateValue.INTERRUPTED,
            repository.find(first)!!.state,
        )
        assertEquals(
            TranscriptionStateValue.INTERRUPTED,
            repository.find(second)!!.state,
        )
        assertEquals("PROCESS_INTERRUPTED", repository.find(second)!!.errorCode)
    }

    @Test
    fun failedPersistenceValidationDoesNotWritePartialSegments() = runBlocking {
        val id = repository.create(request(mode = TranscriptionModeValue.FAST))
        repository.transition(id, TranscriptionStateValue.VAD_ANALYZING)
        repository.transition(id, TranscriptionStateValue.FIRST_PASS_TRANSCRIBING)
        repository.transition(id, TranscriptionStateValue.PUNCTUATING)

        try {
            repository.persistCompleted(
                transcriptionId = id,
                segments =
                    listOf(
                        TranscriptSegmentWrite(
                            segmentIndex = 0,
                            startSampleIndex = 0,
                            endSampleIndexExclusive = 20_000,
                            firstPassRawText = "bad",
                            secondPassRawText = null,
                            finalText = "bad",
                            detectedLanguage = null,
                            confidence = null,
                            tokens = emptyList(),
                        ),
                    ),
            )
            throw AssertionError("out-of-bounds segment must fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(repository.loadSegments(id).isEmpty())
        assertEquals(
            TranscriptionStateValue.PERSISTING,
            repository.find(id)!!.state,
        )
    }

    private suspend fun seedCanonicalRecording() {
        val dao = database.recordingDao()
        dao.insertRecordingIgnore(
            RecordingEntity(
                id = "rec-1",
                sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                sourceRemoteIdentity = "remote-1",
                sourceDeviceAddress = "AA:BB",
                originalFilename = "note.wav",
                displayName = "Note",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 1_000,
                downloadedAtMs = 1_000,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            ),
        )
        dao.insertAssetIgnore(
            AudioAssetEntity(
                assetId = "canonical-1",
                recordingId = "rec-1",
                role = AudioAssetRole.CANONICAL_WAV,
                relativePath = "canonical/rec-1.wav",
                container = "WAV",
                codec = "PCM",
                sampleFormat = "PCM16_LE",
                sampleRateHz = 16_000,
                channelCount = 1,
                sizeBytes = 32_044,
                sha256 = "a".repeat(64),
                integrityState = AudioIntegrityState.VERIFIED,
                formatValidationState = AudioValidationState.VALID,
                createdAtMs = 1_000,
                verifiedAtMs = 1_000,
            ),
        )
        dao.upsertDerivation(
            AudioDerivationEntity(
                recordingId = "rec-1",
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                sourceSha256 = "b".repeat(64),
                sourceAssetId = "device-1",
                outputAssetId = "canonical-1",
                pipelineVersion = 1,
                state = AudioDerivationState.READY,
                startedAtMs = 1_000,
                updatedAtMs = 1_001,
                completedAtMs = 1_001,
                errorCode = null,
                errorDetail = null,
            ),
        )
    }

    private fun request(mode: String) =
        NewTranscriptionRequest(
            recordingId = "rec-1",
            mode = mode,
            sourceCanonicalAssetId = "canonical-1",
            sourceCanonicalSha256 = "a".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 16_000,
            pipelineVersion = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersion = "1.13.8",
            vadModelId = "silero-vad-int8",
            vadModelVersion = "2025-07-11",
            firstPassAsrModelId = "zipformer-small-bilingual",
            firstPassAsrModelVersion = "2023-02-16",
            secondPassAsrModelId =
                if (mode == TranscriptionModeValue.HIGH_QUALITY) {
                    "sensevoice-2024-int8"
                } else {
                    null
                },
            secondPassAsrModelVersion =
                if (mode == TranscriptionModeValue.HIGH_QUALITY) {
                    "2024-07-17"
                } else {
                    null
                },
            punctuationModelId = "ct-transformer-zh-en-int8",
            punctuationModelVersion = "2024-04-12",
            languageConfig = "auto",
            configSnapshot = "{}",
            modelManifestDigest = "c".repeat(64),
        )
}
