package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeSourceInterruptionRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val repository = RealtimeTranscriptPersistenceRepository(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `startup interruption reconciles phone runs but leaves recorder card untouched`() = runBlocking {
        repository.createRun(
            runEntity(
                id = "phone-active",
                source = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                state = RealtimeTranscriptionStateValue.ACTIVE,
            ),
        )
        repository.createRun(
            runEntity(
                id = "phone-finalizing",
                source = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                state = RealtimeTranscriptionStateValue.FINALIZING,
            ),
        )
        repository.createRun(
            runEntity(
                id = "recorder-active",
                source = RealtimeAudioSourceTypeValue.RECORDER_CARD,
                state = RealtimeTranscriptionStateValue.ACTIVE,
            ),
        )
        repository.createSegment(segmentEntity("phone-segment", "phone-active", 11L))
        repository.createSegment(segmentEntity("recorder-segment", "recorder-active", 22L))

        val reconciled =
            repository.interruptNonTerminalRunsForSource(
                audioSourceType = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                endedAtMs = 9_000L,
                endReason = "PROCESS_RESTART",
                errorCode = "PHONE_MIC_PROCESS_INTERRUPTED",
                errorMessage = "应用进程中断，手机实时转写未正常结束",
            )

        assertEquals(listOf("phone-active", "phone-finalizing"), reconciled)

        val phoneActive = requireNotNull(repository.getRun("phone-active"))
        assertEquals(RealtimeTranscriptionStateValue.INTERRUPTED, phoneActive.state)
        assertEquals(9_000L, phoneActive.endedAtMs)
        assertEquals("PROCESS_RESTART", phoneActive.endReason)
        assertEquals("PHONE_MIC_PROCESS_INTERRUPTED", phoneActive.errorCode)

        val phoneFinalizing = requireNotNull(repository.getRun("phone-finalizing"))
        assertEquals(RealtimeTranscriptionStateValue.INTERRUPTED, phoneFinalizing.state)
        assertEquals(9_000L, phoneFinalizing.endedAtMs)

        val phoneSegment = repository.getSegments("phone-active").single()
        assertEquals(RealtimeMediaSegmentStateValue.INTERRUPTED, phoneSegment.state)
        assertEquals(9_000L, phoneSegment.endedAtMs)
        assertEquals("PROCESS_RESTART", phoneSegment.endReason)
        assertEquals(0L, phoneSegment.canonicalSampleCount)
        assertEquals(false, phoneSegment.isCompleteDeviceRecording)

        val recorderRun = requireNotNull(repository.getRun("recorder-active"))
        assertEquals(RealtimeTranscriptionStateValue.ACTIVE, recorderRun.state)
        assertNull(recorderRun.endedAtMs)
        val recorderSegment = repository.getSegments("recorder-active").single()
        assertEquals(RealtimeMediaSegmentStateValue.ACTIVE, recorderSegment.state)
        assertNull(recorderSegment.endedAtMs)
    }

    @Test
    fun `completed phone run is not rewritten`() = runBlocking {
        repository.createRun(
            runEntity(
                id = "phone-completed",
                source = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                state = RealtimeTranscriptionStateValue.COMPLETED,
                endedAtMs = 5_000L,
            ),
        )

        val reconciled =
            repository.interruptNonTerminalRunsForSource(
                audioSourceType = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                endedAtMs = 9_000L,
                endReason = "PROCESS_RESTART",
                errorCode = "PHONE_MIC_PROCESS_INTERRUPTED",
                errorMessage = "应用进程中断，手机实时转写未正常结束",
            )

        assertEquals(emptyList<String>(), reconciled)
        val completed = requireNotNull(repository.getRun("phone-completed"))
        assertEquals(RealtimeTranscriptionStateValue.COMPLETED, completed.state)
        assertEquals(5_000L, completed.endedAtMs)
    }

    private fun runEntity(
        id: String,
        source: String,
        state: String,
        endedAtMs: Long? = null,
    ) = RealtimeTranscriptionRunEntity(
        id = id,
        recordingId = null,
        deviceRecordingIdentity = null,
        audioSourceType = source,
        state = state,
        modelId = "small",
        modelVersion = "1",
        modelRevision = 1L,
        runtimeId = "test-runtime",
        runtimeVersion = "1",
        vadModelId = "vad",
        vadModelVersion = "1",
        vadModelRevision = 1L,
        punctuationModelId = null,
        punctuationModelVersion = null,
        punctuationModelRevision = null,
        languageConfig = "zh,en",
        configSnapshotSchemaVersion = 1,
        requestedConfigSnapshot = "{}",
        effectiveConfigSnapshot = "{}",
        modelManifestDigest = "digest",
        createdAtMs = 1_000L,
        startedAtMs = 1_100L,
        updatedAtMs = endedAtMs ?: 1_100L,
        endedAtMs = endedAtMs,
        endReason = if (endedAtMs == null) null else "STOP",
        errorCode = null,
        errorMessage = null,
    )

    private fun segmentEntity(
        id: String,
        runId: String,
        sessionId: Long,
    ) = RealtimeMediaSegmentEntity(
        id = id,
        runId = runId,
        segmentIndex = 0,
        transportGeneration = null,
        canonicalSessionId = sessionId,
        state = RealtimeMediaSegmentStateValue.ACTIVE,
        startedAtMs = 1_200L,
        endedAtMs = null,
        endReason = null,
        canonicalSampleCount = 0L,
        localRelativePath = null,
        localFilename = null,
        deviceFilename = null,
        sizeBytes = null,
        sha256 = null,
        isCompleteDeviceRecording = false,
    )
}
