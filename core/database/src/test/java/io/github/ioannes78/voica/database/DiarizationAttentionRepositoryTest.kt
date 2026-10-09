package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class DiarizationAttentionRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var diarizationRepository: DiarizationRepository
    private lateinit var attentionRepository: DiarizationAttentionRepository
    private var clock = 1_000L
    private var idCounter = 0

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database =
                Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            diarizationRepository =
                DiarizationRepository(
                    database = database,
                    nowMs = { ++clock },
                    idFactory = { "dia-attention-${++idCounter}" },
                )
            attentionRepository = DiarizationAttentionRepository(database, nowMs = { ++clock })
            database.recordingDao().insertRecordingIgnore(recording())
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun interruptedRunSurfacesUntilExplicitlyAcknowledged() = runBlocking {
        val runId = createRun()
        diarizationRepository.transitionRun(runId, DiarizationStateValue.VAD_ANALYZING)
        diarizationRepository.reconcileInterruptedOnStartup()

        val attention = attentionRepository.observeForRecording(RECORDING_ID).first().single()
        assertEquals(DiarizationAttentionKind.DIARIZATION, attention.kind)
        assertEquals(DiarizationStateValue.INTERRUPTED, attention.state)
        assertEquals(runId, attention.id)

        assertTrue(attentionRepository.acknowledge(attention))
        assertTrue(attentionRepository.observeForRecording(RECORDING_ID).first().isEmpty())
    }

    @Test
    fun userCancelledRunIsNotDurableAttention() = runBlocking {
        val runId = createRun()
        diarizationRepository.cancelRun(runId)

        assertTrue(attentionRepository.observeForRecording(RECORDING_ID).first().isEmpty())
    }

    @Test
    fun replacementDurableRunResolvesOlderInterruptedRun() = runBlocking {
        val interruptedId = createRun()
        diarizationRepository.transitionRun(interruptedId, DiarizationStateValue.VAD_ANALYZING)
        diarizationRepository.transitionRun(interruptedId, DiarizationStateValue.DIARIZING)
        diarizationRepository.reconcileInterruptedOnStartup()
        assertEquals(
            listOf(interruptedId),
            attentionRepository.observeForRecording(RECORDING_ID).first().map { it.id },
        )

        val replacementId = createRun()
        attentionRepository.acknowledgeSupersededRun(
            recordingId = RECORDING_ID,
            replacementRunId = replacementId,
        )

        assertTrue(attentionRepository.observeForRecording(RECORDING_ID).first().isEmpty())
        assertEquals(DiarizationStateValue.PREPARING, diarizationRepository.findRun(replacementId)?.state)
    }

    private suspend fun createRun(): String =
        diarizationRepository.createRun(
            NewDiarizationRunRequest(
                recordingId = RECORDING_ID,
                sourceCanonicalAssetId = "canonical",
                sourceCanonicalSha256 = SHA_A,
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = 16_000L,
                pipelineVersion = 2,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "silero-vad-int8",
                vadModelVersion = "1",
                vadModelRevision = 1,
                segmentationModelId = "pyannote-segmentation-3-int8",
                segmentationModelVersion = "3",
                segmentationModelRevision = 1,
                embeddingModelId = "campplus-int8",
                embeddingModelVersion = "1",
                embeddingModelRevision = 1,
                modelManifestDigest = SHA_B,
                configSnapshot = "{\"speakerCountPreset\":\"TWO\"}",
            ),
        )

    private fun recording() =
        RecordingEntity(
            id = RECORDING_ID,
            sourceType = RecordingSourceType.LOCAL_IMPORT,
            sourceRemoteIdentity = null,
            sourceDeviceAddress = null,
            originalFilename = "attention.wav",
            displayName = "Attention",
            recordedAtLocalIso = null,
            deviceReportedDurationMs = 1_000L,
            mediaDurationMs = 1_000L,
            downloadedAtMs = null,
            createdAtMs = 1L,
            updatedAtMs = 1L,
            state = RecordingState.ACTIVE,
        )

    private companion object {
        const val RECORDING_ID = "recording-attention"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
