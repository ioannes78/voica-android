package io.github.ioannes78.voica.ui.annotation

import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingDerivation
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineAnnotationAnchorCaptureTest {
    @Test
    fun verifiedCanonicalPlaybackCapturesAssetShaAndProfile() {
        val recording = recording()
        val playback = playback(recordingId = RECORDING_ID, sample = 12_000L)

        val anchor = captureCanonicalAnnotationAnchor(recording, playback, 12_000L)

        requireNotNull(anchor)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, anchor.domain)
        assertEquals(12_000L, anchor.sampleIndex)
        assertEquals(ASSET_ID, anchor.canonicalAssetIdSnapshot)
        assertEquals(SHA, anchor.canonicalSha256Snapshot)
        assertEquals(PROFILE, anchor.canonicalProfileIdSnapshot)
    }

    @Test
    fun playbackForDifferentRecordingIsRejected() {
        assertNull(
            captureCanonicalAnnotationAnchor(
                recording(),
                playback(recordingId = "other", sample = 4_000L),
                4_000L,
            ),
        )
    }

    @Test
    fun unverifiedOrInvalidCanonicalAssetIsRejected() {
        val unverified =
            recording(
                asset =
                    canonicalAsset().copy(
                        integrityState = AudioIntegrityState.UNVERIFIED,
                    ),
            )
        val invalid =
            recording(
                asset =
                    canonicalAsset().copy(
                        formatValidationState = AudioValidationState.INVALID,
                    ),
            )

        assertNull(captureCanonicalAnnotationAnchor(unverified, playback(), 4_000L))
        assertNull(captureCanonicalAnnotationAnchor(invalid, playback(), 4_000L))
    }

    @Test
    fun nonCanonicalPlaybackSourceIsRejected() {
        val recording =
            recording(
                asset =
                    canonicalAsset().copy(
                        role = AudioAssetRole.DEVICE_WAV,
                    ),
            )

        assertNull(captureCanonicalAnnotationAnchor(recording, playback(), 4_000L))
    }

    @Test
    fun sampleOutsidePlaybackDurationIsRejected() {
        val playback = playback().copy(durationSampleCount = 16_000L)

        assertNull(captureCanonicalAnnotationAnchor(recording(), playback, 16_001L))
        assertNull(captureCanonicalAnnotationAnchor(recording(), playback, -1L))
    }

    private fun recording(
        asset: RecordingAsset = canonicalAsset(),
    ): RecordingLibraryItem =
        RecordingLibraryItem(
            id = RECORDING_ID,
            sourceRemoteIdentity = null,
            sourceDeviceAddress = null,
            originalFilename = "recording.wav",
            displayName = "recording",
            recordedAtLocalIso = null,
            deviceReportedDurationMs = 2_000L,
            downloadedAtMs = null,
            state = "ACTIVE",
            assets = listOf(asset),
            derivations =
                listOf(
                    RecordingDerivation(
                        profileId = PROFILE,
                        sourceSha256 = "b".repeat(64),
                        sourceAssetId = "source",
                        outputAssetId = ASSET_ID,
                        pipelineVersion = 1,
                        state = AudioDerivationState.READY,
                        errorCode = null,
                        errorDetail = null,
                    ),
                ),
        )

    private fun canonicalAsset(): RecordingAsset =
        RecordingAsset(
            assetId = ASSET_ID,
            role = AudioAssetRole.CANONICAL_WAV,
            relativePath = "canonical/$ASSET_ID.wav",
            container = "WAV",
            codec = "PCM",
            sampleFormat = "PCM16_LE",
            sampleRateHz = 16_000,
            channelCount = 1,
            sizeBytes = 64_044L,
            sha256 = SHA,
            integrityState = AudioIntegrityState.VERIFIED,
            formatValidationState = AudioValidationState.VALID,
        )

    private fun playback(
        recordingId: String = RECORDING_ID,
        sample: Long = 4_000L,
    ): PlaybackSnapshot =
        PlaybackSnapshot(
            recordingId = recordingId,
            sourceAssetId = ASSET_ID,
            state = PlaybackState.PAUSED,
            positionSampleIndex = sample,
            durationSampleCount = 32_000L,
        )

    private companion object {
        const val RECORDING_ID = "recording-1"
        const val ASSET_ID = "canonical-asset"
        const val PROFILE = "CANONICAL_PCM16_MONO_16K_V1"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
