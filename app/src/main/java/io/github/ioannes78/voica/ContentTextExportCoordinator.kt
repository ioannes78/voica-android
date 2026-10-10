package io.github.ioannes78.voica

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TextExportFormat(
    val extension: String,
    val mimeType: String,
) {
    TXT(".txt", "text/plain"),
    MARKDOWN(".md", "text/markdown"),
}

data class TextContentDocument(
    val baseName: String,
    val plainText: String,
    val markdownText: String,
)

data class TextExportResult(
    val exported: Boolean,
    val displayName: String,
    val destinationUri: Uri? = null,
    val error: String? = null,
    val mimeType: String? = null,
    val locationLabel: String? = null,
)

sealed interface TextShareOutcome {
    data class Ready(
        val intent: Intent,
    ) : TextShareOutcome

    data class Failed(
        val reason: String,
    ) : TextShareOutcome
}

class ContentTextExportCoordinator(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val clipboard =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val shareDir = File(appContext.cacheDir, SHARE_DIRECTORY)
    private val destinationStore = ExportDestinationStore(appContext)

    fun copyToClipboard(
        label: String,
        text: String,
    ): Boolean {
        if (text.isBlank()) return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    suspend fun prepareShare(
        document: TextContentDocument,
        format: TextExportFormat = TextExportFormat.TXT,
    ): TextShareOutcome =
        withContext(ioDispatcher) {
            val content = document.content(format)
            if (content.isBlank()) {
                return@withContext TextShareOutcome.Failed("没有可分享的文本")
            }

            if (
                format == TextExportFormat.TXT &&
                content.length <= DIRECT_SHARE_CHARACTER_LIMIT
            ) {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, content)
                return@withContext TextShareOutcome.Ready(
                    Intent.createChooser(send, "分享文本")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            runCatching {
                shareDir.mkdirs()
                cleanupStaleShareCacheLocked()
                val target =
                    File(
                        shareDir,
                        safeBaseName(document.baseName) + format.extension,
                    )
                target.writeText(content, Charsets.UTF_8)
                val uri =
                    FileProvider.getUriForFile(
                        appContext,
                        appContext.packageName + FILE_PROVIDER_SUFFIX,
                        target,
                    )
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(format.mimeType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                TextShareOutcome.Ready(
                    Intent.createChooser(send, "分享文本")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.getOrElse { error ->
                TextShareOutcome.Failed(error.message ?: "准备分享失败")
            }
        }

    suspend fun exportToDownloads(
        document: TextContentDocument,
        format: TextExportFormat,
    ): TextExportResult {
        val preferred = destinationStore.current()
        preferred.treeUri?.let { treeUri ->
            return exportToTree(document, format, treeUri)
        }

        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val result =
            withContext(ioDispatcher) {
                val displayName =
                    allocateDownloadName(
                        safeBaseName(document.baseName) + format.extension,
                    )
                val values =
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                        put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Voica",
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                var destination: Uri? = null
                try {
                    destination =
                        resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: error("无法创建 Downloads 文件")
                    writeDocument(document, format, destination)
                    resolver.update(
                        destination,
                        ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        },
                        null,
                        null,
                    )
                    TextExportResult(
                        exported = true,
                        displayName = displayName,
                        destinationUri = destination,
                        mimeType = format.mimeType,
                        locationLabel = ExportDestinationStore.DEFAULT_SHORT_LABEL,
                    )
                } catch (error: Throwable) {
                    destination?.let {
                        runCatching { resolver.delete(it, null, null) }
                    }
                    TextExportResult(
                        exported = false,
                        displayName = displayName,
                        error = error.message ?: "导出失败",
                        mimeType = format.mimeType,
                        locationLabel = ExportDestinationStore.DEFAULT_SHORT_LABEL,
                    )
                }
            }
        publishFeedback(result, format)
        return result
    }

    suspend fun exportToTree(
        document: TextContentDocument,
        format: TextExportFormat,
        treeUri: Uri,
    ): TextExportResult {
        val locationLabel = destinationStore.resolveTreeLabel(treeUri)
        val result =
            withContext(ioDispatcher) {
                val requested = safeBaseName(document.baseName) + format.extension
                val usedNames = loadTreeNames(treeUri)
                val displayName = allocateUniqueName(requested, usedNames)
                val parent =
                    runCatching {
                        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
                    }.getOrElse {
                        return@withContext TextExportResult(
                            exported = false,
                            displayName = displayName,
                            error = "无法访问导出目录",
                            mimeType = format.mimeType,
                            locationLabel = locationLabel,
                        )
                    }
                var destination: Uri? = null
                try {
                    destination =
                        DocumentsContract.createDocument(
                            resolver,
                            parent,
                            format.mimeType,
                            displayName,
                        ) ?: error("无法创建导出文件")
                    writeDocument(document, format, destination)
                    TextExportResult(
                        exported = true,
                        displayName = displayName,
                        destinationUri = destination,
                        mimeType = format.mimeType,
                        locationLabel = locationLabel,
                    )
                } catch (error: Throwable) {
                    destination?.let {
                        runCatching { DocumentsContract.deleteDocument(resolver, it) }
                    }
                    TextExportResult(
                        exported = false,
                        displayName = displayName,
                        error = error.message ?: "导出失败",
                        mimeType = format.mimeType,
                        locationLabel = locationLabel,
                    )
                }
            }
        publishFeedback(result, format)
        return result
    }

    suspend fun exportToUri(
        document: TextContentDocument,
        format: TextExportFormat,
        destinationUri: Uri,
    ): TextExportResult {
        val result =
            withContext(ioDispatcher) {
                val displayName = safeBaseName(document.baseName) + format.extension
                try {
                    writeDocument(document, format, destinationUri)
                    TextExportResult(
                        exported = true,
                        displayName = displayName,
                        destinationUri = destinationUri,
                        mimeType = format.mimeType,
                        locationLabel = "已选择位置",
                    )
                } catch (error: Throwable) {
                    TextExportResult(
                        exported = false,
                        displayName = displayName,
                        error = error.message ?: "导出失败",
                        mimeType = format.mimeType,
                        locationLabel = "已选择位置",
                    )
                }
            }
        publishFeedback(result, format)
        return result
    }

    private fun publishFeedback(
        result: TextExportResult,
        format: TextExportFormat,
    ) {
        if (result.exported) {
            publishExportSuccess(
                context = appContext,
                displayName = result.displayName,
                destinationUri = result.destinationUri,
                mimeType = result.mimeType ?: format.mimeType,
                locationLabel = result.locationLabel ?: ExportDestinationStore.DEFAULT_SHORT_LABEL,
            )
        } else {
            publishExportFailure(result.error ?: "导出失败")
        }
    }

    private fun writeDocument(
        document: TextContentDocument,
        format: TextExportFormat,
        destinationUri: Uri,
    ) {
        resolver.openOutputStream(destinationUri, "w")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { writer ->
                writer.write(document.content(format))
            } ?: error("无法写入导出文件")
    }

    private fun TextContentDocument.content(format: TextExportFormat): String =
        when (format) {
            TextExportFormat.TXT -> plainText
            TextExportFormat.MARKDOWN -> markdownText
        }

    private fun allocateDownloadName(requested: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return requested
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/Voica/"
        val used = linkedSetOf<String>()
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
                        used += cursor.getString(index)
                    }
                }
            }
        }
        return allocateUniqueName(requested, used)
    }

    private fun loadTreeNames(treeUri: Uri): Set<String> {
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
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
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
        used: Set<String>,
    ): String {
        if (requested !in used) return requested
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val ext = if (dot > 0) requested.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (candidate !in used) return candidate
            index += 1
        }
    }

    private fun safeBaseName(value: String): String =
        value
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .map { if (it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
            .take(MAX_BASENAME)
            .ifBlank { "Voica-" + nowMs() }

    private fun cleanupStaleShareCacheLocked() {
        if (!shareDir.exists()) return
        val cutoff = nowMs() - STALE_SHARE_AGE_MS
        shareDir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private companion object {
        const val DIRECT_SHARE_CHARACTER_LIMIT = 60_000
        const val MAX_BASENAME = 160
        const val SHARE_DIRECTORY = "share"
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val STALE_SHARE_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
