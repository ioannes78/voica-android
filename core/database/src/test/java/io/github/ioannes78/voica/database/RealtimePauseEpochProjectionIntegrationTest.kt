package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealtimePauseEpochProjectionIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val realtime = RealtimeTranscriptPersistenceRepository(database)
    private val integration = RealtimeRecordingIntegrationRepository(database) { 20_000L }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `phone pause resume projects second epoch into later canonical wav samples`() = runBlocking {
        val recordingId = "phone-rec-1"
        val runId = "phone-run-1"
        seedPhoneRecording(recordingId)
        val canonicalAssetId = seedCanonicalAsset(recordingId)
        seedCompletedPhoneRun(runId, recordingId)
        val segment = seedFinalSegment(runId, sampleCount = 24_000L)
        seedUtterance(segment.id, id = "u1", index = 0, epochId = 1L, start = 4_000L, end = 12_000L)
        seedUtterance(segment.id, id = "u2", index = 1, epochId = 2L, start = 1_000L, end = 6_000L)
        realtime.appendTimelineMarker(
            NewRealtimeTimelineMarker(
                runId = runId,
                segmentId = segment.id,
                type = RealtimeTimelineMarkerTypeValue.PAUSE,
                canonicalSessionId = segment.canonicalSessionId,
                canonicalEpochId = 1L,
                sampleIndex = 16_000L,
                createdAtMs = 5_000L,
            ),
        )
        realtime.appendTimelineMarker(
            NewRealtimeTimelineMarker(
                runId = runId,
                segmentId = segment.id,
                type = RealtimeTimelineMarkerTypeValue.RESUME,
                canonicalSessionId = segment.canonicalSessionId,
                canonicalEpochId = 2L,
                sampleIndex = 0L,
                createdAtMs = 6_000L,
            ),
        )

        val projected =
            integration.projectCompletedRealtimeRun(
                runId = runId,
                recordingId = recordingId,
                canonicalAssetId = canonicalAssetId,
                canonicalSha256 = SHA,
                canonicalProfileId = PROFILE,
                canonicalPipelineVersion = 1,
            )

        assertTrue(projected.alignedToCanonical)
        val transcript = database.transcriptionDao().findTranscription(checkNotNull(projected.transcriptionId))
        assertEquals(PROFILE, transcript?.canonicalProfileId)
        val projectedSegments = database.transcriptionDao().loadSegments(checkNotNull(projected.transcriptionId))
        assertEquals(2, projectedSegments.size)
        assertEquals(4_000L, projectedSegments[0].startSampleIndex)
        assertEquals(12_000L, projectedSegments[0].endSampleIndexExclusive)
        assertEquals(17_000L, projectedSegments[1].startSampleIndex)
        assertEquals(22_000L, projectedSegments[1].endSampleIndexExclusive)
    }

    @Test
    fun `missing pause proof refuses canonical aligned projection`() = runBlocking {
        val recordingId = "phone-rec-2"
        val runId = "phone-run-2"
        seedPhoneRecording(recordingId)
        val canonicalAssetId = seedCanonicalAsset(recordingId)
        seedCompletedPhoneRun(runId, recordingId)
        val segment = seedFinalSegment(runId, sampleCount = 24_000L)
        seedUtterance(segment.id, id = "u2", index = 0, epochId = 2L, start = 1_000L, end = 6_000L)
        realtime.appendTimelineMarker(
            NewRealtimeTimelineMarker(
                runId = runId,
                segmentId = segment.id,
                type = RealtimeTimelineMarkerTypeValue.RESUME,
                canonicalSessionId = segment.canonicalSessionId,
                canonicalEpochId = 2L,
                sampleIndex = 0L,
                createdAtMs = 6_000L,
            ),
        )

        val projected =
            integration.projectCompletedRealtimeRun(
                runId = runId,
                recordingId = recordingId,
                canonicalAssetId = canonicalAssetId,
                canonicalSha256 = SHA,
                canonicalProfileId = PROFILE,
                canonicalPipelineVersion = 1,
            )

        assertFalse(projected.alignedToCanonical)
        val transcript = database.transcriptionDao().findTranscription(checkNotNull(projected.transcriptionId))
        assertEquals(RealtimeTranscriptProfileValue.SEGMENTED_CAPTURE, transcript?.canonicalProfileId)
    }

    private suspend fun seedPhoneRecording(recordingId: String) {
        database.recordingDao().insertRecordingIgnore(
            RecordingEntity(
                id = recordingId,
                sourceType = RecordingSourceType.PHONE_MICROPHONE,
                sourceRemoteIdentity = null,
                sourceDeviceAddress = null,
                originalFilename = "$recordingId.wav",
                displayName = "手机录音",
                recordedAtLocalIso = "2026-10-15T11:00:00+08:00",
                deviceReportedDurationMs = null,
                mediaDurationMs = 1_500L,
                downloadedAtMs = null,
                createdAtMs = 1_000L,
                updatedAtMs = 10_000L,
            ),
        )
        database.recordingDao().insertUserMetadataIgnore(
            RecordingUserMetadataEntity(recordingId, null, false, 1_000L),
        )
    }

    private suspend fun seedCanonicalAsset(recordingId: String): String =
        integration.commitDirectCanonicalCapture(
            RealtimeCanonicalCapture(
                recordingId = recordingId,
                relativePath = "phone/$recordingId/$recordingId.wav",
                sizeBytes = 48_044L,
                sha256 = SHA,
                profileId = PROFILE,
                pipelineVersion = 1,
                sampleRateHz = 16_000,
                channelCount = 1,
                verifiedAtMs = 10_000L,
            ),
        )

    private suspend fun seedCompletedPhoneRun(runId: String, recordingId: String) {
        realtime.createRun(
            RealtimeTranscriptionRunEntity(
                id = runId,
                recordingId = recordingId,
                deviceRecordingIdentity = null,
                audioSourceType = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                state = RealtimeTranscriptionStateValue.COMPLETED,
                modelId = "zipformer-small-bilingual",
                modelVersion = "1",
                modelRevision = 1L,
                runtimeId = "sherpa-onnx",
                runtimeVersion = "1.13.8",
                vadModelId = "silero-vad-int8",
                vadModelVersion = "1",
                vadModelRevision = 1L,
                punctuationModelId = null,
                punctuationModelVersion = null,
                punctuationModelRevision = null,
                languageConfig = "en,zh",
                configSnapshotSchemaVersion = 1,
                requestedConfigSnapshot = "{}",
                effectiveConfigSnapshot = "{}",
                modelManifestDigest = "digest",
                createdAtMs = 2_000L,
                startedAtMs = 2_000L,
                updatedAtMs = 10_000L,
                endedAtMs = 10_000L,
                endReason = "STOP",
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun seedFinalSegment(
        runId: String,
        sampleCount: Long,
    ): RealtimeMediaSegmentEntity {
        val segment =
            RealtimeMediaSegmentEntity(
                id = "$runId-segment-0",
                runId = runId,
                segmentIndex = 0,
                transportGeneration = null,
                canonicalSessionId = 7L,
                state = RealtimeMediaSegmentStateValue.FINALIZED,
                startedAtMs = 2_000L,
                endedAtMs = 10_000L,
                endReason = "STOP",
                canonicalSampleCount = sampleCount,
                localRelativePath = "phone/$runId.wav",
                localFilename = "$runId.wav",
                deviceFilename = null,
                sizeBytes = 44L + sampleCount * 2L,
                sha256 = SHA,
                isCompleteDeviceRecording = false,
            )
        realtime.createSegment(segment)
        return segment
    }

    private suspend fun seedUtterance(
        segmentId: String,
        id: String,
        index: Int,
        epochId: Long,
        start: Long,
        end: Long,
    ) {
        realtime.persistFinalUtterance(
            RealtimeUtteranceEntity(
                id = id,
                segmentId = segmentId,
                utteranceIndex = index,
                canonicalEpochId = epochId,
                startSampleIndex = start,
                endSampleIndexExclusive = end,
                rawFinalText = id,
                finalText = "$id。",
                detectedLanguage = "zh",
                createdAtMs = 7_000L + index,
            ),
        )
    }

    private companion object {
        const val PROFILE = "CANONICAL_PCM16_16000_MONO_WAV_V1"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
