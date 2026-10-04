package io.github.ioannes78.voica.model

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val MIN_MODEL_INSTALL_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
private const val MEBIBYTE_BYTES = 1024L * 1024L

internal data class ModelInstallSpaceRequirements(
    val packageVolumeBytes: Long,
    val storageVolumeBytes: Long,
    val sharedVolumeBytes: Long?,
)

internal fun calculateModelInstallSpaceRequirements(
    downloadSizeBytes: Long,
    installedSizeBytes: Long,
    existingPartBytes: Long,
    sameVolume: Boolean,
    reserveBytes: Long = MIN_MODEL_INSTALL_SPACE_RESERVE_BYTES,
): ModelInstallSpaceRequirements {
    require(downloadSizeBytes >= 0L)
    require(installedSizeBytes >= 0L)
    require(existingPartBytes >= 0L)
    require(reserveBytes >= 0L)
    val retained = existingPartBytes.coerceAtMost(downloadSizeBytes)
    val remainingDownloadBytes = downloadSizeBytes - retained

    fun addExact(vararg values: Long): Long =
        values.fold(0L) { total, value -> Math.addExact(total, value) }

    return if (sameVolume) {
        ModelInstallSpaceRequirements(
            packageVolumeBytes = 0L,
            storageVolumeBytes = 0L,
            sharedVolumeBytes =
                addExact(
                    remainingDownloadBytes,
                    installedSizeBytes,
                    reserveBytes,
                ),
        )
    } else {
        ModelInstallSpaceRequirements(
            packageVolumeBytes = addExact(remainingDownloadBytes, reserveBytes),
            storageVolumeBytes = addExact(installedSizeBytes, reserveBytes),
            sharedVolumeBytes = null,
        )
    }
}

class DefaultModelManager(
    private val bundledCatalog: ModelCatalog,
    private val remoteCatalogProvider: ModelCatalogProvider?,
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
) : ModelManager {
    private val packages = packageDirectory.canonicalFile
    private val catalogMutex = Mutex()
    private val modelMutexes = ConcurrentHashMap<String, Mutex>()
    private val installJobs = ConcurrentHashMap<String, Job>()
    private val installPartFiles = ConcurrentHashMap<String, File>()
    private val _operations =
        MutableStateFlow<Map<String, ModelOperationStatus>>(emptyMap())

    @Volatile
    private var remoteCatalog: ModelCatalog? = null

    override val operations: StateFlow<Map<String, ModelOperationStatus>> =
        _operations.asStateFlow()

    init {
        require(packages.mkdirs() || packages.isDirectory)
    }

    override suspend fun catalog(): ModelCatalog =
        remoteCatalog ?: bundledCatalog

    override suspend fun activeModel(modelId: String): ActiveModel? =
        withContext(blockingDispatcher) {
        val activation = storage.activationState(modelId)
        val activeVersion = activation.activeVersion
        val activeRevision = activation.activeRevision

        if (activeVersion != null && activeRevision != null) {
            val snapshot =
                storage.installedSnapshot(
                    modelId = modelId,
                    version = activeVersion,
                    revision = activeRevision,
                ) ?: return@withContext null
            return@withContext ActiveModel(
                descriptor = snapshot.descriptor,
                manifestDigest = snapshot.manifestDigest,
                installedDirectory =
                    storage.activeDirectory(modelId)
                        ?: return@withContext null,
            )
        }

        val builtin = bundledCatalog.model(modelId) ?: return@withContext null
        val builtinAvailable =
            builtin.sourceType == ModelSourceType.BUILTIN ||
                builtin.sourceType == ModelSourceType.BUILTIN_WITH_OVERRIDE
        if (!builtinAvailable) return@withContext null
        return@withContext ActiveModel(
            descriptor = builtin,
            manifestDigest = bundledCatalog.manifestDigest,
            installedDirectory = null,
        )
    }

    override suspend fun checkForUpdates(force: Boolean): ModelCatalog {
        val provider = remoteCatalogProvider ?: return catalog()
        return catalogMutex.withLock {
            val loaded = provider.load(force)
            validateRemoteCatalog(loaded)
            remoteCatalog = loaded
            loaded
        }
    }

    override suspend fun availability(modelId: String): ModelAvailability? {
        val effective = catalog().model(modelId) ?: return null
        val builtin = bundledCatalog.model(modelId)
        val compatibility = effective.compatibilityWith(environment)
        val operation = operations.value[modelId]
        val (activation, exactInstalled) =
            withContext(blockingDispatcher) {
                storage.activationState(modelId) to
                    storage.installedVersion(effective)
            }

        val builtinAvailable =
            builtin != null &&
                (builtin.sourceType == ModelSourceType.BUILTIN ||
                    builtin.sourceType == ModelSourceType.BUILTIN_WITH_OVERRIDE)

        val activeVersion =
            activation.activeVersion ?: builtin?.version.takeIf { builtinAvailable }
        val activeRevision =
            activation.activeRevision ?: builtin?.revision.takeIf { builtinAvailable }

        val installedVersion =
            exactInstalled?.version
                ?: activation.activeVersion
                ?: builtin?.version.takeIf { builtinAvailable }
        val installedRevision =
            exactInstalled?.revision
                ?: activation.activeRevision
                ?: builtin?.revision.takeIf { builtinAvailable }

        val stableState =
            when {
                !compatibility.compatible -> ModelState.INCOMPATIBLE
                exactInstalled != null -> ModelState.INSTALLED
                activation.activeVersion != null -> ModelState.INSTALLED
                builtinAvailable -> ModelState.INSTALLED
                else -> ModelState.NOT_INSTALLED
            }
        val state =
            operation?.state
                ?.takeIf {
                    it == ModelState.DOWNLOADING ||
                        it == ModelState.VERIFYING ||
                        it == ModelState.LOAD_FAILED ||
                        it == ModelState.CORRUPTED
                }
                ?: stableState

        return ModelAvailability(
            descriptor = effective,
            state = state,
            builtinVersion = builtin?.version.takeIf { builtinAvailable },
            builtinRevision = builtin?.revision.takeIf { builtinAvailable },
            installedVersion = installedVersion,
            installedRevision = installedRevision,
            availableVersion = effective.version,
            availableRevision = effective.revision,
            activeVersion = activeVersion,
            activeRevision = activeRevision,
            previousVersion = activation.previousVersion,
            previousRevision = activation.previousRevision,
            updateAvailable =
                activeRevision != null && effective.revision > activeRevision,
        )
    }

    override suspend fun install(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            val sourceCatalog = catalog()
            val descriptor =
                sourceCatalog.model(modelId)
                    ?.takeIf { it.version == version && it.revision == revision }
                    ?: error("model version is not present in the active catalog")
            val manifestDigest = sourceCatalog.manifestDigest
            require(descriptor.compatibilityWith(environment).compatible) {
                "model is incompatible with this device/app/runtime"
            }
            require(!descriptor.downloadUrl.isNullOrBlank()) {
                "model has no downloadable package"
            }

            val currentJob =
                currentCoroutineContext()[Job]
                    ?: error("model install requires a coroutine Job")
            check(installJobs.putIfAbsent(modelId, currentJob) == null) {
                "model install is already running"
            }

            val packagePart =
                File(
                    packages,
                    descriptor.modelId + "-" +
                        descriptor.revision + "-" +
                        descriptor.version + ".part",
                )
            check(installPartFiles.putIfAbsent(modelId, packagePart) == null) {
                "model install package is already tracked"
            }
            var staging: File? = null
            var installCompleted = false

            try {
                withContext(blockingDispatcher) {
                    ensureInstallSpace(
                        descriptor = descriptor,
                        packagePart = packagePart,
                    )
                }

                updateOperation(
                    modelId,
                    ModelOperationStatus(
                        state = ModelState.DOWNLOADING,
                        downloadedBytes = 0L,
                        totalBytes = descriptor.downloadSizeBytes,
                    ),
                )
                downloadWithMirrors(descriptor, packagePart)

                updateOperation(
                    modelId,
                    ModelOperationStatus(
                        state = ModelState.VERIFYING,
                        downloadedBytes = descriptor.downloadSizeBytes,
                        totalBytes = descriptor.downloadSizeBytes,
                    ),
                )

                withContext(blockingDispatcher) {
                    extractor.verifyPackage(descriptor, packagePart)
                    staging = storage.createStagingDirectory(descriptor)
                    extractor.extract(
                        descriptor = descriptor,
                        packageFile = packagePart,
                        stagingDirectory = checkNotNull(staging),
                    )
                    storage.promoteVerifiedStaging(
                        descriptor = descriptor,
                        stagingDirectory = checkNotNull(staging),
                        manifestDigest = manifestDigest,
                    )
                    staging = null
                }
                installCompleted = true

                updateOperation(
                    modelId,
                    ModelOperationStatus(state = ModelState.INSTALLED),
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + blockingDispatcher) {
                    packagePart.delete()
                }
                clearOperation(modelId)
                throw cancelled
            } catch (error: Throwable) {
                val integrityFailure = isIntegrityFailure(error)
                if (integrityFailure) {
                    withContext(blockingDispatcher) {
                        packagePart.delete()
                    }
                }
                val retainedBytes =
                    withContext(blockingDispatcher) {
                        packagePart.takeIf { it.isFile }?.length()
                    }
                updateOperation(
                    modelId,
                    ModelOperationStatus(
                        state =
                            if (integrityFailure) {
                                ModelState.CORRUPTED
                            } else {
                                ModelState.LOAD_FAILED
                            },
                        downloadedBytes = retainedBytes,
                        totalBytes = descriptor.downloadSizeBytes,
                        errorMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
                throw error
            } finally {
                withContext(NonCancellable + blockingDispatcher) {
                    if (installCompleted) {
                        packagePart.delete()
                    }
                    staging?.deleteRecursively()
                }
                installPartFiles.remove(modelId, packagePart)
                installJobs.remove(modelId, currentJob)
            }
        }
    }

    private fun ensureInstallSpace(
        descriptor: ModelDescriptor,
        packagePart: File,
    ) {
        val downloadSizeBytes =
            descriptor.downloadSizeBytes
                ?: error("model package size is required for storage preflight")
        val installedSizeBytes = descriptor.installedSizeBytes
        val existingPartBytes =
            packagePart.takeIf(File::isFile)
                ?.length()
                ?.coerceAtLeast(0L)
                ?: 0L
        val storageRoot = storage.storageRootDirectory()
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
            val available =
                minOf(
                    usableSpaceBytes(packages),
                    usableSpaceBytes(storageRoot),
                )
            check(available >= required) {
                storageErrorMessage(required = required, available = available)
            }
        } else {
            val packageAvailable = usableSpaceBytes(packages)
            check(packageAvailable >= requirements.packageVolumeBytes) {
                storageErrorMessage(
                    required = requirements.packageVolumeBytes,
                    available = packageAvailable,
                )
            }
            val storageAvailable = usableSpaceBytes(storageRoot)
            check(storageAvailable >= requirements.storageVolumeBytes) {
                storageErrorMessage(
                    required = requirements.storageVolumeBytes,
                    available = storageAvailable,
                )
            }
        }
    }

    private fun storageErrorMessage(
        required: Long,
        available: Long,
    ): String =
        "存储空间不足：模型安装至少还需要 " +
            formatSpaceMiB(required) +
            "，当前可用 " +
            formatSpaceMiB(available)

    private fun formatSpaceMiB(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val mebibytes =
            if (safeBytes == 0L) {
                0L
            } else {
                (safeBytes + MEBIBYTE_BYTES - 1L) / MEBIBYTE_BYTES
            }
        return mebibytes.toString() + " MiB"
    }

    override suspend fun cancelInstall(modelId: String) {
        val job = installJobs[modelId] ?: return
        val packagePart = installPartFiles[modelId]
        job.cancel(
            CancellationException("model install cancelled"),
        )
        if (job !== currentCoroutineContext()[Job]) {
            job.join()
            withContext(NonCancellable + blockingDispatcher) {
                packagePart?.delete()
            }
            clearOperation(modelId)
        }
    }

    override suspend fun confirmInstalledVersion(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            val snapshot =
                withContext(blockingDispatcher) {
                    storage.installedSnapshot(modelId, version, revision)
                } ?: error("model candidate metadata is missing or failed integrity verification")
            val descriptor = snapshot.descriptor
            require(descriptor.compatibilityWith(environment).compatible) {
                "model is incompatible with this device/app/runtime"
            }
            val installed =
                withContext(blockingDispatcher) {
                    storage.installedVersion(descriptor)
                } ?: error("model candidate is not installed or failed integrity verification")
            updateOperation(
                modelId,
                ModelOperationStatus(state = ModelState.VERIFYING),
            )
            try {
                withContext(blockingDispatcher) {
                    candidateValidator.validate(
                        descriptor = descriptor,
                        installedDirectory = installed.directory,
                    )
                    storage.confirmGood(descriptor)
                }
                updateOperation(
                    modelId,
                    ModelOperationStatus(state = ModelState.INSTALLED),
                )
            } catch (cancelled: CancellationException) {
                clearOperation(modelId)
                throw cancelled
            } catch (error: Throwable) {
                updateOperation(
                    modelId,
                    ModelOperationStatus(
                        state = ModelState.LOAD_FAILED,
                        errorMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
                throw error
            }
        }
    }

    override suspend fun removeDownloadedVersion(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            check(!useRegistry.isInUse(modelId, version, revision)) {
                "model version is currently in use"
            }

            withContext(blockingDispatcher) {
                val activation = storage.activationState(modelId)
                val removingActive =
                    activation.activeVersion == version &&
                        activation.activeRevision == revision

                if (removingActive) {
                    val hasPrevious =
                        activation.previousVersion != null &&
                            activation.previousRevision != null
                    if (hasPrevious) {
                        storage.rollback(modelId)
                        storage.clearPrevious(modelId)
                    } else {
                        storage.clearActivation(modelId)
                    }
                }

                storage.removeVersion(modelId, version, revision)
            }
            clearOperation(modelId)
        }
    }

    override suspend fun downloadedVersions(): List<DownloadedModelVersionInfo> =
        withContext(blockingDispatcher) {
            storage.installedVersions()
                .map { stored ->
                    val descriptor = stored.snapshot.descriptor
                    val activation = storage.activationState(descriptor.modelId)
                    DownloadedModelVersionInfo(
                        modelId = descriptor.modelId,
                        displayName = descriptor.displayName,
                        version = descriptor.version,
                        revision = descriptor.revision,
                        sizeBytes = stored.sizeBytes,
                        active =
                            activation.activeVersion == descriptor.version &&
                                activation.activeRevision == descriptor.revision,
                        previous =
                            activation.previousVersion == descriptor.version &&
                                activation.previousRevision == descriptor.revision,
                        inUse =
                            useRegistry.isInUse(
                                descriptor.modelId,
                                descriptor.version,
                                descriptor.revision,
                            ),
                    )
                }
                .sortedWith(
                    compareBy<DownloadedModelVersionInfo> { it.displayName.lowercase() }
                        .thenByDescending { it.revision },
                )
        }

    override suspend fun cleanupTransientStorage(): Long {
        val activeParts = installPartFiles.values.mapTo(hashSetOf()) { it.canonicalPath }
        return withContext(blockingDispatcher) {
            var reclaimed = 0L

            if (installJobs.isEmpty()) {
                reclaimed += storage.cleanupStaging()
            }

            packages.listFiles()
                .orEmpty()
                .filter { file ->
                    file.isFile &&
                        file.name.endsWith(".part") &&
                        file.canonicalPath !in activeParts
                }
                .forEach { file ->
                    val bytes = file.length()
                    if (file.delete()) reclaimed += bytes
                }

            reclaimed
        }
    }

    override suspend fun rollback(modelId: String) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            val state =
                withContext(blockingDispatcher) {
                    storage.activationState(modelId)
                }
            val activeVersion = state.activeVersion
            val activeRevision = state.activeRevision
            if (activeVersion != null && activeRevision != null) {
                check(!useRegistry.isInUse(modelId, activeVersion, activeRevision)) {
                    "active model version is currently in use"
                }
            }
            val builtin = bundledCatalog.model(modelId)
            val hasBuiltinFallback =
                builtin != null &&
                    (builtin.sourceType == ModelSourceType.BUILTIN ||
                        builtin.sourceType == ModelSourceType.BUILTIN_WITH_OVERRIDE)

            try {
                withContext(blockingDispatcher) {
                    storage.rollback(modelId)
                }
            } catch (error: IllegalStateException) {
                if (!hasBuiltinFallback ||
                    activeVersion == null ||
                    activeRevision == null
                ) {
                    throw error
                }
                withContext(blockingDispatcher) {
                    storage.clearActivation(modelId)
                }
            }
            clearOperation(modelId)
        }
    }

    private suspend fun requireDescriptor(
        modelId: String,
        version: String,
        revision: Long,
    ): ModelDescriptor {
        require(revision >= 1L)
        return catalog().model(modelId)
            ?.takeIf { it.version == version && it.revision == revision }
            ?: error("model version is not present in the active catalog")
    }

    private suspend fun downloadWithMirrors(
        descriptor: ModelDescriptor,
        destination: File,
    ) {
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
                    destinationPart = destination,
                    expectedBytes = descriptor.downloadSizeBytes,
                    progressListener =
                        ModelDownloadProgressListener { progress ->
                            updateOperation(
                                descriptor.modelId,
                                ModelOperationStatus(
                                    state = ModelState.DOWNLOADING,
                                    downloadedBytes = progress.downloadedBytes,
                                    totalBytes = progress.totalBytes,
                                ),
                            )
                        },
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

    private fun validateRemoteCatalog(remote: ModelCatalog) {
        require(remote.catalogVersion == bundledCatalog.catalogVersion) {
            "remote catalog version is incompatible"
        }
        require(remote.channel == bundledCatalog.channel) {
            "remote catalog channel mismatch"
        }

        bundledCatalog.models.forEach { baseline ->
            val candidate =
                remote.model(baseline.modelId)
                    ?: error(
                        "remote catalog removed baseline model " + baseline.modelId,
                    )
            require(candidate.kind == baseline.kind) {
                "remote catalog changed model kind for " + baseline.modelId
            }
            require(candidate.sourceType == baseline.sourceType) {
                "remote catalog changed source type for " + baseline.modelId
            }
            require(candidate.revision >= baseline.revision) {
                "remote catalog revision regressed for " + baseline.modelId
            }
        }
    }

    private fun updateOperation(
        modelId: String,
        status: ModelOperationStatus,
    ) {
        _operations.update { current ->
            current + (modelId to status)
        }
    }

    private fun clearOperation(modelId: String) {
        _operations.update { current ->
            current - modelId
        }
    }

    private fun isIntegrityFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return "sha-256" in message ||
            "sha256" in message ||
            "size mismatch" in message ||
            "verification" in message ||
            "archive entry" in message ||
            "extracted model files" in message
    }
}
