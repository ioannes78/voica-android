package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.CanonicalWavPcmSource
import io.github.ioannes78.voica.audio.FileSeekableAudioHandle
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.audio.PlaybackAudioSource
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import io.github.ioannes78.voica.audio.WavParseResult
import io.github.ioannes78.voica.audio.WavPcmInfo
import io.github.ioannes78.voica.audio.WavPcmParser
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomAudioSourceResolver(
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioSourceResolver, PcmSourceResolver {

    override suspend fun resolvePlaybackSource(
        recordingId: String,
    ): PlaybackAudioSource? {
        val resolved = resolveCanonicalAsset(recordingId) ?: return null
        val handle = withContext(ioDispatcher) {
            FileSeekableAudioHandle(resolved.file)
        }
        val wav = resolved.wavInfo
        return PlaybackAudioSource(
            descriptor = PlaybackAudioSourceDescriptor(
                recordingId = recordingId,
                assetId = resolved.asset.assetId,
                container = AudioContainerKind.WAV,
                durationUs = wav.durationUs,
                sampleRateHz = wav.sampleRateHz,
                channelCount = wav.channelCount,
                seekable = true,
                lengthBytes = resolved.asset.sizeBytes,
                bitsPerSample = wav.bitsPerSample,
                pcmDataOffsetBytes = wav.dataOffset,
                pcmDataSizeBytes = wav.dataSizeBytes,
                bytesPerFrame = wav.blockAlign,
                totalSampleCount = wav.frameCount,
            ),
            handle = handle,
        )
    }

    override suspend fun resolvePcmSource(
        recordingId: String,
    ): PcmSource? {
        val resolved = resolveCanonicalAsset(recordingId) ?: return null
        return withContext(ioDispatcher) {
            CanonicalWavPcmSource(resolved.file)
        }
    }

    private suspend fun resolveCanonicalAsset(
        recordingId: String,
    ): ResolvedCanonicalAsset? {
        val recording = repository.loadRecording(recordingId) ?: return null
        val asset = recording.assets.firstOrNull {
            it.role == AudioAssetRole.CANONICAL_WAV &&
                it.integrityState == AudioIntegrityState.VERIFIED &&
                it.formatValidationState == AudioValidationState.VALID
        } ?: return null

        return withContext(ioDispatcher) {
            resolveVerifiedFile(asset)
        }
    }

    private fun resolveVerifiedFile(
        asset: RecordingAsset,
    ): ResolvedCanonicalAsset? {
        val file = managedFile(asset.relativePath) ?: return null
        if (!file.isFile || file.length() != asset.sizeBytes) return null
        if (!sha256(file).equals(asset.sha256, ignoreCase = true)) return null

        val parsed = WavPcmParser.parse(file)
        val info = (parsed as? WavParseResult.Valid)?.info ?: return null
        if (!info.isCanonical) return null

        return ResolvedCanonicalAsset(
            asset = asset,
            file = file,
            wavInfo = info,
        )
    }

    private fun managedFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate = runCatching {
            File(recordingsRoot, relativePath).canonicalFile
        }.getOrNull() ?: return null
        if (
            candidate.path != root.path &&
            !candidate.path.startsWith(root.path + File.separator)
        ) {
            return null
        }
        return candidate
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private data class ResolvedCanonicalAsset(
        val asset: RecordingAsset,
        val file: File,
        val wavInfo: WavPcmInfo,
    )

    private companion object {
        const val HASH_BUFFER_BYTES = 64 * 1024
    }
}
