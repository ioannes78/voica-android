package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingLocalMediaAvailabilityTest {
    @Test
    fun remotePendingIsNotLocalAudio() {
        val availability =
            recording(
                asset(
                    role = AudioAssetRole.REMOTE_PENDING,
                    integrity = AudioIntegrityState.MISSING,
                    validation = AudioValidationState.UNVERIFIED,
                ),
            ).localMediaAvailability()

        assertTrue(availability.remotePending)
        assertFalse(availability.originalReady)
        assertFalse(availability.canonicalReady)
        assertFalse(availability.canGenerateCanonical)
        assertFalse(availability.canShareOrExport)
    }

    @Test
    fun verifiedDeviceOpusEnablesCanonicalGenerationAndExport() {
        val availability =
            recording(
                asset(
                    role = AudioAssetRole.DEVICE_OPUS,
                    integrity = AudioIntegrityState.VERIFIED,
                    validation = AudioValidationState.VALID,
                ),
            ).localMediaAvailability()

        assertFalse(availability.remotePending)
        assertTrue(availability.originalReady)
        assertTrue(availability.canGenerateCanonical)
        assertTrue(availability.canShareOrExport)
    }

    @Test
    fun directCanonicalCanBeSharedWithoutOriginalSource() {
        val availability =
            recording(
                asset(
                    role = AudioAssetRole.CANONICAL_WAV,
                    integrity = AudioIntegrityState.VERIFIED,
                    validation = AudioValidationState.VALID,
                ),
            ).localMediaAvailability()

        assertFalse(availability.originalReady)
        assertTrue(availability.canonicalReady)
        assertFalse(availability.canGenerateCanonical)
        assertTrue(availability.canShareOrExport)
    }

    private fun recording(vararg assets: RecordingAsset): RecordingLibraryItem =
        RecordingLibraryItem(
            id = "recording-1",
            sourceRemoteIdentity = "device|note.opus|1024|10",
            sourceDeviceAddress = "D1:A1:C4:00:0A:5E",
            originalFilename = "note.opus",
            displayName = "note",
            recordedAtLocalIso = null,
            deviceReportedDurationMs = 10_000L,
            downloadedAtMs = null,
            state = "ACTIVE",
            assets = assets.toList(),
            derivations = emptyList(),
        )

    private fun asset(
        role: String,
        integrity: String,
        validation: String,
    ): RecordingAsset =
        RecordingAsset(
            assetId = role,
            role = role,
            relativePath = "$role/audio",
            container = if (role == AudioAssetRole.CANONICAL_WAV) "WAV" else "OPUS",
            codec = if (role == AudioAssetRole.CANONICAL_WAV) "PCM" else "OPUS",
            sampleFormat = null,
            sampleRateHz = null,
            channelCount = null,
            sizeBytes = 1024L,
            sha256 = "a".repeat(64),
            integrityState = integrity,
            formatValidationState = validation,
        )
}
