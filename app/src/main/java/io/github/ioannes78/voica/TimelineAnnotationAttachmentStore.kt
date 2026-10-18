package io.github.ioannes78.voica

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.ioannes78.voica.database.RecordingState
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentEntity
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentKindValue
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentLifecycleStateValue
import io.github.ioannes78.voica.database.TimelineAnnotationAttachmentSourceValue
import io.github.ioannes78.voica.database.TimelineAnnotationDao
import io.github.ioannes78.voica.database.TimelineAnnotationRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TimelineAnnotationCameraCaptureTarget(
    val annotationId: String,
    val attachmentId: String,
    val file: File,
    val uri: Uri,
)

data class TimelineAnnotationAttachmentReconcileResult(
    val recoveredDeletingRows: Int,
    val removedMissingRows: Int,
    val removedOrphanFiles: Int,
    val failedPaths: List<String>,
)

data class TimelineAnnotationDeletingRecordingCleanup(
    val failedPathsByRecording: Map<String, List<String>>,
) {
    val blockedRecordingIds: Set<String>
        get() = failedPathsByRecording.keys
}

/**
 * App-private physical-file owner for Stage 16A.2 photo attachments.
 *
 * Durable DB rows never retain external gallery/camera URIs. Files are copied into
 * files/timeline_annotations/<annotationId>/ and only then registered as READY. Deletion uses a
 * DELETING row as the crash-recovery seam. This store never touches Recording audio storage.
 */
class TimelineAnnotationAttachmentStore(
    context: Context,
    private val database: VoicaDatabase,
    private val annotationRepository: TimelineAnnotationRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dao: TimelineAnnotationDao = database.timelineAnnotationDao()
    private val root = File(appContext.filesDir, ATTACHMENT_ROOT_NAME)
    private val cameraStagingRoot = File(appContext.cacheDir, CAMERA_STAGING_DIR_NAME)
    private val serial = Mutex()

    suspend fun createCameraCaptureTarget(annotationId: String): TimelineAnnotationCameraCaptureTarget =
        serial.withLock {
            withContext(ioDispatcher) {
                requireAnnotation(annotationId)
                validateId(annotationId, "annotationId")
                cameraStagingRoot.mkdirs()
                val attachmentId = idFactory().also { validateId(it, "attachmentId") }
                val file = File(cameraStagingRoot, "$attachmentId.jpg")
                check(!file.exists()) { "camera staging target already exists" }
                check(file.createNewFile()) { "unable to create camera staging target" }
                val uri =
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file,
                    )
                TimelineAnnotationCameraCaptureTarget(
                    annotationId = annotationId,
                    attachmentId = attachmentId,
                    file = file,
                    uri = uri,
                )
            }
        }

    suspend fun discardCameraCaptureTarget(target: TimelineAnnotationCameraCaptureTarget) =
        serial.withLock {
            withContext(ioDispatcher) {
                if (isInside(target.file, cameraStagingRoot)) target.file.delete()
            }
        }

    suspend fun commitCameraCapture(
        target: TimelineAnnotationCameraCaptureTarget,
    ): TimelineAnnotationAttachmentEntity =
        serial.withLock {
            withContext(ioDispatcher) {
                requireAnnotation(target.annotationId)
                validateId(target.annotationId, "annotationId")
                validateId(target.attachmentId, "attachmentId")
                require(isInside(target.file, cameraStagingRoot)) { "camera target is outside staging root" }
                require(target.file.isFile && target.file.length() > 0L) { "camera image is empty" }
                try {
                    commitLocalFile(
                        annotationId = target.annotationId,
                        attachmentId = target.attachmentId,
                        sourceFile = target.file,
                        source = TimelineAnnotationAttachmentSourceValue.CAMERA,
                        providerMimeType = "image/jpeg",
                    )
                } finally {
                    target.file.delete()
                }
            }
        }

    suspend fun importGallery(
        annotationId: String,
        uri: Uri,
    ): TimelineAnnotationAttachmentEntity =
        serial.withLock {
            withContext(ioDispatcher) {
                requireAnnotation(annotationId)
                validateId(annotationId, "annotationId")
                val attachmentId = idFactory().also { validateId(it, "attachmentId") }
                val stagingDir = File(root, STAGING_DIR_NAME).apply { mkdirs() }
                val staged = File(stagingDir, "$attachmentId.part")
                try {
                    val input = resolver.openInputStream(uri) ?: error("无法读取所选图片")
                    input.use { source ->
                        FileOutputStream(staged).use { output ->
                            source.copyTo(output, COPY_BUFFER_BYTES)
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    require(staged.length() > 0L) { "所选图片为空" }
                    commitLocalFile(
                        annotationId = annotationId,
                        attachmentId = attachmentId,
                        sourceFile = staged,
                        source = TimelineAnnotationAttachmentSourceValue.GALLERY,
                        providerMimeType = resolver.getType(uri),
                    )
                } finally {
                    staged.delete()
                    cleanupEmptyDirectory(stagingDir)
                }
            }
        }

    suspend fun deleteAttachment(attachmentId: String): List<String> =
        serial.withLock {
            withContext(ioDispatcher) {
                val attachment = dao.getAttachment(attachmentId) ?: return@withContext emptyList()
                deleteAttachmentLocked(attachment)
            }
        }

    suspend fun deleteAttachmentsForAnnotation(annotationId: String): List<String> =
        serial.withLock {
            withContext(ioDispatcher) {
                val failures = mutableListOf<String>()
                dao.getAttachments(annotationId).forEach { failures += deleteAttachmentLocked(it) }
                failures.distinct()
            }
        }

    suspend fun deleteAttachmentsForRecording(recordingId: String): List<String> =
        serial.withLock {
            withContext(ioDispatcher) {
                val failures = mutableListOf<String>()
                dao.getAttachmentsForRecording(recordingId).forEach { failures += deleteAttachmentLocked(it) }
                failures.distinct()
            }
        }

    /**
     * Must run before RecordingLibraryRepository.reconcilePendingDeletes(). A failed physical delete
     * blocks that Recording from DB deletion so its attachment rows remain available for retry.
     */
    suspend fun cleanupDeletingRecordings(): TimelineAnnotationDeletingRecordingCleanup =
        serial.withLock {
            withContext(ioDispatcher) {
                val failures = linkedMapOf<String, List<String>>()
                database.recordingDao()
                    .findByState(RecordingState.DELETING)
                    .forEach { row ->
                        val failed = mutableListOf<String>()
                        dao.getAttachmentsForRecording(row.recording.id)
                            .forEach { failed += deleteAttachmentLocked(it) }
                        if (failed.isNotEmpty()) failures[row.recording.id] = failed.distinct()
                    }
                TimelineAnnotationDeletingRecordingCleanup(failures)
            }
        }

    suspend fun reconcileOnStartup(): TimelineAnnotationAttachmentReconcileResult =
        serial.withLock {
            withContext(ioDispatcher) {
                root.mkdirs()
                var recoveredDeleting = 0
                var removedMissing = 0
                var removedOrphans = 0
                val failedPaths = mutableListOf<String>()

                dao.getDeletingAttachments().forEach { attachment ->
                    val failed = deleteAttachmentLocked(attachment)
                    if (failed.isEmpty()) recoveredDeleting += 1 else failedPaths += failed
                }

                val rows = dao.getAllAttachments()
                val knownPaths = rows.mapTo(hashSetOf()) { normalizeRelativePath(it.relativePath) }
                rows
                    .filter { it.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.READY }
                    .forEach { attachment ->
                        val file = runCatching { resolveRelativePath(attachment.relativePath) }.getOrNull()
                        if (file == null || !file.isFile) {
                            dao.deleteAttachment(attachment.id)
                            removedMissing += 1
                        }
                    }

                val stagingDir = File(root, STAGING_DIR_NAME)
                root.walkBottomUp()
                    .filter { it.isFile }
                    .filterNot { isInsideOrSame(it, stagingDir) }
                    .forEach { file ->
                        val relative = file.relativeTo(appContext.filesDir).invariantSeparatorsPath
                        if (normalizeRelativePath(relative) !in knownPaths) {
                            if (file.delete()) removedOrphans += 1 else failedPaths += relative
                        }
                    }
                stagingDir.deleteRecursively()
                root.walkBottomUp().filter { it.isDirectory && it != root }.forEach(::cleanupEmptyDirectory)
                cleanupStaleCameraTargets()

                TimelineAnnotationAttachmentReconcileResult(
                    recoveredDeletingRows = recoveredDeleting,
                    removedMissingRows = removedMissing,
                    removedOrphanFiles = removedOrphans,
                    failedPaths = failedPaths.distinct(),
                )
            }
        }

    private suspend fun commitLocalFile(
        annotationId: String,
        attachmentId: String,
        sourceFile: File,
        source: String,
        providerMimeType: String?,
    ): TimelineAnnotationAttachmentEntity {
        val bounds = decodeImageBounds(sourceFile)
        require(bounds.width > 0 && bounds.height > 0) { "无法识别图片内容" }
        val mimeType =
            bounds.mimeType?.takeIf { it.startsWith("image/") }
                ?: providerMimeType?.takeIf { it.startsWith("image/") }
                ?: error("不支持的图片格式")
        val extension = extensionForMime(mimeType)
        val annotationDir = File(root, annotationId).apply { mkdirs() }
        val finalFile = File(annotationDir, "$attachmentId$extension")
        require(isInside(finalFile, root)) { "invalid annotation attachment path" }
        check(!finalFile.exists()) { "annotation attachment already exists" }

        if (!sourceFile.renameTo(finalFile)) {
            copyFileDurably(sourceFile, finalFile)
            check(sourceFile.delete() || !sourceFile.exists()) { "unable to remove attachment staging file" }
        }

        val size = finalFile.length()
        check(size > 0L) { "committed annotation image is empty" }
        val sha = sha256(finalFile)
        val relativePath = finalFile.relativeTo(appContext.filesDir).invariantSeparatorsPath
        val now = nowMs()
        val entity =
            TimelineAnnotationAttachmentEntity(
                id = attachmentId,
                annotationId = annotationId,
                kind = TimelineAnnotationAttachmentKindValue.PHOTO,
                source = source,
                relativePath = relativePath,
                mimeType = mimeType,
                sizeBytes = size,
                sha256 = sha,
                widthPx = bounds.width,
                heightPx = bounds.height,
                lifecycleState = TimelineAnnotationAttachmentLifecycleStateValue.READY,
                createdAtMs = now,
                updatedAtMs = now,
            )
        try {
            annotationRepository.addAttachment(entity)
        } catch (error: Throwable) {
            finalFile.delete()
            cleanupEmptyDirectory(annotationDir)
            throw error
        }
        return entity
    }

    private suspend fun deleteAttachmentLocked(
        attachment: TimelineAnnotationAttachmentEntity,
    ): List<String> {
        val deleting =
            if (attachment.lifecycleState == TimelineAnnotationAttachmentLifecycleStateValue.DELETING) {
                attachment
            } else {
                attachment.copy(
                    lifecycleState = TimelineAnnotationAttachmentLifecycleStateValue.DELETING,
                    updatedAtMs = nowMs(),
                ).also { dao.updateAttachment(it) }
            }
        val file = runCatching { resolveRelativePath(deleting.relativePath) }.getOrElse {
            return listOf(deleting.relativePath)
        }
        if (file.exists() && !file.delete()) return listOf(deleting.relativePath)
        dao.deleteAttachment(deleting.id)
        cleanupEmptyDirectory(file.parentFile)
        return emptyList()
    }

    private suspend fun requireAnnotation(annotationId: String) {
        require(annotationRepository.get(annotationId) != null) { "annotation does not exist" }
    }

    private fun decodeImageBounds(file: File): ImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return ImageBounds(options.outWidth, options.outHeight, options.outMimeType)
    }

    private fun copyFileDurably(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun resolveRelativePath(relativePath: String): File {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.startsWith("$ATTACHMENT_ROOT_NAME/")) { "attachment path outside managed root" }
        val file = File(appContext.filesDir, normalized)
        require(isInside(file, root)) { "attachment path escapes managed root" }
        return file
    }

    private fun normalizeRelativePath(path: String): String = path.replace('\\', '/').trimStart('/')

    private fun isInside(file: File, parent: File): Boolean {
        val parentPath = parent.canonicalFile.path + File.separator
        return file.canonicalFile.path.startsWith(parentPath)
    }

    private fun isInsideOrSame(file: File, parent: File): Boolean {
        val candidate = file.canonicalFile
        val rootCandidate = parent.canonicalFile
        return candidate == rootCandidate || candidate.path.startsWith(rootCandidate.path + File.separator)
    }

    private fun validateId(value: String, label: String) {
        require(SAFE_ID.matches(value)) { "$label contains unsafe path characters" }
    }

    private fun extensionForMime(mimeType: String): String =
        when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heic", "image/heif" -> ".heic"
            else -> ".img"
        }

    private fun cleanupStaleCameraTargets() {
        val cutoff = nowMs() - STALE_CAMERA_TARGET_AGE_MS
        cameraStagingRoot.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private fun cleanupEmptyDirectory(directory: File?) {
        if (directory == null || directory == root || directory == cameraStagingRoot) return
        if (directory.isDirectory && directory.list().isNullOrEmpty()) directory.delete()
    }

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String?,
    )

    private companion object {
        const val ATTACHMENT_ROOT_NAME = "timeline_annotations"
        const val CAMERA_STAGING_DIR_NAME = "timeline-annotation-camera"
        const val STAGING_DIR_NAME = ".staging"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val STALE_CAMERA_TARGET_AGE_MS = 24L * 60L * 60L * 1_000L
        val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,160}$")
    }
}
