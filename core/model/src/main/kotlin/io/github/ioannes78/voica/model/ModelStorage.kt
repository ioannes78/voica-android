package io.github.ioannes78.voica.model

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

enum class ModelVerificationFailure {
    DIRECTORY_MISSING,
    FILE_MISSING,
    FILE_OUTSIDE_MODEL_DIRECTORY,
    SIZE_MISMATCH,
    SHA256_MISMATCH,
}

sealed interface ModelVerificationResult {
    data object Valid : ModelVerificationResult

    data class Invalid(
        val failure: ModelVerificationFailure,
        val relativePath: String? = null,
    ) : ModelVerificationResult
}

data class InstalledModelVersion(
    val modelId: String,
    val version: String,
    val revision: Long,
    val directory: File,
    val confirmedGood: Boolean,
)

data class ModelActivationState(
    val activeVersion: String?,
    val activeRevision: Long?,
    val previousVersion: String?,
    val previousRevision: Long?,
)

class ModelStorage(
    rootDirectory: File,
) {
    private val root = rootDirectory.canonicalFile
    private val stagingRoot = File(root, ".staging")
    private val installedRoot = File(root, "installed")
    private val stateRoot = File(root, "state")

    init {
        require(root.mkdirs() || root.isDirectory)
        require(stagingRoot.mkdirs() || stagingRoot.isDirectory)
        require(installedRoot.mkdirs() || installedRoot.isDirectory)
        require(stateRoot.mkdirs() || stateRoot.isDirectory)
    }

    fun createStagingDirectory(descriptor: ModelDescriptor): File {
        val directory =
            File(
                stagingRoot,
                "${descriptor.modelId}-${descriptor.revision}-${descriptor.version}",
            )
        if (directory.exists()) directory.deleteRecursively()
        check(directory.mkdirs()) { "failed to create staging directory" }
        return directory
    }

    fun verifyDirectory(
        descriptor: ModelDescriptor,
        directory: File,
    ): ModelVerificationResult {
        if (!directory.isDirectory) {
            return ModelVerificationResult.Invalid(
                ModelVerificationFailure.DIRECTORY_MISSING,
            )
        }
        val canonicalRoot = directory.canonicalFile

        descriptor.files.forEach { expected ->
            val file = File(canonicalRoot, expected.relativePath)
            val canonical = runCatching { file.canonicalFile }.getOrNull()
                ?: return ModelVerificationResult.Invalid(
                    ModelVerificationFailure.FILE_OUTSIDE_MODEL_DIRECTORY,
                    expected.relativePath,
                )
            if (!isInside(canonicalRoot, canonical)) {
                return ModelVerificationResult.Invalid(
                    ModelVerificationFailure.FILE_OUTSIDE_MODEL_DIRECTORY,
                    expected.relativePath,
                )
            }
            if (!canonical.isFile) {
                return ModelVerificationResult.Invalid(
                    ModelVerificationFailure.FILE_MISSING,
                    expected.relativePath,
                )
            }
            if (canonical.length() != expected.sizeBytes) {
                return ModelVerificationResult.Invalid(
                    ModelVerificationFailure.SIZE_MISMATCH,
                    expected.relativePath,
                )
            }
            if (!sha256Hex(canonical).equals(expected.sha256, ignoreCase = true)) {
                return ModelVerificationResult.Invalid(
                    ModelVerificationFailure.SHA256_MISMATCH,
                    expected.relativePath,
                )
            }
        }

        return ModelVerificationResult.Valid
    }

    fun promoteVerifiedStaging(
        descriptor: ModelDescriptor,
        stagingDirectory: File,
    ): InstalledModelVersion {
        require(isInside(stagingRoot, stagingDirectory.canonicalFile)) {
            "staging directory is outside managed staging root"
        }
        check(verifyDirectory(descriptor, stagingDirectory) == ModelVerificationResult.Valid) {
            "staging verification failed"
        }

        val target = versionDirectory(descriptor.modelId, descriptor.version, descriptor.revision)
        target.parentFile?.mkdirs()

        if (target.exists()) {
            check(verifyDirectory(descriptor, target) == ModelVerificationResult.Valid) {
                "existing model version is corrupted"
            }
            stagingDirectory.deleteRecursively()
        } else {
            Files.move(
                stagingDirectory.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }

        return InstalledModelVersion(
            modelId = descriptor.modelId,
            version = descriptor.version,
            revision = descriptor.revision,
            directory = target,
            confirmedGood = confirmedMarker(target).isFile,
        )
    }

    fun confirmGood(descriptor: ModelDescriptor): InstalledModelVersion {
        val target = versionDirectory(descriptor.modelId, descriptor.version, descriptor.revision)
        check(verifyDirectory(descriptor, target) == ModelVerificationResult.Valid) {
            "cannot confirm an invalid model installation"
        }

        atomicWrite(
            confirmedMarker(target),
            "confirmed=${descriptor.modelId}:${descriptor.revision}:${descriptor.version}\n",
        )

        val previous = activationState(descriptor.modelId)
        val next =
            if (previous.activeVersion == descriptor.version &&
                previous.activeRevision == descriptor.revision
            ) {
                previous
            } else {
                ModelActivationState(
                    activeVersion = descriptor.version,
                    activeRevision = descriptor.revision,
                    previousVersion = previous.activeVersion,
                    previousRevision = previous.activeRevision,
                )
            }
        writeActivationState(descriptor.modelId, next)

        return InstalledModelVersion(
            modelId = descriptor.modelId,
            version = descriptor.version,
            revision = descriptor.revision,
            directory = target,
            confirmedGood = true,
        )
    }

    fun activationState(modelId: String): ModelActivationState {
        require(SAFE_PATH_SEGMENT_REGEX.matches(modelId))
        val file = stateFile(modelId)
        if (!file.isFile) {
            return ModelActivationState(null, null, null, null)
        }
        val properties = Properties()
        file.inputStream().use(properties::load)
        return ModelActivationState(
            activeVersion = properties.getProperty("activeVersion"),
            activeRevision = properties.getProperty("activeRevision")?.toLongOrNull(),
            previousVersion = properties.getProperty("previousVersion"),
            previousRevision = properties.getProperty("previousRevision")?.toLongOrNull(),
        )
    }

    fun installedVersion(descriptor: ModelDescriptor): InstalledModelVersion? {
        val directory =
            versionDirectory(
                descriptor.modelId,
                descriptor.version,
                descriptor.revision,
            )
        if (!directory.isDirectory) return null
        if (verifyDirectory(descriptor, directory) != ModelVerificationResult.Valid) {
            return null
        }
        return InstalledModelVersion(
            modelId = descriptor.modelId,
            version = descriptor.version,
            revision = descriptor.revision,
            directory = directory,
            confirmedGood = confirmedMarker(directory).isFile,
        )
    }

    fun activeDirectory(modelId: String): File? {
        val state = activationState(modelId)
        val version = state.activeVersion ?: return null
        val revision = state.activeRevision ?: return null
        val directory = versionDirectory(modelId, version, revision)
        return directory.takeIf { it.isDirectory && confirmedMarker(it).isFile }
    }

    fun rollback(modelId: String): ModelActivationState {
        val current = activationState(modelId)
        val previousVersion = current.previousVersion
            ?: throw IllegalStateException("no previous confirmed-good model")
        val previousRevision = current.previousRevision
            ?: throw IllegalStateException("no previous confirmed-good model")

        val previousDirectory = versionDirectory(modelId, previousVersion, previousRevision)
        check(previousDirectory.isDirectory && confirmedMarker(previousDirectory).isFile) {
            "previous model is not confirmed-good"
        }

        val rolledBack =
            ModelActivationState(
                activeVersion = previousVersion,
                activeRevision = previousRevision,
                previousVersion = current.activeVersion,
                previousRevision = current.activeRevision,
            )
        writeActivationState(modelId, rolledBack)
        return rolledBack
    }

    fun removeVersion(
        modelId: String,
        version: String,
        revision: Long,
    ): Boolean {
        require(SAFE_PATH_SEGMENT_REGEX.matches(modelId))
        require(SAFE_PATH_SEGMENT_REGEX.matches(version))
        val state = activationState(modelId)
        check(!(state.activeVersion == version && state.activeRevision == revision)) {
            "cannot remove active model version"
        }
        check(!(state.previousVersion == version && state.previousRevision == revision)) {
            "cannot remove rollback model version"
        }

        val directory = versionDirectory(modelId, version, revision)
        return !directory.exists() || directory.deleteRecursively()
    }

    fun cleanupStaging() {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun writeActivationState(
        modelId: String,
        state: ModelActivationState,
    ) {
        val content = buildString {
            state.activeVersion?.let { append("activeVersion=").append(it).append('\n') }
            state.activeRevision?.let { append("activeRevision=").append(it).append('\n') }
            state.previousVersion?.let { append("previousVersion=").append(it).append('\n') }
            state.previousRevision?.let { append("previousRevision=").append(it).append('\n') }
        }
        atomicWrite(stateFile(modelId), content)
    }

    private fun atomicWrite(
        target: File,
        content: String,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(content)
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun stateFile(modelId: String) =
        File(stateRoot, "$modelId.properties")

    private fun versionDirectory(
        modelId: String,
        version: String,
        revision: Long,
    ): File {
        require(SAFE_PATH_SEGMENT_REGEX.matches(modelId))
        require(SAFE_PATH_SEGMENT_REGEX.matches(version))
        require(revision >= 1L)
        return File(File(installedRoot, modelId), "$revision-$version")
    }

    private fun confirmedMarker(directory: File) =
        File(directory, ".confirmed-good")

    private fun isInside(
        parent: File,
        child: File,
    ): Boolean {
        val parentPath = parent.canonicalPath
        val childPath = child.canonicalPath
        return childPath == parentPath ||
            childPath.startsWith(parentPath + File.separator)
    }
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(SHA_BUFFER_SIZE).use { input ->
        val buffer = ByteArray(SHA_BUFFER_SIZE)
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

private const val SHA_BUFFER_SIZE = 64 * 1024
