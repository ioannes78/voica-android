package io.github.ioannes78.voica.model

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ModelInstallCandidateInspection(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val packageComplete: Boolean,
    val installed: Boolean,
    val confirmedGood: Boolean,
    val active: Boolean,
)

data class ModelInstallTransientProtection(
    val packagePartNames: Set<String> = emptySet(),
    val stagingDirectoryNames: Set<String> = emptySet(),
)

interface ModelInstallBackend {
    suspend fun freezeCandidate(
        modelId: String,
        version: String,
        revision: Long,
    ): ModelDescriptorSnapshot

    suspend fun inspectCandidate(
        snapshot: ModelDescriptorSnapshot,
    ): ModelInstallCandidateInspection

    suspend fun ensureInstallSpace(snapshot: ModelDescriptorSnapshot)

    suspend fun downloadPackage(
        snapshot: ModelDescriptorSnapshot,
        progressListener: ModelDownloadProgressListener? = null,
    )

    suspend fun verifyPackage(snapshot: ModelDescriptorSnapshot)

    suspend fun extractPackage(snapshot: ModelDescriptorSnapshot)

    suspend fun verifyFilesAndPromote(snapshot: ModelDescriptorSnapshot)

    suspend fun runtimeValidate(snapshot: ModelDescriptorSnapshot)

    suspend fun activate(snapshot: ModelDescriptorSnapshot)

    suspend fun cleanupInterruptedStaging(snapshot: ModelDescriptorSnapshot)

    suspend fun cleanupUserCancelled(snapshot: ModelDescriptorSnapshot)

    suspend fun cleanupTransientStorage(
        protection: ModelInstallTransientProtection,
    ): Long
}

fun modelInstallPackagePartName(snapshot: ModelDescriptorSnapshot): String =
    with(snapshot.descriptor) {
        "$modelId-$revision-$version.part"
    }

fun modelInstallStagingDirectoryName(snapshot: ModelDescriptorSnapshot): String =
    with(snapshot.descriptor) {
        "$modelId-$revision-$version"
    }

class DefaultModelInstallBackend(
    private val modelManager: ModelManager,
    private val environment: ModelEnvironment,
    private val storage: ModelStorage,
    packageDirectory: File,
    private val downloader: ModelPackageDownloader = HttpModelPackageDownloader(),
    private val extractor: ModelPackageExtractor = ModelPackageExtractor(),
    private val useRegistry: ModelUseRegistry = ModelUseRegistry(),
    private val candidateValidator: ModelCandidateValidator,
    private val blockingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpaceBytes: (File) -> Long = { directory -> directory.usableSpace },
    private val sameStorageVolume: (File, File) -> Boolean = { first, second ->
        runCatching {
            Files.getFileStore(first.toPath()) == Files.getFileStore(second.toPath())
        }.getOrDefault(false)
    },
) : ModelInstallBackend {
    private val packages = packageDirectory.canonicalFile
    private val storageRoot = storage.storageRootDirectory().canonicalFile
    private val stagingRoot = File(storageRoot, ".staging").canonicalFile

    init {
        require(packages.mkdirs() || packages.isDirectory)
        require(stagingRoot.mkdirs() || stagingRoot.isDirectory)
    }

    override suspend fun freezeCandidate(
        modelId: String,
        version: String,
        revision: Long,
    ): ModelDescriptorSnapshot {
        require(revision >= 1L)
        val catalog = modelManager.catalog()
        val descriptor =
            catalog.model(modelId)
                ?.takeIf { it.version == version && it.revision == revision }
                ?: error("model version is not present in the active catalog")
        require(descriptor.compatibilityWith(environment).compatible) {
            "model is incompatible with this device/app/runtime"
        }
        require(!descriptor.downloadUrl.isNullOrBlank()) {
            "model has no downloadable package"
        }
        return ModelDescriptorSnapshot(
            descriptor = descriptor,
            manifestDigest = catalog.manifestDigest,
        )
    }

    override suspend fun inspectCandidate(
        snapshot: ModelDescriptorSnapshot,
    ): ModelInstallCandidateInspection =
        withContext(blockingDispatcher) {
            val descriptor = snapshot.descriptor
            val part = packagePart(snapshot)
            val installed = storage.installedVersion(descriptor)
            val activation = storage.activationState(descriptor.modelId)
            val total = descriptor.downloadSizeBytes
            val downloaded = part.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
            ModelInstallCandidateInspection(
                downloadedBytes = downloaded,
                totalBytes = total,
                packageComplete = total != null && downloaded == total,
                installed = installed != null,
                confirmedGood = installed?.confirmedGood == true,
                active =
                    activation.activeVersion == descriptor.version &&
                        activation.activeRevision == descriptor.revision &&
                        installed?.confirmedGood == true,
            )
        }

    override suspend fun ensureInstallSpace(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            val descriptor = snapshot.descriptor
            val downloadSizeBytes =
                descriptor.downloadSizeBytes
                    ?: error("model package size is required for storage preflight")
            val installedSizeBytes = descriptor.installedSizeBytes
            val part = packagePart(snapshot)
            val existingPartBytes =
                part.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
            val sharedVolume = sameStorageVolume(packages, storageRoot)
            val reserveBytes =
                maxOf(
                    MIN_MODEL_INSTALL_SPACE_RESERVE_BYTES,
                    installedSizeBytes / 10L,
                )
            val requirements =
                calculateModelInstallSpaceRequirements(
                    downloadSizeBytes = downloadSizeBytes,
                    installedSizeBytes = installedSizeBytes,
                    existingPartBytes = existingPartBytes,
                    sameVolume = sharedVolume,
                    reserveBytes = reserveBytes,
                )

            if (sharedVolume) {
                val required = checkNotNull(requirements.sharedVolumeBytes)
                val available = minOf(usableSpaceBytes(packages), usableSpaceBytes(storageRoot))
                check(available >= required) {
                    storageErrorMessage(required, available)
                }
            } else {
                val packageAvailable = usableSpaceBytes(packages)
                check(packageAvailable >= requirements.packageVolumeBytes) {
                    storageErrorMessage(requirements.packageVolumeBytes, packageAvailable)
                }
                val storageAvailable = usableSpaceBytes(storageRoot)
                check(storageAvailable >= requirements.storageVolumeBytes) {
                    storageErrorMessage(requirements.storageVolumeBytes, storageAvailable)
                }
            }
        }
    }

    override suspend fun downloadPackage(
        snapshot: ModelDescriptorSnapshot,
        progressListener: ModelDownloadProgressListener?,
    ) {
        val descriptor = snapshot.descriptor
        val urls =
            buildList {
                descriptor.downloadUrl?.let(::add)
                addAll(descriptor.mirrors)
            }.distinct()
        check(urls.isNotEmpty()) { "model has no download URL" }

        var lastError: Throwable? = null
        for (url in urls) {
            try {
                downloader.download(
                    url = url,
                    destinationPart = packagePart(snapshot),
                    expectedBytes = descriptor.downloadSizeBytes,
                    progressListener = progressListener,
                )
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("all model download URLs failed")
    }

    override suspend fun verifyPackage(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            extractor.verifyPackage(snapshot.descriptor, packagePart(snapshot))
        }
    }

    override suspend fun extractPackage(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            val staging = storage.createStagingDirectory(snapshot.descriptor)
            extractor.extract(
                descriptor = snapshot.descriptor,
                packageFile = packagePart(snapshot),
                stagingDirectory = staging,
            )
        }
    }

    override suspend fun verifyFilesAndPromote(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            storage.promoteVerifiedStaging(
                descriptor = snapshot.descriptor,
                stagingDirectory = stagingDirectory(snapshot),
                manifestDigest = snapshot.manifestDigest,
            )
        }
    }

    override suspend fun runtimeValidate(snapshot: ModelDescriptorSnapshot) {
        val installed =
            withContext(blockingDispatcher) {
                storage.installedVersion(snapshot.descriptor)
            } ?: error("model candidate is not installed or failed integrity verification")

        withContext(blockingDispatcher) {
            storage.refreshDescriptorSnapshot(
                descriptor = snapshot.descriptor,
                manifestDigest = snapshot.manifestDigest,
            )
            candidateValidator.validate(
                descriptor = snapshot.descriptor,
                installedDirectory = installed.directory,
            )
        }
    }

    override suspend fun activate(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            storage.confirmGood(snapshot.descriptor)
            packagePart(snapshot).delete()
        }
    }

    override suspend fun cleanupInterruptedStaging(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            stagingDirectory(snapshot).deleteRecursively()
        }
    }

    override suspend fun cleanupUserCancelled(snapshot: ModelDescriptorSnapshot) {
        withContext(blockingDispatcher) {
            packagePart(snapshot).delete()
            stagingDirectory(snapshot).deleteRecursively()

            val descriptor = snapshot.descriptor
            val installed = storage.installedVersion(descriptor) ?: return@withContext
            val activation = storage.activationState(descriptor.modelId)
            val protected =
                (activation.activeVersion == descriptor.version &&
                    activation.activeRevision == descriptor.revision) ||
                    (activation.previousVersion == descriptor.version &&
                        activation.previousRevision == descriptor.revision) ||
                    useRegistry.isInUse(
                        descriptor.modelId,
                        descriptor.version,
                        descriptor.revision,
                    )
            if (!protected && !installed.confirmedGood) {
                storage.removeVersion(
                    descriptor.modelId,
                    descriptor.version,
                    descriptor.revision,
                )
            }
        }
    }

    override suspend fun cleanupTransientStorage(
        protection: ModelInstallTransientProtection,
    ): Long =
        withContext(blockingDispatcher) {
            var reclaimed = 0L

            packages.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile &&
                        file.name.endsWith(".part") &&
                        file.name !in protection.packagePartNames
                }
                .forEach { file ->
                    val bytes = file.length()
                    if (file.delete()) reclaimed += bytes
                }

            stagingRoot.listFiles()
                .orEmpty()
                .filter { it.name !in protection.stagingDirectoryNames }
                .forEach { entry ->
                    val bytes = physicalBytes(entry)
                    if (entry.deleteRecursively()) reclaimed += bytes
                }

            reclaimed
        }

    private fun packagePart(snapshot: ModelDescriptorSnapshot): File =
        File(packages, modelInstallPackagePartName(snapshot))

    private fun stagingDirectory(snapshot: ModelDescriptorSnapshot): File =
        File(stagingRoot, modelInstallStagingDirectoryName(snapshot)).canonicalFile

    private fun physicalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf(::physicalBytes)
    }

    private fun storageErrorMessage(required: Long, available: Long): String =
        "存储空间不足：模型安装至少还需要 ${formatSpaceMiB(required)}，当前可用 ${formatSpaceMiB(available)}"

    private fun formatSpaceMiB(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val mib = if (safe == 0L) 0L else (safe + MEBIBYTE_BYTES - 1L) / MEBIBYTE_BYTES
        return "$mib MiB"
    }

    private companion object {
        const val MEBIBYTE_BYTES = 1024L * 1024L
    }
}
