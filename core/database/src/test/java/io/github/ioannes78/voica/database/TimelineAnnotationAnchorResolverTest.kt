package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineAnnotationAnchorResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val integration = RealtimeRecordingIntegrationRepository(database) { 100L }
    private val realtime = RealtimeTranscriptPersistenceRepository(database)
    private val annotationRepository = TimelineAnnotationRepository(database, nowMs = { 50L }) { "annotation" }
    private val resolver = TimelineAnnotationAnchorResolver(database)

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `canonical annotation resolves only against matching verified canonical identity`() = runBlocking {
        seedRecording("rec-canonical")
        val assetId = seedCanonicalAsset("rec-canonical", sampleCount = 16_000L)
        val annotation =
            annotationRepository.create(
                TimelineAnnotationOwner.Recording("rec-canonical"),
                TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
                    sampleIndex = 8_000L,
                    canonicalAssetIdSnapshot = assetId,
                    canonicalSha256Snapshot = SHA,
                    canonicalProfileIdSnapshot = PROFILE,
                ),
            )

        assertEquals(
            TimelineAnnotationSeekResolution.Exact(8_000L),
            resolver.resolve(
                annotation,
                TimelinePlaybackMediaIdentity("rec-canonical", assetId, 16_000L),
            ),
        )

        val wrongIdentity = annotation.copy(anchorCanonicalAssetIdSnapshot = "wrong-asset")
        assertEquals(
            TimelineAnnotationSeekResolution.Unavailable(
                TimelineAnnotationSeekUnavailableReason.CANONICAL_ASSET_MISMATCH,
            ),
            resolver.resolve(
                wrongIdentity,
                TimelinePlaybackMediaIdentity("rec-canonical", assetId, 16_000L),
            ),
        )
    }

    @Test
    fun `single segment realtime annotation resolves through existing exact projection lineage`() = runBlocking {
        val recordingId = "rec-realtime"
        val runId = "run-realtime"
        seedRecording(recordingId)
        val assetId = seedCanonicalAsset(recordingId, sampleCount = 16_000L)
        seedCompletedRun(runId, recordingId)
        val segment = seedSegment(runId, "segment-0", 0, 16_000L, canonicalSessionId = 7L)
        realtime.persistFinalUtterance(
            RealtimeUtteranceEntity(
                id = "utterance-0",
                segmentId = segment.id,
                utteranceIndex = 0,
                canonicalEpochId = 1L,
                startSampleIndex = 1_000L,
                endSampleIndexExclusive = 12_000L,
                rawFinalText = "测试",
                finalText = "测试。",
                detectedLanguage = "zh",
                createdAtMs = 5L,
            ),
        )
        val projection =
            integration.projectCompletedRealtimeRun(
                runId = runId,
                recordingId = recordingId,
                canonicalAssetId = assetId,
                canonicalSha256 = SHA,
                canonicalProfileId = PROFILE,
                canonicalPipelineVersion = 1,
            )
        assertTrue(projection.alignedToCanonical)

        val annotation =
            annotationRepository.create(
                TimelineAnnotationOwner.Recording(recordingId),
                TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                    sampleIndex = 8_000L,
                    realtimeRunIdSnapshot = runId,
                    realtimeSegmentIdSnapshot = segment.id,
                    realtimeSegmentIndexSnapshot = 0,
                    canonicalSessionIdSnapshot = 7L,
                    canonicalEpochIdSnapshot = 1L,
                ),
            )

        assertEquals(
            TimelineAnnotationSeekResolution.Exact(8_000L),
            resolver.resolve(
                annotation,
                TimelinePlaybackMediaIdentity(recordingId, assetId, 16_000L),
            ),
        )
    }

    @Test
    fun `multiple realtime media segments are never concatenated into a fake playback anchor`() = runBlocking {
        val recordingId = "rec-segmented"
        val runId = "run-segmented"
        seedRecording(recordingId)
        val assetId = seedCanonicalAsset(recordingId, sampleCount = 16_000L)
        seedCompletedRun(runId, recordingId)
        val first = seedSegment(runId, "segment-a", 0, 8_000L, canonicalSessionId = 10L)
        seedSegment(runId, "segment-b", 1, 8_000L, canonicalSessionId = 11L)

        val annotation =
            annotationRepository.create(
                TimelineAnnotationOwner.Recording(recordingId),
                TimelineAnnotationAnchor(
                    domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                    sampleIndex = 4_000L,
                    realtimeRunIdSnapshot = runId,
                    realtimeSegmentIdSnapshot = first.id,
                    realtimeSegmentIndexSnapshot = 0,
                    canonicalSessionIdSnapshot = 10L,
                    canonicalEpochIdSnapshot = 1L,
                ),
            )

        assertEquals(
            TimelineAnnotationSeekResolution.Unavailable(
                TimelineAnnotationSeekUnavailableReason.REALTIME_SEGMENTED_CAPTURE,
            ),
            resolver.resolve(
                annotation,
                TimelinePlaybackMediaIdentity(recordingId, assetId, 16_000L),
            ),
        )
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
                mediaDurationMs = 1_000L,
                downloadedAtMs = null,
                createdAtMs = 1L,
                updatedAtMs = 1L,
            ),
        )
    }

    private suspend fun seedCanonicalAsset(recordingId: String, sampleCount: Long): String =
        integration.commitDirectCanonicalCapture(
            RealtimeCanonicalCapture(
                recordingId = recordingId,
                relativePath = "phone/$recordingId.wav",
                sizeBytes = 44L + sampleCount * 2L,
                sha256 = SHA,
                profileId = PROFILE,
                pipelineVersion = 1,
                sampleRateHz = 16_000,
                channelCount = 1,
                verifiedAtMs = 2L,
            ),
        )

    private suspend fun seedCompletedRun(runId: String, recordingId: String) {
        realtime.createRun(
            RealtimeTranscriptionRunEntity(
                id = runId,
                recordingId = recordingId,
                deviceRecordingIdentity = null,
                audioSourceType = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                state = RealtimeTranscriptionStateValue.COMPLETED,
                modelId = "model",
                modelVersion = "1",
                modelRevision = 1L,
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
                createdAtMs = 2L,
                startedAtMs = 2L,
                updatedAtMs = 4L,
                endedAtMs = 4L,
                endReason = "STOP",
                errorCode = null,
                errorMessage = null,
            ),
        )
    }

    private suspend fun seedSegment(
        runId: String,
        id: String,
        index: Int,
        sampleCount: Long,
        canonicalSessionId: Long,
    ): RealtimeMediaSegmentEntity {
        val segment =
            RealtimeMediaSegmentEntity(
                id = id,
                runId = runId,
                segmentIndex = index,
                transportGeneration = index.toLong(),
                canonicalSessionId = canonicalSessionId,
                state = RealtimeMediaSegmentStateValue.FINALIZED,
                startedAtMs = 2L,
                endedAtMs = 4L,
                endReason = "STOP",
                canonicalSampleCount = sampleCount,
                localRelativePath = "realtime/$id.wav",
                localFilename = "$id.wav",
                deviceFilename = null,
                sizeBytes = 44L + sampleCount * 2L,
                sha256 = SHA,
                isCompleteDeviceRecording = false,
            )
        realtime.createSegment(segment)
        return segment
    }

    private companion object {
        const val PROFILE = "stage16a2-test-pcm16-16k-mono"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
