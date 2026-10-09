package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Stage13C7SummarySnapshotContractTest {
    private lateinit var database: VoicaDatabase
    private lateinit var contentRepository: Stage12CContentRepository
    private lateinit var diarizationRepository: DiarizationRepository
    private var clock = 90_000L
    private val ids = AtomicInteger()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        contentRepository = Stage12CContentRepository(database)
        diarizationRepository =
            DiarizationRepository(
                database = database,
                nowMs = { ++clock },
                idFactory = { "c7-id-" + ids.incrementAndGet() },
            )
        seedCompletedTranscript()
        contentRepository.onTranscriptionCompleted(RECORDING_ID, TRANSCRIPTION_ID)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun summaryCanSnapshotCompletedTextBeforeSpeakerAlignmentAndLaterBuildCanUseSpeakerData() =
        runBlocking {
            val builder = StructuredTranscriptInputBuilder(database)

            val beforeSpeaker = builder.buildEffective(TRANSCRIPTION_ID)

            assertEquals(TEXT, beforeSpeaker.finalText)
            assertNull(beforeSpeaker.alignmentId)

            val runId = createCompletedDiarizationRun()
            val alignmentId =
                diarizationRepository.createAlignment(
                    NewTranscriptSpeakerAlignmentRequest(
                        transcriptionId = TRANSCRIPTION_ID,
                        diarizationRunId = runId,
                        alignmentVersion = 1,
                        configSnapshot = "{}",
                    ),
                )
            diarizationRepository.transitionAlignment(
                alignmentId,
                TranscriptSpeakerAlignmentStateValue.ALIGNING,
            )
            diarizationRepository.persistCompletedAlignment(
                alignmentId = alignmentId,
                spans =
                    listOf(
                        TranscriptSpeakerSpanWrite(
                            spanIndex = 0,
                            sourceSegmentIndex = 0,
                            speakerIndex = 0,
                            startSampleIndex = 0L,
                            endSampleIndexExclusive = 16_000L,
                            tokenSource = null,
                            tokenStartIndex = null,
                            tokenEndIndexExclusive = null,
                            finalTextStartOffset = 0,
                            finalTextEndOffsetExclusive = TEXT.length,
                            assignmentQuality = SpeakerAssignmentQualityValue.ASSIGNED,
                            overlap = false,
                            ambiguous = false,
                        ),
                    ),
            )

            val afterSpeaker = builder.buildEffective(TRANSCRIPTION_ID)

            assertEquals(TEXT, beforeSpeaker.finalText)
            assertNull(beforeSpeaker.alignmentId)
            assertEquals(TEXT, afterSpeaker.finalText)
            assertEquals(alignmentId, afterSpeaker.alignmentId)
            assertNotNull(afterSpeaker.units.single().evidence?.speakerId)
        }

    private suspend fun createCompletedDiarizationRun(): String {
        val runId =
            diarizationRepository.createRun(
                NewDiarizationRunRequest(
                    recordingId = RECORDING_ID,
                    sourceCanonicalAssetId = CANONICAL_ASSET_ID,
                    sourceCanonicalSha256 = SHA_A,
                    canonicalProfileId = PROFILE_ID,
                    totalSampleCount = 16_000L,
                    pipelineVersion = 1,
                    runtimeId = "sherpa-onnx",
                    runtimeVersion = "1.13.8",
                    vadModelId = "silero-vad-int8",
                    vadModelVersion = "1",
                    vadModelRevision = 1,
                    segmentationModelId = "pyannote-segmentation-3-int8",
                    segmentationModelVersion = "3.0",
                    segmentationModelRevision = 1,
                    embeddingModelId = "campplus-int8",
                    embeddingModelVersion = "stage13a",
                    embeddingModelRevision = 1,
                    modelManifestDigest = SHA_C,
                    configSnapshot = "{}",
                ),
            )
        diarizationRepository.transitionRun(runId, DiarizationStateValue.VAD_ANALYZING)
        diarizationRepository.transitionRun(runId, DiarizationStateValue.DIARIZING)
        diarizationRepository.transitionRun(runId, DiarizationStateValue.STITCHING)
        diarizationRepository.persistCompletedRun(
            runId = runId,
            speakerCount = 1,
            turns =
                listOf(
                    SpeakerTurnWrite(
                        speakerIndex = 0,
                        startSampleIndex = 0L,
                        endSampleIndexExclusive = 16_000L,
                        confidence = 0.95f,
                        overlap = false,
                    ),
                ),
        )
        return runId
    }

    private suspend fun seedCompletedTranscript() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "c7.wav",
                displayName = "C7",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 1_000L,
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                state = RecordingState.ACTIVE,
            ),
        )
        database.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = TRANSCRIPTION_ID,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.HIGH_QUALITY,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = CANONICAL_ASSET_ID,
                sourceCanonicalSha256 = SHA_A,
                canonicalProfileId = PROFILE_ID,
                totalSampleCount = 16_000L,
                pipelineVersion = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "silero-vad-int8",
                vadModelVersion = "1",
                firstPassAsrModelId = "sensevoice-int8",
                firstPassAsrModelVersion = "1",
                secondPassAsrModelId = null,
                secondPassAsrModelVersion = null,
                punctuationModelId = "ct-transformer-zh-en-int8",
                punctuationModelVersion = "1",
                languageConfig = "auto",
                configSnapshot = "{}",
                modelManifestDigest = SHA_B,
                createdAtMs = 10L,
                startedAtMs = 10L,
                updatedAtMs = 20L,
                completedAtMs = 20L,
                errorCode = null,
                errorMessage = null,
            ),
        )
        database.transcriptionDao().insertSegment(
            TranscriptSegmentEntity(
                id = SEGMENT_ID,
                transcriptionId = TRANSCRIPTION_ID,
                segmentIndex = 0,
                startSampleIndex = 0L,
                endSampleIndexExclusive = 16_000L,
                firstPassRawText = TEXT,
                secondPassRawText = null,
                finalText = TEXT,
                detectedLanguage = "zh",
                confidence = 0.9f,
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "c7-recording"
        const val TRANSCRIPTION_ID = "c7-transcription"
        const val SEGMENT_ID = "c7-segment"
        const val CANONICAL_ASSET_ID = "c7-canonical"
        const val PROFILE_ID = "CANONICAL_PCM16_16000_MONO_WAV_V1"
        const val TEXT = "转写正文已经可用"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
