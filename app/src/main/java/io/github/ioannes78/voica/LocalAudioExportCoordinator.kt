package io.github.ioannes78.voica

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class AudioExportVariant {
    CANONICAL_WAV,
    ORIGINAL,
}

data class LocalAudioExportItemResult(
    val recordingId: String,
    val exported: Boolean,
    val displayName: String?,
    val destinationUri: Uri? = null,
    val error: String? = null,
)

data class LocalAudioBatchExportResult(
    val items: List<LocalAudioExportItemResult>,
) {
    val exportedCount: Int
        get() = items.count { it.exported }

    val failedCount: Int
        get() = items.size - exportedCount
}

sealed interface LocalAudioShareOutcome {
    data class Ready(
        val intent: Intent,
        val displayName: String,
    ) : LocalAudioShareOutcome

    data class Failed(
        val reason: String,
    ) : LocalAudioShareOutcome
}

class LocalAudioExportCoordinator(
    context: Context,
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val canonicalAudioCoordinator: CanonicalAudioCoordinator,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val shareDir = File(appContext.cacheDir, SHARE_DIRECTORY)

    suspend fun exportToDownloads(
        recordingIds: Collection<String>,
        variant: AudioExportVariant = AudioExportVariant.CANONICAL_WAV,
    ): LocalAudioBatchExportResult {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        return withContext(ioDispatcher) {
            val usedNames = loadExistingDownloadNames()
            val results = ArrayList<LocalAudioExportItemResult>()
            recordingIds.distinct().forEach { recordingId ->
                currentCoroutineContext().ensureActive()
                val source = resolveSource(recordingId, variant)
                if (source == null) {
                    results +=
                        LocalAudioExportItemResult(
                            recordingId = recordingId,
                            exported = false,
                            displayName = null,
                            error = "没有可导出的音频",
                        )
                    return@forEach
                }
                val name = allocateUniqueName(source.displayName, usedNames)
                usedNames += name
                results += exportSourceToDownloads(source, name)
            }
            LocalAudioBatchExportResult(results)
        }
    }

    suspend fun exportToTree(
        recordingIds: Collection<String>,
        treeUri: Uri,
        variant: AudioExportVariant = AudioExportVariant.CANONICAL_WAV,
    ): LocalAudioBatchExportResult =
        withContext(ioDispatcher) {
            val parent =
                runCatching {
                    val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
                }.getOrElse {
                    return@withContext LocalAudioBatchExportResult(
                        recordingIds.distinct().map { recordingId ->
                            LocalAudioExportItemResult(
                                recordingId = recordingId,
                                exported = false,
                                displayName = null,
                                error = "无法访问导出目录",
                            )
                        },
                    )
                }
            val usedNames = loadTreeNames(treeUri)
            val results = ArrayList<LocalAudioExportItemResult>()
            recordingIds.distinct().forEach { recordingId ->
                currentCoroutineContext().ensureActive()
                val source = resolveSource(recordingId, variant)
                if (source == null) {
                    results +=
                        LocalAudioExportItemResult(
                            recordingId = recordingId,
                            exported = false,
                            displayName = null,
                            error = "没有可导出的音频",
                        )
                    return@forEach
                }
                val name = allocateUniqueName(source.displayName, usedNames)
                usedNames += name
                var destination: Uri? = null
                try {
                    destination =
                        DocumentsContract.createDocument(
                            resolver,
                            parent,
                            source.mimeType,
                            name,
                        ) ?: error("无法创建导出文件")
                    copySource(source.file, destination)
                    results +=
                        LocalAudioExportItemResult(
                            recordingId = recordingId,
                            exported = true,
                            displayName = name,
                            destinationUri = destination,
                        )
                } catch (cancelled: CancellationException) {
                    destination?.let {
                        runCatching {
                            DocumentsContract.deleteDocument(resolver, it)
                        }
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    destination?.let {
                        runCatching {
                            DocumentsContract.deleteDocument(resolver, it)
                        }
                    }
                    results +=
                        LocalAudioExportItemResult(
                            recordingId = recordingId,
                            exported = false,
                            displayName = name,
                            error = error.message ?: "导出失败",
                        )
                }
            }
            LocalAudioBatchExportResult(results)
        }

    suspend fun exportToUri(
        recordingId: String,
        destinationUri: Uri,
        variant: AudioExportVariant = AudioExportVariant.CANONICAL_WAV,
    ): LocalAudioExportItemResult =
        withContext(ioDispatcher) {
            val source =
                resolveSource(recordingId, variant)
                    ?: return@withContext LocalAudioExportItemResult(
                        recordingId = recordingId,
                        exported = false,
                        displayName = null,
                        error = "没有可导出的音频",
                    )
            try {
                copySource(source.file, destinationUri)
                LocalAudioExportItemResult(
                    recordingId = recordingId,
                    exported = true,
                    displayName = source.displayName,
                    destinationUri = destinationUri,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                LocalAudioExportItemResult(
                    recordingId = recordingId,
                    exported = false,
                    displayName = source.displayName,
                    error = error.message ?: "导出失败",
                )
            }
        }

    suspend fun prepareShare(
        recordingId: String,
        variant: AudioExportVariant = AudioExportVariant.CANONICAL_WAV,
    ): LocalAudioShareOutcome =
        withContext(ioDispatcher) {
            val source =
                resolveSource(recordingId, variant)
                    ?: return@withContext LocalAudioShareOutcome.Failed("没有可分享的音频")
            shareDir.mkdirs()
            cleanupStaleShareCacheLocked()
            val target =
                File(
                    shareDir,
                    recordingId.take(12) + "-" + source.displayName,
                )
            try {
                copyFile(source.file, target)
                val uri =
                    FileProvider.getUriForFile(
                        appContext,
                        appContext.packageName + FILE_PROVIDER_SUFFIX,
                        target,
                    )
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(source.mimeType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser =
                    Intent.createChooser(send, "分享音频")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                LocalAudioShareOutcome.Ready(
                    intent = chooser,
                    displayName = source.displayName,
                )
            } catch (error: Throwable) {
                target.delete()
                LocalAudioShareOutcome.Failed(
                    error.message ?: "准备分享失败",
                )
            }
        }

    suspend fun cleanupStaleShareCache() =
        withContext(ioDispatcher) {
            cleanupStaleShareCacheLocked()
        }

    private suspend fun resolveSource(
        recordingId: String,
        variant: AudioExportVariant,
    ): ExportSource? {
        if (!repository.isRecordingActive(recordingId)) return null
        var recording = repository.loadRecording(recordingId) ?: return null

        if (variant == AudioExportVariant.CANONICAL_WAV) {
            var canonical = findCanonical(recording.assets)
            if (canonical == null) {
                when (canonicalAudioCoordinator.generate(recordingId)) {
                    CanonicalGenerationOutcome.Ready,
                    CanonicalGenerationOutcome.AlreadyReady,
                    -> Unit
                    CanonicalGenerationOutcome.NoSource,
                    is CanonicalGenerationOutcome.Failed,
                    -> return null
                }
                recording = repository.loadRecording(recordingId) ?: return null
                canonical = findCanonical(recording.assets) ?: return null
            }
            val file = managedFile(canonical.relativePath) ?: return null
            if (!file.isFile || file.length() != canonical.sizeBytes) return null
            return ExportSource(
                recordingId = recordingId,
                file = file,
                displayName =
                    ensureExtension(
                        sanitizeExportName(recording.displayName),
                        ".wav",
                    ),
                mimeType = "audio/wav",
            )
        }

        val original =
            recording.assets.firstOrNull {
                it.role == AudioAssetRole.IMPORTED_ORIGINAL &&
                    it.integrityState == AudioIntegrityState.VERIFIED
            } ?: recording.assets.firstOrNull {
                it.role == AudioAssetRole.DEVICE_OPUS &&
                    it.integrityState == AudioIntegrityState.VERIFIED
            } ?: recording.assets.firstOrNull {
                it.role == AudioAssetRole.DEVICE_WAV &&
                    it.integrityState == AudioIntegrityState.VERIFIED
            } ?: return null
        val file = managedFile(original.relativePath) ?: return null
        if (!file.isFile || file.length() != original.sizeBytes) return null
        val extension = extensionFor(original)
        return ExportSource(
            recordingId = recordingId,
            file = file,
            displayName =
                ensureExtension(
                    sanitizeExportName(recording.displayName),
                    extension,
                ),
            mimeType = mimeTypeFor(original),
        )
    }

    private fun findCanonical(assets: List<RecordingAsset>): RecordingAsset? =
        assets.firstOrNull {
            it.role == AudioAssetRole.CANONICAL_WAV &&
                it.integrityState == AudioIntegrityState.VERIFIED &&
                it.formatValidationState == AudioValidationState.VALID
        }

    private suspend fun exportSourceToDownloads(
        source: ExportSource,
        displayName: String,
    ): LocalAudioExportItemResult {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, source.mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Voica",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        var destination: Uri? = null
        return try {
            destination =
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: error("无法创建 Downloads 文件")
            copySource(source.file, destination)
            resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            LocalAudioExportItemResult(
                recordingId = source.recordingId,
                exported = true,
                displayName = displayName,
                destinationUri = destination,
            )
        } catch (cancelled: CancellationException) {
            destination?.let { resolver.delete(it, null, null) }
            throw cancelled
        } catch (error: Throwable) {
            destination?.let { runCatching { resolver.delete(it, null, null) } }
            LocalAudioExportItemResult(
                recordingId = source.recordingId,
                exported = false,
                displayName = displayName,
                error = error.message ?: "导出失败",
            )
        }
    }

    private fun loadExistingDownloadNames(): MutableSet<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return mutableSetOf()
        val names = linkedSetOf<String>()
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Voica/"
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                MediaStore.Downloads.RELATIVE_PATH + " = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (index >= 0 && !cursor.isNull(index)) {
                        names += cursor.getString(index)
                    }
                }
            }
        }
        return names
    }

    private fun loadTreeNames(treeUri: Uri): MutableSet<String> {
        val names = linkedSetOf<String>()
        runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    treeId,
                )
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index =
                    cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                while (cursor.moveToNext()) {
                    if (index >= 0 && !cursor.isNull(index)) {
                        names += cursor.getString(index)
                    }
                }
            }
        }
        return names
    }

    private fun allocateUniqueName(
        requested: String,
        usedNames: Set<String>,
    ): String {
        if (requested !in usedNames) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$extension"
            if (candidate !in usedNames) return candidate
            index += 1
        }
    }

    private suspend fun copySource(
        source: File,
        destination: Uri,
    ) {
        val output =
            resolver.openOutputStream(destination, "w")
                ?: error("无法打开导出目标")
        output.buffered().use { target ->
            FileInputStream(source).buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    target.write(buffer, 0, read)
                }
                target.flush()
            }
        }
    }

    private suspend fun copyFile(
        source: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        FileInputStream(source).buffered().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
    }

    private fun managedFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate =
            runCatching {
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

    private fun sanitizeExportName(value: String): String =
        value
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .map { if (it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
            .take(MAX_EXPORT_BASENAME)
            .ifBlank { "Voica-" + nowMs() }

    private fun ensureExtension(
        name: String,
        extension: String,
    ): String =
        if (name.lowercase(Locale.ROOT).endsWith(extension.lowercase(Locale.ROOT))) {
            name
        } else {
            name + extension
        }

    private fun extensionFor(asset: RecordingAsset): String =
        when (asset.container) {
            "WAV" -> ".wav"
            "MP3" -> ".mp3"
            "MP4" -> ".m4a"
            "AAC_ADTS" -> ".aac"
            "FLAC" -> ".flac"
            "OGG_OPUS" -> ".opus"
            "RAW_OPUS", "DEVICE_RAW_CANDIDATE" -> ".opus"
            else -> ".bin"
        }

    private fun mimeTypeFor(asset: RecordingAsset): String =
        when (asset.container) {
            "WAV" -> "audio/wav"
            "MP3" -> "audio/mpeg"
            "MP4" -> "audio/mp4"
            "AAC_ADTS" -> "audio/aac"
            "FLAC" -> "audio/flac"
            "OGG_OPUS" -> "audio/ogg"
            "RAW_OPUS", "DEVICE_RAW_CANDIDATE" -> "application/octet-stream"
            else -> "application/octet-stream"
        }

    private fun cleanupStaleShareCacheLocked() {
        if (!shareDir.exists()) return
        val cutoff = nowMs() - STALE_SHARE_AGE_MS
        shareDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private data class ExportSource(
        val recordingId: String,
        val file: File,
        val displayName: String,
        val mimeType: String,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_EXPORT_BASENAME = 180
        const val SHARE_DIRECTORY = "share"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val STALE_SHARE_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
