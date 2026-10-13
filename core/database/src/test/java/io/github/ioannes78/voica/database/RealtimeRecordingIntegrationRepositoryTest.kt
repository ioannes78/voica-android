package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimeRecordingIntegrationRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val recordingsRoot = Files.createTempDirectory("stage16a-integration").toFile()
    private val integration = RealtimeRecordingIntegrationRepository(database) { 10_000L }
    private val realtime = RealtimeTranscriptPersistenceRepository(database)
    private val library = RecordingLibraryRepository(database, recordingsRoot) { 10_000L }

    @After
    fun closeDatabase() {
        database.close()
        recordingsRoot.deleteRecursively()
    }

    @Test
    fun `direct realtime canonical capture creates verified lineage and visible transcript`() = runBlocking {
        seedRecording("rec-a", "device|note.opus|64044|2")
        seedCompletedRun("run-a", "rec-a")
        val segment = seedFinalSegment("run-a", "segment-a", 0, 32_000)
        seedUtterance(segment.id, "第一句", "第一句。", 0, 16_000)

        val canonicalAssetId =
            integration.commitDirectCanonicalCapture(
                RealtimeCanonicalCapture(
                    recordingId = "rec-a",
                    relativePath = "realtime/run-a/part01.wav",
                    sizeBytes = 64_044,
                    sha256 = "a".repeat(64),
                    profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                    pipelineVersion = 1,
                    sampleRateHz = 16_000,
                    channelCount = 1,
                    verifiedAtMs = 9_000,
                ),
            )

        val lineage =
            library.loadCanonicalTranscriptionLineage(
                recordingId = "rec-a",
                profileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
            )
        assertNotNull(lineage)
        assertEquals(canonicalAssetId, lineage!!.canonicalAssetId)
        assertEquals("a".repeat(64), lineage.canonicalSha256)

        val projected =
            integration.projectCompletedRealtimeRun(
                runId = "run-a",
                recordingId = "rec-a",
                canonicalAssetId = canonicalAssetId,
                canonicalSha256 = "a".repeat(64),
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                canonicalPipelineVersion = 1,
            )
        assertTrue(projected.alignedToCanonical)
        val tx = database.transcriptionDao().findTranscription(projected.transcriptionId!!)
        assertEquals(TranscriptionModeValue.REALTIME, tx!!.mode)
        assertEquals("CANONICAL_PCM16_16000_MONO_WAV_V1", tx.canonicalProfileId)
        assertEquals("第一句。", database.transcriptionDao().loadSegments(tx.id).single().finalText)
        assertEquals(tx.id, Stage12CContentRepository(database).resolveCurrentTranscriptionId("rec-a"))
    }

    @Test
    fun `segmented reconnect transcript is text visible without claiming canonical playback`() = runBlocking {
        seedRecording("rec-b", "device|note-b.opus|124120|62")
        seedCompletedRun("run-b", "rec-b")
        val first = seedFinalSegment("run-b", "segment-b0", 0, 16_000)
        val second = seedFinalSegment("run-b", "segment-b1", 1, 8_000)
        seedUtterance(first.id, "断线前", "断线前。", 1_000, 8_000)
        seedUtterance(second.id, "重连后", "重连后。", 0, 4_000)

        val projected =
            integration.projectCompletedRealtimeRun(
                runId = "run-b",
                recordingId = "rec-b",
                canonicalAssetId = null,
                canonicalSha256 = null,
                canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                canonicalPipelineVersion = 1,
            )
        assertTrue(!projected.alignedToCanonical)
        val tx = database.transcriptionDao().findTranscription(projected.transcriptionId!!)
        assertEquals(RealtimeTranscriptProfileValue.SEGMENTED_CAPTURE, tx!!.canonicalProfileId)
        assertNull(
            library.loadCanonicalTranscriptionLineage(
                recordingId = "rec-b",
                profileId = RealtimeTranscriptProfileValue.SEGMENTED_CAPTURE,
            ),
        )
        val projectedSegments = database.transcriptionDao().loadSegments(tx.id)
        assertEquals(listOf("断线前。", "重连后。"), projectedSegments.map { it.finalText })
        assertEquals(16_000L, projectedSegments[1].startSampleIndex)
    }

    @Test
    fun `remote pending media exposes device size without becoming a local source`() = runBlocking {
        seedRecording("rec-c", "device|note-c.opus|124120|62")
        integration.ensureRemotePendingAsset(
            recordingId = "rec-c",
            remoteIdentity = "device|note-c.opus|124120|62",
            filename = "note-c.opus",
            sizeBytes = 124_120,
        )

        val pending = database.recordingDao().findAsset("rec-c", AudioAssetRole.REMOTE_PENDING)
        assertNotNull(pending)
        assertEquals(124_120L, pending!!.sizeBytes)
        assertEquals(AudioIntegrityState.MISSING, pending.integrityState)
        assertNull(library.loadCanonicalConversionSource("rec-c", "CANONICAL_PCM16_16000_MONO_WAV_V1"))
        assertEquals(0L, library.recordingStorageUsage().originalAudioBytes)
        val row = library.observeLibrary(LibraryQueryCriteria()).first().single()
        assertEquals(124_120L, row.physicalAudioBytes)
    }

    private suspend fun seedRecording(id: String, remoteIdentity: String) {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = id,
                sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                sourceRemoteIdentity = remoteIdentity,
                sourceDeviceAddress = "AA:BB:CC:DD:EE:FF",
                originalFilename = remoteIdentity.split('|')[1],
                displayName = remoteIdentity.split('|')[1].substringBeforeLast('.'),
                recordedAtLocalIso = null,
                deviceReportedDurationMs = 62_000,
                mediaDurationMs = 62_000,
                downloadedAtMs = null,
                createdAtMs = 1_000,
                updatedAtMs = 1_000,
            ),
        )
        database.recordingDao().insertUserMetadataIgnore(
            RecordingUserMetadataEntity(id, null, false, 1_000),
        )
    }

    private suspend fun seedCompletedRun(runId: String, recordingId: String) {
        realtime.createRun(
            RealtimeTranscriptionRunEntity(
                id = runId,
                recordingId = recordingId,
                deviceRecordingIdentity = "note.opus",
                audioSourceType = RealtimeAudioSourceTypeValue.RECORDER_CARD,
                state = RealtimeTranscriptionStateValue.COMPLETED,
                modelId = "zipformer-small-bilingual",
                modelVersion = "1",
                modelRevision = 2,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "silero-vad-int8",
                vadModelVersion = "1",
                vadModelRevision = 3,
                punctuationModelId = "ct-transformer",
                punctuationModelVersion = "1",
                punctuationModelRevision = 4,
                languageConfig = "zh-en",
                configSnapshotSchemaVersion = 2,
                requestedConfigSnapshot = "{}",
                effectiveConfigSnapshot = "{}",
                modelManifestDigest = "digest",
                createdAtMs = 2_000,
                startedAtMs = 2_100,
                updatedAtMs = 9_000,
                endedAtMs = 9_000,
                endReason = "STOP",
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun seedFinalSegment(
        runId: String,
        segmentId: String,
        segmentIndex: Int,
        sampleCount: Long,
    ): RealtimeMediaSegmentEntity {
        val entity =
            RealtimeMediaSegmentEntity(
                id = segmentId,
                runId = runId,
                segmentIndex = segmentIndex,
                transportGeneration = segmentIndex.toLong() + 1,
                canonicalSessionId = segmentIndex.toLong() + 10,
                state = RealtimeMediaSegmentStateValue.FINALIZED,
                startedAtMs = 3_000L + segmentIndex,
                endedAtMs = 8_000L + segmentIndex,
                endReason = if (segmentIndex == 0) "TRANSPORT_CLOSED" else "STOP",
                canonicalSampleCount = sampleCount,
                localRelativePath = "run/$segmentId.wav",
                localFilename = "$segmentId.wav",
                deviceFilename = "note.opus",
                sizeBytes = sampleCount * 2 + 44,
                sha256 = if (segmentIndex == 0) "b".repeat(64) else "c".repeat(64),
                isCompleteDeviceRecording = false,
            )
        realtime.createSegment(entity)
        return entity
    }

    private suspend fun seedUtterance(
        segmentId: String,
        raw: String,
        final: String,
        start: Long,
        end: Long,
    ) {
        realtime.persistFinalUtterance(
            RealtimeUtteranceEntity(
                id = "$segmentId-utt-$start",
                segmentId = segmentId,
                utteranceIndex = database.realtimeTranscriptionDao().nextUtteranceIndex(segmentId),
                canonicalEpochId = 1,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                rawFinalText = raw,
                finalText = final,
                detectedLanguage = "zh",
                createdAtMs = 5_000 + start,
            ),
        )
    }
}