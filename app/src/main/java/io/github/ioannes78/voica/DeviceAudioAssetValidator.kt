package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.AudioContainerDetector
import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.RawOpusValidationResult
import io.github.ioannes78.voica.audio.RawOpusValidator
import io.github.ioannes78.voica.audio.WavParseResult
import io.github.ioannes78.voica.audio.WavPcmParser
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.LegacyAssetFormat
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.opus.NativeOpusBackend
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceAudioAssetValidator(
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun validate(
        remoteIdentity: String,
        format: LegacyAssetFormat,
    ): Boolean {
        val asset = repository.findDeviceAsset(remoteIdentity, format) ?: return false
        val file = managedFile(asset.relativePath) ?: run {
            repository.markAssetIntegrity(
                assetId = asset.assetId,
                integrityState = AudioIntegrityState.MISSING,
                validationState = AudioValidationState.CORRUPTED,
            )
            return false
        }
        if (!file.isFile || file.length() != asset.sizeBytes) {
            repository.markAssetIntegrity(
                assetId = asset.assetId,
                integrityState = if (file.exists()) {
                    AudioIntegrityState.CORRUPTED
                } else {
                    AudioIntegrityState.MISSING
                },
                validationState = AudioValidationState.CORRUPTED,
            )
            return false
        }

        repository.updateSourceValidation(
            assetId = asset.assetId,
            validationState = AudioValidationState.VALIDATING,
            container = asset.container,
            codec = asset.codec,
            sampleFormat = asset.sampleFormat,
            sampleRateHz = asset.sampleRateHz,
            channelCount = asset.channelCount,
        )

        return when (format) {
            LegacyAssetFormat.OPUS -> validateOpus(asset.assetId, file)
            LegacyAssetFormat.WAV -> validateWav(asset.assetId, file)
        }
    }

    private suspend fun validateOpus(
        assetId: String,
        file: File,
    ): Boolean {
        val kind = withContext(ioDispatcher) {
            AudioContainerDetector.detect(readPrefix(file))
        }
        if (kind != AudioContainerKind.DEVICE_RAW_CANDIDATE) {
            repository.updateSourceValidation(
                assetId = assetId,
                validationState = AudioValidationState.UNSUPPORTED,
                container = kind.name,
                codec = "OPUS",
            )
            return false
        }

        val result = withContext(ioDispatcher) {
            RawOpusValidator(NativeOpusBackend()).validate(file)
        }
        return when (result) {
            is RawOpusValidationResult.Valid -> {
                repository.updateSourceValidation(
                    assetId = assetId,
                    validationState = AudioValidationState.VALID,
                    container = "RAW_OPUS",
                    codec = "OPUS",
                    channelCount = result.value.channelCount,
                )
                true
            }

            is RawOpusValidationResult.UnsupportedFraming -> {
                repository.updateSourceValidation(
                    assetId = assetId,
                    validationState = AudioValidationState.UNSUPPORTED,
                    container = "DEVICE_RAW_CANDIDATE",
                    codec = "OPUS",
                )
                false
            }
        }
    }

    private suspend fun validateWav(
        assetId: String,
        file: File,
    ): Boolean {
        val parsed = withContext(ioDispatcher) {
            WavPcmParser.parse(file)
        }
        return when (parsed) {
            is WavParseResult.Invalid -> {
                repository.updateSourceValidation(
                    assetId = assetId,
                    validationState = AudioValidationState.INVALID,
                    container = "WAV",
                    codec = null,
                    sampleFormat = null,
                )
                false
            }

            is WavParseResult.Valid -> {
                val info = parsed.info
                val supported =
                    info.isPcm &&
                        info.bitsPerSample == 16 &&
                        info.blockAlign == info.channelCount * 2
                repository.updateSourceValidation(
                    assetId = assetId,
                    validationState =
                        if (supported) {
                            AudioValidationState.VALID
                        } else {
                            AudioValidationState.UNSUPPORTED
                        },
                    container = "WAV",
                    codec = if (info.isPcm) "PCM" else "WAVE_FORMAT_${info.audioFormat}",
                    sampleFormat =
                        if (info.isPcm) {
                            "PCM${info.bitsPerSample}_LE"
                        } else {
                            null
                        },
                    sampleRateHz = info.sampleRateHz,
                    channelCount = info.channelCount,
                )
                supported
            }
        }
    }

    private fun managedFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate = runCatching {
            File(recordingsRoot, relativePath).canonicalFile
        }.getOrNull() ?: return null
        return candidate.takeIf {
            it.path == root.path ||
                it.path.startsWith(root.path + File.separator)
        }
    }

    private fun readPrefix(file: File): ByteArray =
        file.inputStream().use { input ->
            val buffer = ByteArray(PREFIX_BYTES)
            val count = input.read(buffer)
            if (count <= 0) ByteArray(0) else buffer.copyOf(count)
        }

    private companion object {
        const val PREFIX_BYTES = 4 * 1024
    }
}
