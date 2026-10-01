package io.github.ioannes78.voica.model

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties

data class ModelStoreState(
    val activeVersion: String?,
    val previousVersion: String?,
)

data class InstalledModelMetadata(
    val modelId: String,
    val version: String,
    val revision: Long,
    val confirmedGood: Boolean,
)

class AtomicModelStore(
    private val root: Path,
) {
    private val installedRoot = root.resolve("installed")
    private val stagingRoot = root.resolve(".staging")
    private val stateRoot = root.resolve("state")

    init {
        Files.createDirectories(installedRoot)
        Files.createDirectories(stagingRoot)
        Files.createDirectories(stateRoot)
    }

    fun stagingDirectory(
        modelId: String,
        version: String,
    ): Path {
        requireSafeSegment(modelId, "modelId")
        requireSafeSegment(version, "version")
        return stagingRoot.resolve(modelId).resolve(version)
    }

    fun versionDirectory(
        modelId: String,
        version: String,
    ): Path {
        requireSafeSegment(modelId, "modelId")
        requireSafeSegment(version, "version")
        return installedRoot.resolve(modelId).resolve(version)
    }

    fun state(modelId: String): ModelStoreState {
        requireSafeSegment(modelId, "modelId")
        val file = stateFile(modelId)
        if (!Files.isRegularFile(file)) {
            return ModelStoreState(activeVersion = null, previousVersion = null)
        }

        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        return ModelStoreState(
            activeVersion = properties.getProperty(KEY_ACTIVE)?.takeIf { it.isNotBlank() },
            previousVersion = properties.getProperty(KEY_PREVIOUS)?.takeIf { it.isNotBlank() },
        )
    }

    fun activeInstallation(modelId: String): InstalledModelMetadata? =
        state(modelId).activeVersion?.let { readMetadata(modelId, it) }

    fun previousInstallation(modelId: String): InstalledModelMetadata? =
        state(modelId).previousVersion?.let { readMetadata(modelId, it) }

    fun commitVerifiedVersion(
        descriptor: ModelDescriptor,
        stagedDirectory: Path,
        confirmedGood: Boolean = true,
    ): InstalledModelMetadata {
        requireSafeSegment(descriptor.modelId, "modelId")
        requireSafeSegment(descriptor.version, "version")

        val expectedStaging =
            stagingDirectory(descriptor.modelId, descriptor.version)
                .toAbsolutePath()
                .normalize()
        val actualStaging = stagedDirectory.toAbsolutePath().normalize()
        require(actualStaging == expectedStaging) {
            "staged directory must be the store-owned staging path"
        }

        ModelIntegrityVerifier.requireValidDirectory(actualStaging, descriptor)

        val destination = versionDirectory(descriptor.modelId, descriptor.version)
        check(!Files.exists(destination)) {
            "model version already exists: ${descriptor.modelId}/${descriptor.version}"
        }

        writeInstallMetadata(
            directory = actualStaging,
            metadata =
                InstalledModelMetadata(
                    modelId = descriptor.modelId,
                    version = descriptor.version,
                    revision = descriptor.revision,
                    confirmedGood = confirmedGood,
                ),
        )

        Files.createDirectories(destination.parent)
        moveAtomically(actualStaging, destination)

        val current = state(descriptor.modelId)
        val previous =
            if (current.activeVersion != null &&
                current.activeVersion != descriptor.version
            ) {
                current.activeVersion
            } else {
                current.previousVersion
            }
        writeStateAtomically(
            modelId = descriptor.modelId,
            value =
                ModelStoreState(
                    activeVersion = descriptor.version,
                    previousVersion = previous,
                ),
        )

        return readMetadata(descriptor.modelId, descriptor.version)
            ?: error("installed metadata missing after commit")
    }

    fun rollback(modelId: String): Boolean {
        val current = state(modelId)
        val previous = current.previousVersion ?: return false
        val previousMetadata = readMetadata(modelId, previous) ?: return false
        if (!previousMetadata.confirmedGood) return false

        writeStateAtomically(
            modelId,
            ModelStoreState(
                activeVersion = previous,
                previousVersion = current.activeVersion,
            ),
        )
        return true
    }

    fun removeInactiveVersion(
        modelId: String,
        version: String,
    ): Boolean {
        requireSafeSegment(modelId, "modelId")
        requireSafeSegment(version, "version")

        val current = state(modelId)
        check(version != current.activeVersion) {
            "cannot delete active model version"
        }

        val target = versionDirectory(modelId, version)
        if (!Files.exists(target)) return false
        deleteRecursively(target)

        if (version == current.previousVersion) {
            writeStateAtomically(
                modelId,
                current.copy(previousVersion = null),
            )
        }
        return true
    }

    fun readMetadata(
        modelId: String,
        version: String,
    ): InstalledModelMetadata? {
        val file = versionDirectory(modelId, version).resolve(INSTALL_METADATA)
        if (!Files.isRegularFile(file)) return null

        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        val storedModelId = properties.getProperty("modelId") ?: return null
        val storedVersion = properties.getProperty("version") ?: return null
        val revision = properties.getProperty("revision")?.toLongOrNull() ?: return null
        val confirmed = properties.getProperty("confirmedGood")?.toBooleanStrictOrNull() ?: false
        return InstalledModelMetadata(
            modelId = storedModelId,
            version = storedVersion,
            revision = revision,
            confirmedGood = confirmed,
        )
    }

    private fun writeInstallMetadata(
        directory: Path,
        metadata: InstalledModelMetadata,
    ) {
        val properties =
            Properties().apply {
                setProperty("modelId", metadata.modelId)
                setProperty("version", metadata.version)
                setProperty("revision", metadata.revision.toString())
                setProperty("confirmedGood", metadata.confirmedGood.toString())
            }
        Files.newOutputStream(
            directory.resolve(INSTALL_METADATA),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { output ->
            properties.store(output, "Voica model installation")
        }
    }

    private fun writeStateAtomically(
        modelId: String,
        value: ModelStoreState,
    ) {
        requireSafeSegment(modelId, "modelId")
        Files.createDirectories(stateRoot)

        val target = stateFile(modelId)
        val temporary = stateRoot.resolve(".$modelId.state.tmp")
        val properties =
            Properties().apply {
                value.activeVersion?.let { setProperty(KEY_ACTIVE, it) }
                value.previousVersion?.let { setProperty(KEY_PREVIOUS, it) }
            }

        Files.newOutputStream(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { output ->
            properties.store(output, "Voica active model state")
        }

        moveAtomically(temporary, target, replaceExisting = true)
    }

    private fun stateFile(modelId: String): Path =
        stateRoot.resolve("$modelId.properties")

    private fun moveAtomically(
        source: Path,
        target: Path,
        replaceExisting: Boolean = false,
    ) {
        val options =
            if (replaceExisting) {
                arrayOf(
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }

        try {
            Files.move(source, target, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback =
                if (replaceExisting) {
                    arrayOf(StandardCopyOption.REPLACE_EXISTING)
                } else {
                    emptyArray()
                }
            Files.move(source, target, *fallback)
        }
    }

    private fun deleteRecursively(path: Path) {
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun requireSafeSegment(
        value: String,
        label: String,
    ) {
        require(SAFE_SEGMENT.matches(value)) {
            "$label contains unsafe path characters"
        }
    }

    private companion object {
        const val INSTALL_METADATA = ".voica-install.properties"
        const val KEY_ACTIVE = "activeVersion"
        const val KEY_PREVIOUS = "previousVersion"
        val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]+$")
    }
}
