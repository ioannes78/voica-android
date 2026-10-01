package io.github.ioannes78.voica.model

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) : ModelManager {
    private val packages = packageDirectory.canonicalFile
    private val catalogMutex = Mutex()
    private val modelMutexes = ConcurrentHashMap<String, Mutex>()
    private val installJobs = ConcurrentHashMap<String, Job>()
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

    override suspend fun activeModel(modelId: String): ActiveModel? {
        val activation = storage.activationState(modelId)
        val activeVersion = activation.activeVersion
        val activeRevision = activation.activeRevision

        if (activeVersion != null && activeRevision != null) {
            val snapshot =
                storage.installedSnapshot(
                    modelId = modelId,
                    version = activeVersion,
                    revision = activeRevision,
                ) ?: return null
            return ActiveModel(
                descriptor = snapshot.descriptor,
                manifestDigest = snapshot.manifestDigest,
                installedDirectory =
                    storage.activeDirectory(modelId)
                        ?: return null,
            )
        }

        val builtin = bundledCatalog.model(modelId) ?: return null
        val builtinAvailable =
            builtin.sourceType == ModelSourceType.BUILTIN ||
                builtin.sourceType == ModelSourceType.BUILTIN_WITH_OVERRIDE
        if (!builtinAvailable) return null
        return ActiveModel(
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
        val activation = storage.activationState(modelId)
        val exactInstalled = storage.installedVersion(effective)

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
            var staging: File? = null

            try {
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

                extractor.verifyPackage(descriptor, packagePart)
                staging = storage.createStagingDirectory(descriptor)
                extractor.extract(descriptor, packagePart, staging)
                storage.promoteVerifiedStaging(
                    descriptor = descriptor,
                    stagingDirectory = staging,
                    manifestDigest = manifestDigest,
                )
                staging = null

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
                        state =
                            if (isIntegrityFailure(error)) {
                                ModelState.CORRUPTED
                            } else {
                                ModelState.LOAD_FAILED
                            },
                        errorMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
                throw error
            } finally {
                packagePart.delete()
                staging?.deleteRecursively()
                installJobs.remove(modelId, currentJob)
            }
        }
    }

    override suspend fun cancelInstall(modelId: String) {
        installJobs[modelId]?.cancel(
            CancellationException("model install cancelled"),
        )
    }

    override suspend fun confirmInstalledVersion(
        modelId: String,
        version: String,
        revision: Long,
    ) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            val snapshot =
                storage.installedSnapshot(modelId, version, revision)
                    ?: error("model candidate metadata is missing or failed integrity verification")
            val descriptor = snapshot.descriptor
            require(descriptor.compatibilityWith(environment).compatible) {
                "model is incompatible with this device/app/runtime"
            }
            val installed =
                storage.installedVersion(descriptor)
                    ?: error("model candidate is not installed or failed integrity verification")
            updateOperation(
                modelId,
                ModelOperationStatus(state = ModelState.VERIFYING),
            )
            try {
                candidateValidator.validate(
                    descriptor = descriptor,
                    installedDirectory = installed.directory,
                )
                storage.confirmGood(descriptor)
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
            clearOperation(modelId)
        }
    }

    override suspend fun rollback(modelId: String) {
        val mutex = modelMutexes.computeIfAbsent(modelId) { Mutex() }
        mutex.withLock {
            val state = storage.activationState(modelId)
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
                storage.rollback(modelId)
            } catch (error: IllegalStateException) {
                if (!hasBuiltinFallback ||
                    activeVersion == null ||
                    activeRevision == null
                ) {
                    throw error
                }
                storage.clearActivation(modelId)
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
