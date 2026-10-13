package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryItem

internal data class RecordingLocalMediaAvailability(
    val canonicalReady: Boolean,
    val originalReady: Boolean,
    val remotePending: Boolean,
    val displayAsset: RecordingAsset?,
) {
    val canGenerateCanonical: Boolean
        get() = originalReady

    val canShareOrExport: Boolean
        get() = canonicalReady || originalReady
}

internal fun RecordingLibraryItem.localMediaAvailability(): RecordingLocalMediaAvailability {
    val original =
        assets.firstOrNull { asset ->
            asset.role in LOCAL_ORIGINAL_ROLES &&
                asset.integrityState == AudioIntegrityState.VERIFIED
        }
    val canonical =
        assets.firstOrNull { asset ->
            asset.role == AudioAssetRole.CANONICAL_WAV &&
                asset.integrityState == AudioIntegrityState.VERIFIED &&
                asset.formatValidationState == AudioValidationState.VALID
        }
    val remotePending =
        assets.firstOrNull { asset ->
            asset.role == AudioAssetRole.REMOTE_PENDING &&
                asset.integrityState != AudioIntegrityState.VERIFIED
        }
    return RecordingLocalMediaAvailability(
        canonicalReady = canonical != null,
        originalReady = original != null,
        remotePending = remotePending != null,
        displayAsset = original ?: remotePending ?: canonical,
    )
}

private val LOCAL_ORIGINAL_ROLES =
    setOf(
        AudioAssetRole.IMPORTED_ORIGINAL,
        AudioAssetRole.DEVICE_OPUS,
        AudioAssetRole.DEVICE_WAV,
    )
