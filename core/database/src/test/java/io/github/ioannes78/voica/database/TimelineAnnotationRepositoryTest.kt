package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.ArrayDeque
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineAnnotationRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `attached and phone pending annotations preserve strict ownership`() = runBlocking {
        seedRecording("recording-a")
        val ids = ArrayDeque(listOf("attached", "pending"))
        var now = 10L
        val repository = TimelineAnnotationRepository(database, nowMs = { now++ }) { ids.removeFirst() }

        val attached =
            repository.create(
                owner = TimelineAnnotationOwner.Recording("recording-a"),
                anchor = TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                    sampleIndex = 16_000,
                    canonicalAssetIdSnapshot = "canonical-a",
                    canonicalSha256Snapshot = "sha-a",
                    canonicalProfileIdSnapshot = "pcm16-16k-mono",
                ),
                noteText = "  重点  ",
            )
        assertEquals(TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING, attached.ownershipState)
        assertEquals("recording-a", attached.recordingId)
        assertNull(attached.pendingOwnerKind)
        assertEquals("重点", attached.noteText)

        val pending =
            repository.create(
                owner = TimelineAnnotationOwner.Pending(
                    TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                    "future-recording",
                ),
                anchor = TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                    sampleIndex = 8_000,
                    canonicalSessionIdSnapshot = 7,
                    canonicalEpochIdSnapshot = 1,
                ),
            )
        assertEquals(TimelineAnnotationOwnershipStateValue.PENDING_SESSION, pending.ownershipState)
        assertNull(pending.recordingId)

        seedRecording("future-recording")
        assertEquals(
            1,
            repository.attachPendingOwner(
                TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                "future-recording",
                "future-recording",
            ),
        )
        val promoted = requireNotNull(repository.get("pending"))
        assertEquals(TimelineAnnotationOwnershipStateValue.ATTACHED_RECORDING, promoted.ownershipState)
        assertEquals("future-recording", promoted.recordingId)
        assertNull(promoted.pendingOwnerKind)
        assertNull(promoted.pendingOwnerId)

        assertEquals(listOf("attached"), repository.observeRecording("recording-a").first().map { it.id })
        assertEquals(listOf("pending"), repository.observeRecording("future-recording").first().map { it.id })
    }

    @Test
    fun `realtime pending annotation promotes only after run attaches to same recording`() = runBlocking {
        seedRecording("recording-r")
        database.realtimeTranscriptionDao().insertRun(runEntity("run-r", recordingId = null))
        val repository = TimelineAnnotationRepository(database, idFactory = { "realtime-annotation" })
        repository.create(
            owner = TimelineAnnotationOwner.Pending(
                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                "run-r",
            ),
            anchor = TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                sampleIndex = 4_000,
                realtimeRunIdSnapshot = "run-r",
                realtimeSegmentIdSnapshot = "segment-r-1",
                realtimeSegmentIndexSnapshot = 1,
                canonicalSessionIdSnapshot = 42,
                canonicalEpochIdSnapshot = 3,
            ),
        )

        var rejected = false
        try {
            repository.attachPendingOwner(
                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                "run-r",
                "recording-r",
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertNull(requireNotNull(repository.get("realtime-annotation")).recordingId)

        assertEquals(1, database.realtimeTranscriptionDao().attachRecording("run-r", "recording-r", 10L))
        assertEquals(
            1,
            repository.attachPendingOwner(
                TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                "run-r",
                "recording-r",
            ),
        )
        assertEquals("recording-r", requireNotNull(repository.get("realtime-annotation")).recordingId)
    }

    @Test
    fun `startup reconciliation attaches resolvable owners and preserves unresolved annotations`() = runBlocking {
        seedRecording("phone-ready")
        seedRecording("realtime-ready")
        database.realtimeTranscriptionDao().insertRun(runEntity("run-ready", recordingId = "realtime-ready"))
        val ids = ArrayDeque(listOf("phone", "realtime", "unresolved"))
        val repository = TimelineAnnotationRepository(database, idFactory = { ids.removeFirst() })
        val canonical = TimelineAnnotationAnchor(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, 1_000)

        repository.create(
            TimelineAnnotationOwner.Pending(TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE, "phone-ready"),
            canonical,
        )
        repository.create(
            TimelineAnnotationOwner.Pending(TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN, "run-ready"),
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                sampleIndex = 2_000,
                realtimeRunIdSnapshot = "run-ready",
                realtimeSegmentIdSnapshot = "seg-ready",
                realtimeSegmentIndexSnapshot = 0,
                canonicalSessionIdSnapshot = 10,
            ),
        )
        repository.create(
            TimelineAnnotationOwner.Pending(TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE, "missing-recording"),
            canonical,
        )

        val result = repository.reconcilePending()
        assertEquals(2, result.attachedCount)
        assertEquals(1, result.unresolvedCount)
        assertEquals("phone-ready", requireNotNull(repository.get("phone")).recordingId)
        assertEquals("realtime-ready", requireNotNull(repository.get("realtime")).recordingId)
        assertNull(requireNotNull(repository.get("unresolved")).recordingId)
    }

    @Test
    fun `realtime anchor rejects incomplete segment identity instead of inventing position`() = runBlocking {
        val repository = TimelineAnnotationRepository(database, idFactory = { "bad" })
        var rejected = false
        try {
            repository.create(
                TimelineAnnotationOwner.Pending(
                    TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                    "run-missing",
                ),
                TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                    sampleIndex = 100,
                    realtimeRunIdSnapshot = "run-missing",
                    realtimeSegmentIdSnapshot = null,
                    realtimeSegmentIndexSnapshot = null,
                    canonicalSessionIdSnapshot = null,
                ),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private suspend fun seedRecording(id: String) {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = id,
                sourceType = RecordingSourceType.PHONE_MICROPHONE,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "$id.wav",
                displayName = id,
                recordedAtLocalIso = null,
                deviceReportedDurationMs = null,
                mediaDurationMs = null,
                downloadedAtMs = null,
                createdAtMs = 1,
                updatedAtMs = 1,
            ),
        )
    }

    private fun runEntity(id: String, recordingId: String?): RealtimeTranscriptionRunEntity =
        RealtimeTranscriptionRunEntity(
            id = id,
            recordingId = recordingId,
            deviceRecordingIdentity = null,
            audioSourceType = RealtimeAudioSourceTypeValue.RECORDER_CARD,
            state = RealtimeTranscriptionStateValue.ACTIVE,
            modelId = "model",
            modelVersion = "1",
            modelRevision = 1,
            runtimeId = "runtime",
            runtimeVersion = "1",
            vadModelId = null,
            vadModelVersion = null,
            vadModelRevision = null,
            punctuationModelId = null,
            punctuationModelVersion = null,
            punctuationModelRevision = null,
            languageConfig = "zh",
            configSnapshotSchemaVersion = 1,
            requestedConfigSnapshot = "{}",
            effectiveConfigSnapshot = "{}",
            modelManifestDigest = "digest",
            createdAtMs = 1,
            startedAtMs = 1,
            updatedAtMs = 1,
            endedAtMs = null,
            endReason = null,
            errorCode = null,
            errorMessage = null,
        )
}
