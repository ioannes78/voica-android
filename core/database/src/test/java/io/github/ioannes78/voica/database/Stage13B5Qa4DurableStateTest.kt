package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class Stage13B5Qa4DurableStateTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: Stage13B5Qa4Repository
    private var clock = 10_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = Stage13B5Qa4Repository(database, nowMs = { clock++ })
        runBlocking { insertRecording() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun candidateDismissalsAreDurableAndIndependent() = runBlocking {
        repository.dismissTranscriptionCandidate(RECORDING_ID, "tx-candidate")
        var attention = repository.observeCandidateAttention(RECORDING_ID).first()
        assertEquals("tx-candidate", attention?.dismissedTranscriptionCandidateId)
        assertNull(attention?.dismissedAiSummaryCandidateId)

        repository.dismissAiSummaryCandidate(RECORDING_ID, "summary-candidate")
        attention = repository.observeCandidateAttention(RECORDING_ID).first()
        assertEquals("tx-candidate", attention?.dismissedTranscriptionCandidateId)
        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)

        repository.clearTranscriptionCandidateDismissal(RECORDING_ID)
        attention = repository.observeCandidateAttention(RECORDING_ID).first()
        assertNull(attention?.dismissedTranscriptionCandidateId)
        assertEquals("summary-candidate", attention?.dismissedAiSummaryCandidateId)
    }

    @Test
    fun interruptedTranscriptionRemainsAttentionUntilExplicitlyAcknowledged() = runBlocking {
        insertTranscription(
            id = "tx-interrupted",
            state = TranscriptionStateValue.INTERRUPTED,
            createdAtMs = 100L,
        )

        assertEquals(
            "tx-interrupted",
            repository.observeTranscriptionAttentionForRecording(RECORDING_ID).first()?.id,
        )

        assertTrue(repository.acknowledgeTranscriptionAttention("tx-interrupted"))
        assertNull(repository.observeTranscriptionAttentionForRecording(RECORDING_ID).first())
    }

    @Test
    fun newerReplacementSuppressesOlderTranscriptionAttention() = runBlocking {
        insertTranscription(
            id = "tx-old",
            state = TranscriptionStateValue.INTERRUPTED,
            createdAtMs = 100L,
        )
        insertTranscription(
            id = "tx-new",
            state = TranscriptionStateValue.PREPARING,
            createdAtMs = 200L,
        )

        assertNull(repository.observeTranscriptionAttentionForRecording(RECORDING_ID).first())
    }

    private suspend fun insertRecording() {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = RECORDING_ID,
                sourceType = RecordingSourceType.LOCAL_IMPORT,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "sample.wav",
                displayName = "sample",
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 1_000L,
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
                state = RecordingState.ACTIVE,
            ),
        )
    }

    private suspend fun insertTranscription(
        id: String,
        state: String,
        createdAtMs: Long,
    ) {
        database.transcriptionDao().insertTranscription(
            TranscriptionEntity(
                id = id,
                recordingId = RECORDING_ID,
                mode = TranscriptionModeValue.HIGH_QUALITY,
                state = state,
                sourceCanonicalAssetId = "canonical-$id",
                sourceCanonicalSha256 = "a".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                totalSampleCount = 16_000L,
                pipelineVersion = 2,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1",
                vadModelId = "vad",
                vadModelVersion = "1",
                firstPassAsrModelId = "asr",
                firstPassAsrModelVersion = "1",
                secondPassAsrModelId = null,
                secondPassAsrModelVersion = null,
                punctuationModelId = null,
                punctuationModelVersion = null,
                languageConfig = "auto",
                configSnapshot = "{}",
                modelManifestDigest = "b".repeat(64),
                createdAtMs = createdAtMs,
                startedAtMs = createdAtMs,
                updatedAtMs = createdAtMs,
                completedAtMs = createdAtMs.takeIf { state !in TranscriptionStateValue.ACTIVE },
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private companion object {
        const val RECORDING_ID = "recording-qa4"
    }
}
