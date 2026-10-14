package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.database.RealtimeAudioSourceTypeValue
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptionRunEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import io.github.ioannes78.voica.database.VoicaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeStopFinalizationPersistenceTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: RealtimeTranscriptPersistenceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RealtimeTranscriptPersistenceRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stop timeout persists completed run with degraded transcript metadata`() = runBlocking {
        repository.createRun(activeRun("run-timeout"))
        val coordinator =
            RealtimeTranscriptPersistenceCoordinator(
                repository = repository,
                runId = "run-timeout",
                transportGenerationProvider = { 1L },
                localPathResolver = { it.name },
                clockMs = { 2_000L },
            )

        coordinator.finalizeRun(
            state = RealtimeTranscriptionStateValue.FAILED,
            endReason = Stage16AStopFinalizationPolicy.ERROR_CODE,
            errorCode = Stage16AStopFinalizationPolicy.ERROR_CODE,
            errorMessage = "old fatal wording",
        )

        val stored = repository.getRun("run-timeout")
        assertNotNull(stored)
        assertEquals(RealtimeTranscriptionStateValue.COMPLETED, stored?.state)
        assertEquals(Stage16AStopFinalizationPolicy.ERROR_CODE, stored?.endReason)
        assertEquals(Stage16AStopFinalizationPolicy.ERROR_CODE, stored?.errorCode)
        assertEquals(Stage16AStopFinalizationPolicy.DEGRADED_MESSAGE, stored?.errorMessage)
        assertEquals(2_000L, stored?.endedAtMs)
    }

    private fun activeRun(id: String) =
        RealtimeTranscriptionRunEntity(
            id = id,
            recordingId = null,
            deviceRecordingIdentity = "note.opus",
            audioSourceType = RealtimeAudioSourceTypeValue.RECORDER_CARD,
            state = RealtimeTranscriptionStateValue.ACTIVE,
            modelId = "zipformer-small-bilingual",
            modelVersion = "test",
            modelRevision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersion = "1.13.8",
            vadModelId = "silero-vad-int8",
            vadModelVersion = "test",
            vadModelRevision = 1,
            punctuationModelId = null,
            punctuationModelVersion = null,
            punctuationModelRevision = null,
            languageConfig = "zh-en",
            configSnapshotSchemaVersion = 1,
            requestedConfigSnapshot = "{}",
            effectiveConfigSnapshot = "{}",
            modelManifestDigest = "digest",
            createdAtMs = 1_000L,
            startedAtMs = 1_000L,
            updatedAtMs = 1_000L,
            endedAtMs = null,
            endReason = null,
            errorCode = null,
            errorMessage = null,
        )
}
