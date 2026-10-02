package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiarizationRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: DiarizationRepository
    private var clock = 20_000L
    private val ids = AtomicInteger()

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            DiarizationRepository(
                database = database,
                nowMs = { ++clock },
                idFactory = { "dia-id-" + ids.incrementAndGet() },
            )
        seedRecordingAndTranscriptions()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completedRunPersistsAnonymousSpeakersAndOverlappingTurns() = runBlocking {
        val runId = createCompletedRun()

        val completed = repository.findRun(runId)!!
        assertEquals(DiarizationStateValue.COMPLETED, completed.state)

        val speakers = repository.loadSpeakers(runId)
        assertEquals(listOf(1, 2), speakers.map { it.speakerOrdinal })
        assertTrue(speakers.all { it.displayName == null })

        val turns = repository.loadTurns(runId)
        assertEquals(2, turns.size)
        assertEquals(0L, turns[0].startSampleIndex)
        assertEquals(20_000L, turns[0].endSampleIndexExclusive)
        assertEquals(16_000L, turns[1].startSampleIndex)
        assertEquals(32_000L, turns[1].endSampleIndexExclusive)
        assertTrue(turns[1].overlap)
    }

    @Test
    fun oneCompletedDiarizationRunCanBackIndependentFastAndHqAlignments() = runBlocking {
        val runId = createCompletedRun()

        val fast = persistAlignment(runId, "tx-fast")
        val hq = persistAlignment(runId, "tx-hq")

        assertNotEquals(fast, hq)
        assertEquals(
            TranscriptSpeakerAlignmentStateValue.COMPLETED,
            repository.findAlignment(fast)!!.state,
        )
        assertEquals(
            TranscriptSpeakerAlignmentStateValue.COMPLETED,
            repository.findAlignment(hq)!!.state,
        )
        assertEquals(runId, repository.findAlignment(fast)!!.diarizationRunId)
        assertEquals(runId, repository.findAlignment(hq)!!.diarizationRunId)

        val fastSpans = repository.loadSpans(fast)
        val hqSpans = repository.loadSpans(hq)
        assertEquals(2, fastSpans.size)
        assertEquals(2, hqSpans.size)
        assertEquals("FIRST_PASS", fastSpans.first().tokenSource)
        assertEquals("SECOND_PASS", hqSpans.first().tokenSource)
    }

    @Test
    fun alignmentRejectsDifferentCanonicalAudio() = runBlocking {
        val runId = createCompletedRun()

        try {
            repository.createAlignment(
                NewTranscriptSpeakerAlignmentRequest(
                    transcriptionId = "tx-other-canonical",
                    diarizationRunId = runId,
                    alignmentVersion = 1,
                    configSnapshot = "{}",
                ),
            )
            throw AssertionError("different canonical audio must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun spanPersistenceRejectsGapsInFinalTextCoverage() = runBlocking {
        val runId = createCompletedRun()
        val alignmentId =
            repository.createAlignment(
                NewTranscriptSpeakerAlignmentRequest(
                    transcriptionId = "tx-fast",
                    diarizationRunId = runId,
                    alignmentVersion = 1,
                    configSnapshot = "{}",
                ),
            )
        repository.transitionAlignment(
            alignmentId,
            TranscriptSpeakerAlignmentStateValue.ALIGNING,
        )

        try {
            repository.persistCompletedAlignment(
                alignmentId,
                listOf(
                    span(
                        index = 0,
                        sourceSegmentIndex = 0,
                        speakerIndex = 0,
                        textStart = 0,
                        textEnd = 2,
                        sampleStart = 0,
                        sampleEnd = 16_000,
                        tokenSource = "FIRST_PASS",
                    ),
                    span(
                        index = 1,
                        sourceSegmentIndex = 0,
                        speakerIndex = 1,
                        textStart = 3,
                        textEnd = 6,
                        sampleStart = 16_000,
                        sampleEnd = 32_000,
                        tokenSource = "FIRST_PASS",
                    ),
                ),
            )
            throw AssertionError("finalText gap must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        assertTrue(repository.loadSpans(alignmentId).isEmpty())
        assertEquals(
            TranscriptSpeakerAlignmentStateValue.PERSISTING,
            repository.findAlignment(alignmentId)!!.state,
        )
    }

    @Test
    fun startupReconciliationInterruptsActiveRunAndAlignment() = runBlocking {
        val runId = repository.createRun(runRequest())
        repository.transitionRun(runId, DiarizationStateValue.VAD_ANALYZING)

        val completedRun = createCompletedRun()
        val alignmentId =
            repository.createAlignment(
                NewTranscriptSpeakerAlignmentRequest(
                    transcriptionId = "tx-fast",
                    diarizationRunId = completedRun,
                    alignmentVersion = 1,
                    configSnapshot = "{}",
                ),
            )
        repository.transitionAlignment(
            alignmentId,
            TranscriptSpeakerAlignmentStateValue.ALIGNING,
        )

        val result = repository.reconcileInterruptedOnStartup()

        assertEquals(1, result.interruptedRuns)
        assertEquals(1, result.interruptedAlignments)
        assertEquals(DiarizationStateValue.INTERRUPTED, repository.findRun(runId)!!.state)
        assertEquals(
            TranscriptSpeakerAlignmentStateValue.INTERRUPTED,
            repository.findAlignment(alignmentId)!!.state,
        )
    }

    @Test
    fun latestCompletedRunDoesNotOverwriteOlderRun() = runBlocking {
        val first = createCompletedRun()
        val second = createCompletedRun()

        assertNotEquals(first, second)
        val runs = repository.observeRuns("rec-1").first()
        assertEquals(2, runs.size)
        assertEquals(second, runs.first().id)
        assertEquals(first, runs.last().id)
    }

    private suspend fun createCompletedRun(): String {
        val runId = repository.createRun(runRequest())
        repository.transitionRun(runId, DiarizationStateValue.VAD_ANALYZING)
        repository.transitionRun(runId, DiarizationStateValue.DIARIZING)
        repository.transitionRun(runId, DiarizationStateValue.STITCHING)
        repository.persistCompletedRun(
            runId = runId,
            speakerCount = 2,
            turns =
                listOf(
                    SpeakerTurnWrite(
                        speakerIndex = 0,
                        startSampleIndex = 0,
                        endSampleIndexExclusive = 20_000,
                        confidence = 0.8F,
                        overlap = false,
                    ),
                    SpeakerTurnWrite(
                        speakerIndex = 1,
                        startSampleIndex = 16_000,
                        endSampleIndexExclusive = 32_000,
                        confidence = 0.7F,
                        overlap = true,
                    ),
                ),
        )
        return runId
    }

    private suspend fun persistAlignment(
        runId: String,
        transcriptionId: String,
    ): String {
        val source =
            if (transcriptionId == "tx-hq") {
                TranscriptTokenSourceValue.SECOND_PASS
            } else {
                TranscriptTokenSourceValue.FIRST_PASS
            }
        val alignmentId =
            repository.createAlignment(
                NewTranscriptSpeakerAlignmentRequest(
                    transcriptionId = transcriptionId,
                    diarizationRunId = runId,
                    alignmentVersion = 1,
                    configSnapshot = "{}",
                ),
            )
        repository.transitionAlignment(
            alignmentId,
            TranscriptSpeakerAlignmentStateValue.ALIGNING,
        )
        repository.persistCompletedAlignment(
            alignmentId,
            listOf(
                span(
                    index = 0,
                    sourceSegmentIndex = 0,
                    speakerIndex = 0,
                    textStart = 0,
                    textEnd = 3,
                    sampleStart = 0,
                    sampleEnd = 16_000,
                    tokenSource = source,
                ),
                span(
                    index = 1,
                    sourceSegmentIndex = 0,
                    speakerIndex = 1,
                    textStart = 3,
                    textEnd = 6,
                    sampleStart = 16_000,
                    sampleEnd = 32_000,
                    tokenSource = source,
                ),
            ),
        )
        return alignmentId
    }

    private fun span(
        index: Int,
        sourceSegmentIndex: Int,
        speakerIndex: Int?,
        textStart: Int,
        textEnd: Int,
        sampleStart: Long,
        sampleEnd: Long,
        tokenSource: String,
    ) =
        TranscriptSpeakerSpanWrite(
            spanIndex = index,
            sourceSegmentIndex = sourceSegmentIndex,
            speakerIndex = speakerIndex,
            startSampleIndex = sampleStart,
            endSampleIndexExclusive = sampleEnd,
            tokenSource = tokenSource,
            tokenStartIndex = index,
            tokenEndIndexExclusive = index + 1,
            finalTextStartOffset = textStart,
            finalTextEndOffsetExclusive = textEnd,
            assignmentQuality = SpeakerAssignmentQualityValue.ASSIGNED,
            overlap = false,
            ambiguous = false,
        )

    private fun runRequest() =
        NewDiarizationRunRequest(
            recordingId = "rec-1",
            sourceCanonicalAssetId = "canonical-1",
            sourceCanonicalSha256 = "a".repeat(64),
            canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            totalSampleCount = 32_000,
            pipelineVersion = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersion = "1.13.8",
            vadModelId = "silero-vad-int8",
            vadModelVersion = "2025-07-11",
            vadModelRevision = 1,
            segmentationModelId = "pyannote-segmentation-3-int8",
            segmentationModelVersion = "3.0",
            segmentationModelRevision = 1,
            embeddingModelId = "eres2net-base-zh-stage9",
            embeddingModelVersion = "stage9",
            embeddingModelRevision = 1,
            modelManifestDigest = "c".repeat(64),
            configSnapshot = "{}",
        )

    private suspend fun seedRecordingAndTranscriptions() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = "rec-1",
                sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                sourceRemoteIdentity = "remote-1",
                sourceDeviceAddress = "AA:BB",
                originalFilename = "note.wav",
                displayName = "Note",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 2_000,
                downloadedAtMs = 1_000,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            ),
        )

        seedTranscription(
            id = "tx-fast",
            mode = TranscriptionModeValue.FAST,
            sha = "a".repeat(64),
            tokenSource = TranscriptTokenSourceValue.FIRST_PASS,
        )
        seedTranscription(
            id = "tx-hq",
            mode = TranscriptionModeValue.HIGH_QUALITY,
            sha = "a".repeat(64),
            tokenSource = TranscriptTokenSourceValue.SECOND_PASS,
        )
        seedTranscription(
            id = "tx-other-canonical",
            mode = TranscriptionModeValue.FAST,
            sha = "f".repeat(64),
            tokenSource = TranscriptTokenSourceValue.FIRST_PASS,
        )
    }

    private suspend fun seedTranscription(
        id: String,
        mode: String,
        sha: String,
        tokenSource: String,
    ) {
        val dao = database.transcriptionDao()
        dao.insertTranscription(
            TranscriptionEntity(
                id = id,
                recordingId = "rec-1",
                mode = mode,
                state = TranscriptionStateValue.COMPLETED,
                sourceCanonicalAssetId = "canonical-1",
                sourceCanonicalSha256 = sha,
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = 32_000,
                pipelineVersion = 1,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "silero-vad-int8",
                vadModelVersion = "2025-07-11",
                firstPassAsrModelId = "zipformer-small-bilingual",
                firstPassAsrModelVersion = "2023-02-16",
                secondPassAsrModelId =
                    if (mode == TranscriptionModeValue.HIGH_QUALITY) "sensevoice-2024-int8" else null,
                secondPassAsrModelVersion =
                    if (mode == TranscriptionModeValue.HIGH_QUALITY) "2024-07-17" else null,
                punctuationModelId = "ct-transformer-zh-en-int8",
                punctuationModelVersion = "2024-04-12",
                languageConfig = "auto",
                configSnapshot = "{}",
                modelManifestDigest = "d".repeat(64),
                createdAtMs = 2_000,
                startedAtMs = 2_000,
                updatedAtMs = 2_001,
                completedAtMs = 2_001,
                errorCode = null,
                errorMessage = null,
            ),
        )
        val segmentId = id + "-segment"
        dao.insertSegment(
            TranscriptSegmentEntity(
                id = segmentId,
                transcriptionId = id,
                segmentIndex = 0,
                startSampleIndex = 0,
                endSampleIndexExclusive = 32_000,
                firstPassRawText = "你好世界",
                secondPassRawText =
                    if (mode == TranscriptionModeValue.HIGH_QUALITY) "你好世界" else null,
                finalText = "你好，世界。",
                detectedLanguage = "zh",
                confidence = 0.9F,
            ),
        )
        dao.insertTokens(
            listOf(
                TranscriptTokenEntity(
                    id = id + "-token-0",
                    transcriptSegmentId = segmentId,
                    tokenIndex = 0,
                    text = "你好",
                    startSampleIndex = 0,
                    endSampleIndexExclusive = 16_000,
                    source = tokenSource,
                ),
                TranscriptTokenEntity(
                    id = id + "-token-1",
                    transcriptSegmentId = segmentId,
                    tokenIndex = 1,
                    text = "世界",
                    startSampleIndex = 16_000,
                    endSampleIndexExclusive = 32_000,
                    source = tokenSource,
                ),
            ),
        )
    }
}
