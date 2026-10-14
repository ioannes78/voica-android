package io.github.ioannes78.voica.ui.library

import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingSourceType

internal data class RecordingLocalMediaAvailability(
    val canonicalReady: Boolean,
    val originalReady: Boolean,
    val remotePending: Boolean,
    val remoteOriginalAvailable: Boolean,
    val remoteOriginalSizeBytes: Long?,
    val canonicalAsset: RecordingAsset?,
    val originalAsset: RecordingAsset?,
    val remotePendingAsset: RecordingAsset?,
    val displayAsset: RecordingAsset?,
) {
    val canGenerateCanonical: Boolean
        get() = originalReady

    val canShareOrExport: Boolean
        get() = canonicalReady || originalReady

    val canRequestOriginal: Boolean
        get() = originalReady || remoteOriginalAvailable
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
    val remoteIdentity = sourceRemoteIdentity?.trim()?.takeIf { it.isNotEmpty() }
    val remoteDevice = sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    val remoteOriginalAvailable =
        original == null &&
            sourceType == RecordingSourceType.DEVICE_DOWNLOAD &&
            remoteIdentity != null &&
            remoteDevice != null
    return RecordingLocalMediaAvailability(
        canonicalReady = canonical != null,
        originalReady = original != null,
        remotePending = remotePending != null,
        remoteOriginalAvailable = remoteOriginalAvailable,
        remoteOriginalSizeBytes =
            remotePending?.sizeBytes
                ?: remoteIdentity?.remoteSizeBytesOrNull(),
        canonicalAsset = canonical,
        originalAsset = original,
        remotePendingAsset = remotePending,
        displayAsset = original ?: canonical ?: remotePending,
    )
}

private fun String.remoteSizeBytesOrNull(): Long? =
    split('|').let { parts ->
        parts.getOrNull(parts.lastIndex - 1)?.toLongOrNull()?.takeIf { it >= 0L }
    }

private val LOCAL_ORIGINAL_ROLES =
    setOf(
        AudioAssetRole.IMPORTED_ORIGINAL,
        AudioAssetRole.DEVICE_OPUS,
        AudioAssetRole.DEVICE_WAV,
    )
