package io.github.ioannes78.voica

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.ioannes78.voica.audio.AudioContainerDetector
import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.CompressedAudioProbe
import io.github.ioannes78.voica.audio.CompressedAudioProbeResult
import io.github.ioannes78.voica.audio.RawOpusValidationResult
import io.github.ioannes78.voica.audio.RawOpusValidator
import io.github.ioannes78.voica.audio.WavParseResult
import io.github.ioannes78.voica.audio.WavPcmParser
import io.github.ioannes78.voica.database.AudioDuplicateMatch
import io.github.ioannes78.voica.database.ImportedOriginalRegistration
import io.github.ioannes78.voica.database.RecordingDisplayNamePolicy
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.media.AndroidMediaProbe
import io.github.ioannes78.voica.opus.NativeOpusBackend
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface LocalAudioImportOutcome {
    data class Imported(
        val recordingId: String,
        val originalFilename: String,
    ) : LocalAudioImportOutcome

    data class Duplicate(
        val originalFilename: String,
        val matches: List<AudioDuplicateMatch>,
    ) : LocalAudioImportOutcome

    data class Unsupported(
        val originalFilename: String,
        val reason: String,
    ) : LocalAudioImportOutcome

    data class Failed(
        val originalFilename: String?,
        val reason: String,
    ) : LocalAudioImportOutcome
}

class LocalAudioImportCoordinator(
    context: Context,
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val mediaProbe: CompressedAudioProbe = AndroidMediaProbe(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val resolver = context.applicationContext.contentResolver
    private val serial = Mutex()
    private val stagingDir = File(recordingsRoot, "import-staging")
    private val importedDir = File(recordingsRoot, "imported")

    suspend fun import(
        uri: Uri,
        allowDuplicate: Boolean = false,
    ): LocalAudioImportOutcome =
        serial.withLock {
            withContext(ioDispatcher) {
                importLocked(uri, allowDuplicate)
            }
        }

    suspend fun cleanupStaleStaging() =
        withContext(ioDispatcher) {
            stagingDir.mkdirs()
            val cutoff = nowMs() - STALE_STAGING_AGE_MS
            stagingDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
                .forEach(File::delete)
        }

    private suspend fun importLocked(
        uri: Uri,
        allowDuplicate: Boolean,
    ): LocalAudioImportOutcome {
        val metadata = readMetadata(uri)
        val originalFilename = sanitizeMetadataName(
            metadata.displayName ?: "audio-${nowMs()}",
        )
        val sourceMimeType = runCatching { resolver.getType(uri) }.getOrNull()

        stagingDir.mkdirs()
        importedDir.mkdirs()

        metadata.sizeBytes?.takeIf { it > 0L }?.let { expected ->
            val required = expected + MIN_FREE_SPACE_RESERVE_BYTES
            if (recordingsRoot.usableSpace in 1 until required) {
                return LocalAudioImportOutcome.Failed(
                    originalFilename = originalFilename,
                    reason = "存储空间不足",
                )
            }
        }

        val staged = File(stagingDir, UUID.randomUUID().toString() + ".part")
        var committedFile: File? = null
        try {
            val copied = copyToStaging(uri, staged)
            if (copied.sizeBytes <= 0L) {
                return LocalAudioImportOutcome.Unsupported(
                    originalFilename = originalFilename,
                    reason = "音频文件为空",
                )
            }

            val validation =
                validateSource(
                    file = staged,
                    providerMimeType = sourceMimeType,
                ) ?: return LocalAudioImportOutcome.Unsupported(
                    originalFilename = originalFilename,
                    reason = "不支持或无法识别的音频格式",
                )

            val duplicates =
                repository.findExactAudioDuplicates(
                    sha256 = copied.sha256,
                    sizeBytes = copied.sizeBytes,
                )
            if (duplicates.isNotEmpty() && !allowDuplicate) {
                return LocalAudioImportOutcome.Duplicate(
                    originalFilename = originalFilename,
                    matches = duplicates,
                )
            }

            val extension =
                preferredExtension(
                    originalFilename = originalFilename,
                    validation = validation,
                )
            val finalFile =
                File(
                    importedDir,
                    UUID.randomUUID().toString() + extension,
                )
            check(!finalFile.exists())
            check(staged.renameTo(finalFile)) {
                "无法原子提交导入文件"
            }
            committedFile = finalFile

            val relativePath =
                finalFile.relativeTo(recordingsRoot).invariantSeparatorsPath
            val importedAt = nowMs()
            val recordingId =
                try {
                    repository.registerImportedOriginal(
                        ImportedOriginalRegistration(
                            originalFilename = originalFilename,
                            displayName =
                                RecordingDisplayNamePolicy.defaultDisplayName(
                                    originalFilename,
                                ),
                            relativePath = relativePath,
                            sourceMimeType = sourceMimeType ?: validation.mimeType,
                            container = validation.container,
                            codec = validation.codec,
                            sampleFormat = validation.sampleFormat,
                            sampleRateHz = validation.sampleRateHz,
                            channelCount = validation.channelCount,
                            mediaDurationMs = validation.durationMs,
                            sizeBytes = copied.sizeBytes,
                            sha256 = copied.sha256,
                            importedAtMs = importedAt,
                            providerAuthority = uri.authority,
                            sourceLastModifiedMs = metadata.lastModifiedMs,
                        ),
                    )
                } catch (error: Throwable) {
                    finalFile.delete()
                    committedFile = null
                    throw error
                }

            committedFile = null
            return LocalAudioImportOutcome.Imported(
                recordingId = recordingId,
                originalFilename = originalFilename,
            )
        } catch (error: CancellationException) {
            staged.delete()
            committedFile?.delete()
            throw error
        } catch (error: Throwable) {
            staged.delete()
            committedFile?.delete()
            return LocalAudioImportOutcome.Failed(
                originalFilename = originalFilename,
                reason = error.message ?: error::class.java.simpleName,
            )
        } finally {
            staged.delete()
        }
    }

    private suspend fun copyToStaging(
        uri: Uri,
        target: File,
    ): CopiedSource {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input =
            resolver.openInputStream(uri)
                ?: error("无法打开所选文件")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (
                        total % FREE_SPACE_RECHECK_INTERVAL_BYTES <
                        COPY_BUFFER_BYTES &&
                        recordingsRoot.usableSpace in 1 until MIN_FREE_SPACE_RESERVE_BYTES
                    ) {
                        error("存储空间不足")
                    }
                }
                output.flush()
                output.fd.sync()
            }
        }
        return CopiedSource(
            sizeBytes = total,
            sha256 =
                digest.digest()
                    .joinToString("") {
                        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
                    },
        )
    }

    private fun validateSource(
        file: File,
        providerMimeType: String?,
    ): ValidatedSource? {
        val prefix = file.inputStream().use { input ->
            val buffer = ByteArray(DETECTION_PREFIX_BYTES)
            val read = input.read(buffer)
            if (read <= 0) byteArrayOf() else buffer.copyOf(read)
        }
        val detected = AudioContainerDetector.detect(prefix)

        if (detected == AudioContainerKind.WAV) {
            val wav = WavPcmParser.parse(file)
            val info = (wav as? WavParseResult.Valid)?.info ?: return null
            if (!info.isPcm || info.bitsPerSample != 16) return null
            return ValidatedSource(
                container = "WAV",
                codec = "PCM",
                sampleFormat = "PCM16_LE",
                sampleRateHz = info.sampleRateHz,
                channelCount = info.channelCount,
                durationMs = info.durationUs / 1_000L,
                mimeType = providerMimeType ?: "audio/wav",
            )
        }

        if (detected in STANDARD_COMPRESSED_CONTAINERS) {
            val supported = mediaProbe.probe(file) as? CompressedAudioProbeResult.Supported
                ?: return null
            if (!isAllowedCompressed(detected, supported.descriptor.mimeType)) {
                return null
            }
            return ValidatedSource(
                container = detected.name,
                codec = supported.descriptor.mimeType,
                sampleFormat = null,
                sampleRateHz = supported.descriptor.sampleRateHz,
                channelCount = supported.descriptor.channelCount,
                durationMs = supported.descriptor.durationUs?.div(1_000L),
                mimeType = providerMimeType ?: supported.descriptor.mimeType,
            )
        }

        val raw =
            RawOpusValidator(NativeOpusBackend()).validate(file)
                as? RawOpusValidationResult.Valid
                ?: return null
        return ValidatedSource(
            container = "RAW_OPUS",
            codec = "OPUS",
            sampleFormat = null,
            sampleRateHz = null,
            channelCount = raw.value.channelCount,
            durationMs = raw.value.decodedDurationUs / 1_000L,
            mimeType = providerMimeType ?: "application/octet-stream",
        )
    }

    private fun isAllowedCompressed(
        container: AudioContainerKind,
        mimeType: String,
    ): Boolean =
        when (container) {
            AudioContainerKind.MP3 -> mimeType == "audio/mpeg"
            AudioContainerKind.MP4 -> mimeType == "audio/mp4a-latm"
            AudioContainerKind.AAC_ADTS -> mimeType == "audio/mp4a-latm"
            AudioContainerKind.FLAC -> mimeType == "audio/flac"
            AudioContainerKind.OGG_OPUS -> mimeType == "audio/opus"
            else -> false
        }

    private fun preferredExtension(
        originalFilename: String,
        validation: ValidatedSource,
    ): String {
        val original =
            originalFilename
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.matches(Regex("^[a-z0-9]{1,8}$")) }
        if (original != null) return ".$original"
        return when (validation.container) {
            "WAV" -> ".wav"
            AudioContainerKind.MP3.name -> ".mp3"
            AudioContainerKind.MP4.name -> ".m4a"
            AudioContainerKind.AAC_ADTS.name -> ".aac"
            AudioContainerKind.FLAC.name -> ".flac"
            AudioContainerKind.OGG_OPUS.name -> ".opus"
            "RAW_OPUS" -> ".opus"
            else -> ".bin"
        }
    }

    private fun readMetadata(uri: Uri): SourceMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        }

        var lastModifiedMs: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index =
                        cursor.getColumnIndex(
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        )
                    if (index >= 0 && !cursor.isNull(index)) {
                        lastModifiedMs = cursor.getLong(index)
                    }
                }
            }
        }

        return SourceMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes?.takeIf { it >= 0L },
            lastModifiedMs = lastModifiedMs?.takeIf { it > 0L },
        )
    }

    private fun sanitizeMetadataName(value: String): String =
        value
            .replace('\u0000', '_')
            .map { if (it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .take(MAX_METADATA_NAME)
            .ifBlank { "audio-${nowMs()}" }

    private data class SourceMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
        val lastModifiedMs: Long?,
    )

    private data class CopiedSource(
        val sizeBytes: Long,
        val sha256: String,
    )

    private data class ValidatedSource(
        val container: String,
        val codec: String?,
        val sampleFormat: String?,
        val sampleRateHz: Int?,
        val channelCount: Int?,
        val durationMs: Long?,
        val mimeType: String?,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val DETECTION_PREFIX_BYTES = 8 * 1024
        const val MIN_FREE_SPACE_RESERVE_BYTES = 8L * 1024L * 1024L
        const val FREE_SPACE_RECHECK_INTERVAL_BYTES = 4L * 1024L * 1024L
        const val STALE_STAGING_AGE_MS = 24L * 60L * 60L * 1_000L
        const val MAX_METADATA_NAME = 240

        val STANDARD_COMPRESSED_CONTAINERS =
            setOf(
                AudioContainerKind.MP3,
                AudioContainerKind.MP4,
                AudioContainerKind.AAC_ADTS,
                AudioContainerKind.FLAC,
                AudioContainerKind.OGG_OPUS,
            )
    }
}
