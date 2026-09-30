package io.github.ioannes78.voica.ble

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioContainer {
    WAV,
    RAW_OPUS,
    UNKNOWN,
}

data class LocalRecordingArtifact(
    val id: String,
    val sourceRemoteIdentity: String,
    val sourceDeviceAddress: String,
    val sourceFormat: DeviceAudioFormat,
    val displayFilename: String,
    val physicalFileName: String,
    val recordedAt: LocalDateTime?,
    val deviceReportedDurationMs: Long?,
    val downloadedAtMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val container: AudioContainer,
)

data class LocalDeleteResult(
    val deleted: Boolean,
    val error: FileOperationError? = null,
)

data class LocalDownloadCommitResult(
    val artifact: LocalRecordingArtifact,
    val firstDataPrefix: ByteArray,
)

class StreamingDownloadWriter internal constructor(
    private val tempFile: File,
    private val expectedBytes: Long,
) : FileTransferDataSink {
    private val fileOutput = try {
        FileOutputStream(tempFile, false)
    } catch (error: Throwable) {
        throw FileTransferSinkException(
            FileOperationError(
                FileOperationErrorCode.TEMP_FILE_CREATE_FAILED,
                error.message,
            ),
            error,
        )
    }
    private val output = BufferedOutputStream(fileOutput, BUFFER_SIZE)
    private val digest = MessageDigest.getInstance("SHA-256")
    private val prefix = ArrayList<Byte>(PREFIX_LIMIT)
    private var closed = false

    var bytesWritten: Long = 0
        private set

    override suspend fun write(bytes: ByteArray) {
        if (closed) {
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.LOCAL_WRITE_FAILED,
                    "writer is closed",
                ),
            )
        }
        try {
            output.write(bytes)
            digest.update(bytes)
            bytesWritten += bytes.size
            if (prefix.size < PREFIX_LIMIT) {
                bytes.take(PREFIX_LIMIT - prefix.size).forEach(prefix::add)
            }
        } catch (error: Throwable) {
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.LOCAL_WRITE_FAILED,
                    error.message,
                ),
                error,
            )
        }
    }

    suspend fun commit(finalFile: File): WriterCommitResult {
        if (closed) {
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.ATOMIC_COMMIT_FAILED,
                    "writer is already closed",
                ),
            )
        }

        if (expectedBytes > 0 && bytesWritten != expectedBytes) {
            abort()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.SIZE_MISMATCH,
                    "expected=$expectedBytes actual=$bytesWritten",
                ),
            )
        }

        try {
            output.flush()
            fileOutput.fd.sync()
        } catch (error: Throwable) {
            abort()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.FSYNC_FAILED,
                    error.message,
                ),
                error,
            )
        }

        try {
            output.close()
            closed = true
        } catch (error: Throwable) {
            abort()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.LOCAL_WRITE_FAILED,
                    error.message,
                ),
                error,
            )
        }

        val firstBytes = prefix.toByteArray()
        val container = detectContainer(firstBytes)
        finalFile.parentFile?.mkdirs()

        try {
            moveAtomically(tempFile, finalFile)
        } catch (error: Throwable) {
            tempFile.delete()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.ATOMIC_COMMIT_FAILED,
                    error.message,
                ),
                error,
            )
        }

        return WriterCommitResult(
            sizeBytes = bytesWritten,
            sha256 = digest.digest().toHex(),
            container = container,
            firstDataPrefix = firstBytes,
        )
    }

    override suspend fun abort() {
        if (!closed) {
            runCatching { output.close() }
            closed = true
        }
        runCatching { tempFile.delete() }
    }

    data class WriterCommitResult(
        val sizeBytes: Long,
        val sha256: String,
        val container: AudioContainer,
        val firstDataPrefix: ByteArray,
    )

    private fun detectContainer(bytes: ByteArray): AudioContainer =
        if (
            bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".encodeToByteArray())
        ) {
            AudioContainer.WAV
        } else {
            AudioContainer.UNKNOWN
        }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PREFIX_LIMIT = 64
    }
}

class LocalRecordingStore(
    baseDirectory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val legacyMetadataEnabled: Boolean = true,
) {
    private val baseDir = baseDirectory
    private val tempDir = File(baseDir, "temp")
    private val completedDir = File(baseDir, "completed")
    private val mutableRecordings = MutableStateFlow<List<LocalRecordingArtifact>>(emptyList())
    val recordings: StateFlow<List<LocalRecordingArtifact>> = mutableRecordings.asStateFlow()

    init {
        tempDir.mkdirs()
        completedDir.mkdirs()
        cleanupStaleTempFiles()
        if (legacyMetadataEnabled) reload()
    }

    @Synchronized
    fun isDownloaded(
        remoteIdentity: String,
        format: DeviceAudioFormat = DeviceAudioFormat.OPUS,
    ): Boolean =
        mutableRecordings.value.any { artifact ->
            artifact.sourceRemoteIdentity == remoteIdentity &&
                artifact.sourceFormat == format &&
                File(completedDir, artifact.physicalFileName).let { it.isFile && it.length() == artifact.sizeBytes }
        }

    @Synchronized
    fun artifactForRemote(
        remoteIdentity: String,
        format: DeviceAudioFormat = DeviceAudioFormat.OPUS,
    ): LocalRecordingArtifact? =
        mutableRecordings.value.firstOrNull {
            it.sourceRemoteIdentity == remoteIdentity && it.sourceFormat == format
        }

    fun expectedDownloadBytes(remote: RemoteDeviceFile, format: DeviceAudioFormat): Long =
        when (format) {
            DeviceAudioFormat.OPUS -> remote.sizeBytes
            DeviceAudioFormat.WAV -> remote.wavSizeBytes ?: 0L
        }

    fun estimatedDownloadBytes(remote: RemoteDeviceFile, format: DeviceAudioFormat): Long =
        when (format) {
            DeviceAudioFormat.OPUS -> remote.sizeBytes
            DeviceAudioFormat.WAV ->
                remote.wavSizeBytes
                    ?: remote.durationSeconds
                        ?.let {
                            it.coerceAtLeast(0L) *
                                PCM16_MONO_16K_BYTES_PER_SECOND +
                                WAV_HEADER_BYTES
                        }
                    ?: remote.sizeBytes.coerceAtLeast(MINIMUM_WAV_ESTIMATE_BYTES)
        }

    fun hasCapacity(expectedBytes: Long): Boolean {
        if (expectedBytes <= 0L) return true
        val margin = maxOf(MINIMUM_FREE_MARGIN_BYTES, expectedBytes / 10)
        return baseDir.usableSpace > expectedBytes + margin
    }

    fun prepare(
        remote: RemoteDeviceFile,
        format: DeviceAudioFormat = DeviceAudioFormat.OPUS,
    ): PreparedLocalDownload {
        val requestFilename = remote.downloadFilename(format)
            ?: throw IllegalArgumentException("remote recording cannot project ${format.name} filename")
        val id = if (format == DeviceAudioFormat.OPUS) {
            stableId(remote.identity)
        } else {
            stableId(remote.identity + "|format=" + format.name)
        }
        tempDir.mkdirs()
        completedDir.mkdirs()
        val tempFile = File(tempDir, "$id.part")
        runCatching { tempFile.delete() }
        val writer = StreamingDownloadWriter(
            tempFile = tempFile,
            expectedBytes = expectedDownloadBytes(remote, format),
        )
        return PreparedLocalDownload(
            id = id,
            remote = remote,
            format = format,
            requestFilename = requestFilename,
            tempFile = tempFile,
            writer = writer,
        )
    }

    suspend fun commit(
        prepared: PreparedLocalDownload,
        actualTransferFilename: String?,
    ): LocalDownloadCommitResult {
        val extension = extensionFor(actualTransferFilename ?: prepared.requestFilename)
        val physicalFileName = prepared.id + extension
        val finalFile = File(completedDir, physicalFileName)

        val writerResult = prepared.writer.commit(finalFile)
        val finalExtension = if (writerResult.container == AudioContainer.WAV) ".wav" else extension
        val actualFinalFile =
            if (finalExtension == extension) {
                finalFile
            } else {
                val corrected = File(completedDir, prepared.id + finalExtension)
                try {
                    moveReplacing(finalFile, corrected)
                } catch (error: Throwable) {
                    finalFile.delete()
                    throw FileTransferSinkException(
                        FileOperationError(
                            FileOperationErrorCode.ATOMIC_COMMIT_FAILED,
                            error.message,
                        ),
                        error,
                    )
                }
                corrected
            }

        val displayFilename = safeDisplayFilename(
            (actualTransferFilename ?: prepared.remote.displayFilename)
                .replaceSuffixForContainer(writerResult.container),
        )

        val resolvedContainer = writerResult.container

        if (prepared.format == DeviceAudioFormat.WAV && resolvedContainer != AudioContainer.WAV) {
            actualFinalFile.delete()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.INVALID_AUDIO_CONTAINER,
                    "requested WAV but downloaded bytes are not RIFF/WAVE",
                ),
            )
        }

        val artifact = LocalRecordingArtifact(
            id = prepared.id,
            sourceRemoteIdentity = prepared.remote.identity,
            sourceDeviceAddress = prepared.remote.deviceAddress,
            sourceFormat = prepared.format,
            displayFilename = displayFilename,
            physicalFileName = actualFinalFile.name,
            recordedAt = prepared.remote.recordedAt,
            deviceReportedDurationMs = prepared.remote.durationSeconds?.times(1_000L),
            downloadedAtMs = nowMs(),
            sizeBytes = writerResult.sizeBytes,
            sha256 = writerResult.sha256,
            container = resolvedContainer,
        )

        if (legacyMetadataEnabled) {
            writeMetadataAtomically(artifact)
            reload()
        } else {
            publishRuntimeArtifact(artifact)
        }
        return LocalDownloadCommitResult(
            artifact = artifact,
            firstDataPrefix = writerResult.firstDataPrefix,
        )
    }

    suspend fun abort(prepared: PreparedLocalDownload) {
        prepared.writer.abort()
    }

    @Synchronized
    fun delete(localId: String): LocalDeleteResult {
        val artifact = mutableRecordings.value.firstOrNull { it.id == localId }
            ?: return LocalDeleteResult(deleted = true)

        val audio = File(completedDir, artifact.physicalFileName)
        val metadata = metadataFile(localId)
        val audioDeleted = !audio.exists() || audio.delete()
        val metadataDeleted =
            !legacyMetadataEnabled || !metadata.exists() || metadata.delete()

        if (legacyMetadataEnabled) {
            reload()
        } else {
            mutableRecordings.value = mutableRecordings.value.filterNot { it.id == localId }
        }
        return if (audioDeleted && metadataDeleted) {
            LocalDeleteResult(deleted = true)
        } else {
            LocalDeleteResult(
                deleted = false,
                error = FileOperationError(
                    FileOperationErrorCode.LOCAL_DELETE_FAILED,
                    "audioDeleted=$audioDeleted metadataDeleted=$metadataDeleted",
                ),
            )
        }
    }

    @Synchronized
    fun reload() {
        if (!legacyMetadataEnabled) return
        completedDir.mkdirs()
        val loaded = completedDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
            .mapNotNull(::readMetadata)
            .filter { artifact ->
                val audio = File(completedDir, artifact.physicalFileName)
                audio.isFile && audio.length() == artifact.sizeBytes
            }
            .sortedByDescending { it.downloadedAtMs }
        mutableRecordings.value = loaded
    }

    @Synchronized
    private fun publishRuntimeArtifact(artifact: LocalRecordingArtifact) {
        mutableRecordings.value =
            (mutableRecordings.value.filterNot { it.id == artifact.id } + artifact)
                .sortedByDescending { it.downloadedAtMs }
    }

    fun cleanupStaleTempFiles(): Int {
        tempDir.mkdirs()
        var count = 0
        tempDir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.endsWith(".part") && file.delete()) {
                count += 1
            }
        }
        return count
    }

    fun resolveAudioFile(artifact: LocalRecordingArtifact): File =
        File(completedDir, artifact.physicalFileName)

    data class PreparedLocalDownload(
        val id: String,
        val remote: RemoteDeviceFile,
        val format: DeviceAudioFormat,
        val requestFilename: String,
        internal val tempFile: File,
        val writer: StreamingDownloadWriter,
    )

    private fun extensionFor(
        actualTransferFilename: String?,
    ): String {
        val extension = actualTransferFilename
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        return extension?.let { ".$it" } ?: ".bin"
    }

    private fun String.replaceSuffixForContainer(container: AudioContainer): String =
        when (container) {
            AudioContainer.WAV ->
                if (contains('.')) substringBeforeLast('.') + ".wav" else "$this.wav"
            AudioContainer.RAW_OPUS ->
                if (contains('.')) substringBeforeLast('.') + ".opus" else "$this.opus"
            AudioContainer.UNKNOWN -> this
        }

    private fun safeDisplayFilename(value: String): String {
        val cleaned = value
            .replace('\u0000', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .map { ch -> if (ch.isISOControl()) '_' else ch }
            .joinToString("")
            .trim()
        return cleaned.take(MAX_DISPLAY_FILENAME_LENGTH).ifEmpty { "recording" }
    }

    private fun stableId(remoteIdentity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(remoteIdentity.encodeToByteArray())
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
            }

    private fun metadataFile(id: String): File =
        File(completedDir, id + METADATA_SUFFIX)

    private fun writeMetadataAtomically(artifact: LocalRecordingArtifact) {
        val target = metadataFile(artifact.id)
        val temp = File(completedDir, artifact.id + METADATA_TEMP_SUFFIX)
        val properties = Properties().apply {
            setProperty("id", artifact.id)
            setProperty("sourceRemoteIdentity", artifact.sourceRemoteIdentity)
            setProperty("sourceDeviceAddress", artifact.sourceDeviceAddress)
            setProperty("sourceFormat", artifact.sourceFormat.name)
            setProperty("displayFilename", artifact.displayFilename)
            setProperty("physicalFileName", artifact.physicalFileName)
            setProperty("recordedAt", artifact.recordedAt?.toString().orEmpty())
            setProperty("deviceReportedDurationMs", artifact.deviceReportedDurationMs?.toString().orEmpty())
            setProperty("downloadedAtMs", artifact.downloadedAtMs.toString())
            setProperty("sizeBytes", artifact.sizeBytes.toString())
            setProperty("sha256", artifact.sha256)
            setProperty("container", artifact.container.name)
        }

        try {
            FileOutputStream(temp, false).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            moveReplacing(temp, target)
        } catch (error: Throwable) {
            temp.delete()
            throw FileTransferSinkException(
                FileOperationError(
                    FileOperationErrorCode.ATOMIC_COMMIT_FAILED,
                    "metadata: " + error.message,
                ),
                error,
            )
        }
    }

    private fun readMetadata(file: File): LocalRecordingArtifact? =
        runCatching {
            val properties = Properties()
            FileInputStream(file).use(properties::load)
            val artifact = LocalRecordingArtifact(
                id = properties.getProperty("id"),
                sourceRemoteIdentity = properties.getProperty("sourceRemoteIdentity"),
                sourceDeviceAddress = properties.getProperty("sourceDeviceAddress"),
                sourceFormat = properties.getProperty("sourceFormat")
                    ?.let { runCatching { DeviceAudioFormat.valueOf(it) }.getOrNull() }
                    ?: if (properties.getProperty("displayFilename").endsWith(".wav", ignoreCase = true)) {
                        DeviceAudioFormat.WAV
                    } else {
                        DeviceAudioFormat.OPUS
                    },
                displayFilename = properties.getProperty("displayFilename"),
                physicalFileName = properties.getProperty("physicalFileName"),
                recordedAt = properties.getProperty("recordedAt")
                    .takeIf { it.isNotBlank() }
                    ?.let(LocalDateTime::parse),
                deviceReportedDurationMs = properties.getProperty("deviceReportedDurationMs")
                    ?.takeIf { it.isNotBlank() }
                    ?.toLongOrNull()
                    ?: properties.getProperty("sourceRemoteIdentity")
                        .substringAfterLast('|', missingDelimiterValue = "")
                        .toLongOrNull()
                        ?.times(1_000L),
                downloadedAtMs = properties.getProperty("downloadedAtMs").toLong(),
                sizeBytes = properties.getProperty("sizeBytes").toLong(),
                sha256 = properties.getProperty("sha256"),
                container = AudioContainer.valueOf(properties.getProperty("container")),
            )
            if (
                artifact.container == AudioContainer.UNKNOWN &&
                artifact.displayFilename.endsWith(".opus", ignoreCase = true) &&
                artifact.sizeBytes > 0L &&
                artifact.sizeBytes % RAW_OPUS_PACKET_BYTES == 0L
            ) {
                artifact.copy(container = AudioContainer.RAW_OPUS)
            } else {
                artifact
            }
        }.getOrNull()

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val METADATA_SUFFIX = ".properties"
        const val METADATA_TEMP_SUFFIX = ".properties.part"
        const val MINIMUM_FREE_MARGIN_BYTES = 16L * 1024L * 1024L
        const val MAX_DISPLAY_FILENAME_LENGTH = 160
        const val RAW_OPUS_PACKET_BYTES = 40L
        const val PCM16_MONO_16K_BYTES_PER_SECOND = 32_000L
        const val WAV_HEADER_BYTES = 44L
        const val MINIMUM_WAV_ESTIMATE_BYTES = 1L * 1024L * 1024L
    }
}
